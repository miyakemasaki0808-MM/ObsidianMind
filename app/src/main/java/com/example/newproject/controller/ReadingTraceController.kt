package com.example.newproject.controller

import com.example.newproject.data.ReadingTracePersistence
import com.example.newproject.data.ReadingTraceReadResult
import com.example.newproject.data.ReadingTraceSaveResult
import com.example.newproject.model.ReadingTraceStateWriter
import com.example.newproject.model.ReadingTrace
import com.example.newproject.model.state.ReadingTraceCard
import com.example.newproject.model.ReadingTraceLimits
import com.example.newproject.model.ReadingVisit
import com.example.newproject.model.truncateToUtf8Bytes
import com.example.newproject.model.needsAiSummary
import com.example.newproject.model.withVisit
import com.example.newproject.model.withoutLastVisit
import com.example.newproject.ai.AiAvailability
import com.example.newproject.ai.AiClient
import com.example.newproject.ai.PromptBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 読んだ位置を追跡してサイドカーへ記録し、Rediscover で再会した時に
 * 「前回のあなた」カードを出す。
 *
 * ユーザーはこの機能を操作しない（残す導線もボタンも無い）。普通に読むだけで訪問が
 * 溜まり、AIの役割は溜まった訪問の俯瞰要約だけ。中身は全部ユーザー自身の読み方なので
 * 「前回の自分」であり続ける。
 *
 * 進捗報告はスクロールごとに来るのでメモリ上の最大値更新だけに留め、
 * I/Oは離脱時（[flush]）と背面化時（[pause]）に絞る。背面化で書いた訪問は
 * 復帰後の離脱で差し替えるので、1回の閲覧＝1訪問が保たれる。
 */
internal class ReadingTraceController(
    private val scope: CoroutineScope,
    /**
     * 訪問の書き出し専用スコープ。**アプリ寿命であること**が前提。
     *
     * `viewModelScope` に載せてはいけない。タスクスワイプや Activity finish では
     * `onStop()` → [pause] の直後に `onCleared()` が走るため、IOへディスパッチされる
     * 前のコルーチンがキャンセルされ、確定させたはずの訪問が失われる
     * （背面化だけなら失われないので、KDocの意図が終了経路でだけ破れていた）。
     *
     * 土台は Main.immediate であることも前提。[Session] の各フィールドはメインスレッド
     * からのみ触る規律で書かれており、保存失敗時の巻き戻しもそこへ戻ってくる。
     */
    private val persistScope: CoroutineScope,
    private val aiClient: AiClient,
    private val state: ReadingTraceStateWriter,
    private val persistence: ReadingTracePersistence,
    /**
     * 現在のVaultの識別子。ノートを開いた時点の値をセッションへ写し取り、保存要求に
     * 添えて運ぶ。保存は非同期に走るため、書込時点の現在Vaultから保存先を解決すると
     * 旧ノートの痕跡が切替後の新Vaultへ書き込まれ得る。
     */
    private val currentVaultKey: () -> String?,
    private val clock: () -> Long = System::currentTimeMillis,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    /**
     * 読書中のノートの作業スナップショット。UI状態には出さない。
     *
     * [vaultRelativePath] を後から埋められる（var）のは、さがす・関連から開いた場合に
     * 相対パスが即座に分からないため。走査キャッシュが冷えていると解決にI/Oが要り、
     * それを表示前に挟むとノート表示が遅れる。パス未確定でもセッションだけ先に作って
     * 進捗を溜め、[bindPath] で後から結び付ける。
     */
    private class Session(
        val id: Long,
        var vaultRelativePath: String?,
        val noteTitle: String,
        val documentId: String?,
        /** このノートを開いた時点のVault。保存はここへ向けてしか行わない。 */
        val vaultKey: String,
        openedAtMillis: Long
    ) {
        var deepestBlockIndex = 0

        /**
         * 最深ブロックがどこまで見えていたか（0f〜1f）。ブロック数だけで到達率を測ると
         * 長大な1ブロックを冒頭だけ見ても100%になるため、ブロック内の可視量まで見る。
         */
        var deepestBlockFraction = 0f
        var totalBlocks = 0
        var deepestSectionTitle: String? = null

        /** 背面にいた分を除いた、これまでの能動読書時間。 */
        var activeMillis = 0L

        /** 現在の読書区間の開始時刻。背面化中は null。 */
        var resumedAtMillis: Long? = openedAtMillis

        /**
         * この閲覧で書き込み済みの訪問。背面化のたびに訪問を増やさず、同じ1件を
         * 更新し続けるための目印（→ [recordVisit]）。
         */
        var recordedVisit: ReadingVisit? = null

        /** 前回の書き込み以降に、書き直す価値のある変化があったか。 */
        var dirty = false

        /** 背面にいた時間を除いた読書時間。10秒判定はこれで行う。 */
        fun elapsedMillis(now: Long): Long =
            activeMillis + (resumedAtMillis?.let { now - it } ?: 0L)
    }

    private var session: Session? = null

    // サイドカーの read-modify-write を直列化する。訪問の追記と要約の書き戻しが
    // 交差すると、読み取りが古いまま上書きして訪問を取りこぼしうる。
    // （AI生成の直列化は AiClient 側の責務で、こちらとは別の関心事）
    private val writeMutex = Mutex()

    private var revealJob: Job? = null
    private var activeRequestId = 0L

    private var sessionCounter = 0L

    /**
     * ノートを開いた。ここから読書時間の計測を始める。
     *
     * [vaultRelativePath] は未確定なら null で構わない（[bindPath] で後から埋める）。
     * 最後まで埋まらなければ [flush] は何も記録しない。
     *
     * @return このセッションの識別子。[bindPath] に渡す。
     */
    fun onNoteOpened(vaultRelativePath: String?, noteTitle: String, documentId: String?): Long {
        val id = ++sessionCounter
        // Vault未選択なら保存先が無いので、そもそも追跡しない。
        val vaultKey = currentVaultKey()
        session = vaultKey?.let {
            Session(
                id = id,
                vaultRelativePath = vaultRelativePath?.takeIf { path -> path.isNotBlank() },
                noteTitle = noteTitle,
                documentId = documentId,
                vaultKey = it,
                openedAtMillis = clock()
            )
        }
        return id
    }

    /**
     * 表示後に判明した相対パスを、[sessionId] のセッションへ結び付ける。
     *
     * [sessionId] を要求するのは、パス未確定のノートを続けて開いた時に、前のノートの
     * 遅れた解決結果が次のノートのセッションへ吸い込まれるのを防ぐため
     * （それが起きると別ノートのパスで訪問を記録してしまう）。
     * 既にパスが確定しているセッションには何もしない。
     */
    fun bindPath(sessionId: Long, vaultRelativePath: String) {
        if (vaultRelativePath.isBlank()) return
        val active = session ?: return
        if (active.id != sessionId) return
        if (active.vaultRelativePath == null) active.vaultRelativePath = vaultRelativePath
    }

    /**
     * 表示位置の報告。[blockIndex] は「最後に見えていたブロック」の index、
     * [blockFraction] はそのブロックがどこまで見えていたか（0f〜1f）。
     * 先頭可視ブロックではなく最終可視ブロックを見るのは、先頭基準だと画面に
     * 収まる分だけ最後まで届かず「読み切った」を表現できないため。
     * [sectionTitle] は UI 側が sectionModel から解決済みの値を渡す
     * （本文の再パースを避け、Controller が ui パッケージへ依存しないようにする）。
     */
    fun onReadingProgress(
        blockIndex: Int,
        blockFraction: Float,
        totalBlocks: Int,
        sectionTitle: String?
    ) {
        val active = session ?: return
        if (totalBlocks > active.totalBlocks) {
            active.totalBlocks = totalBlocks
            active.dirty = true
        }
        // スクロールを戻しても最深到達点は下げない。同じブロックに留まっていても、
        // より深くまで見えていれば（長大ブロックを読み進めた）最深を更新する。
        val deeper = blockIndex > active.deepestBlockIndex ||
            (blockIndex == active.deepestBlockIndex && blockFraction > active.deepestBlockFraction)
        if (deeper) {
            active.deepestBlockIndex = blockIndex
            active.deepestBlockFraction = blockFraction.coerceIn(0f, 1f)
            active.deepestSectionTitle = sectionTitle
            active.dirty = true
        }
    }

    /**
     * ノートを離れるときに呼ぶ。条件を満たしていれば訪問を記録し、セッションを終える。
     *
     * 一定時間読んでいない表示は記録しない。一瞬引いてすぐ次のノートへ送った分を
     * 訪問に数えると痕跡が濁るうえ、ノートを表示するたびSAF書込が走る
     * （Vaultがクラウドなら同期トラフィックにもなる）。
     */
    fun flush() {
        recordVisit()
        session = null
    }

    /**
     * アプリが背面へ回るときに呼ぶ。読書時間の計測を止め、条件を満たしていれば訪問を記録する。
     *
     * ここで記録するのは、背面のままプロセスが終了しても読書が失われないようにするため。
     * ただしセッションは**残す**ので、[resume] 後に読み進めれば同じ訪問が更新される
     * （ホームボタンを押すたび「これまで◯回開いています」が増えるのを防ぐ）。
     */
    fun pause() {
        val active = session ?: return
        val resumedAt = active.resumedAtMillis
        if (resumedAt != null) {
            active.activeMillis += clock() - resumedAt
            active.resumedAtMillis = null
        }
        recordVisit()
    }

    /**
     * 背面から復帰したときに呼ぶ。読書時間の計測を再開する。
     *
     * 背面にいた時間は積算しないので、「5秒読んで放置し、戻ってすぐ離れた」が
     * 10秒の訪問条件を満たしてしまうことはない。
     */
    fun resume() {
        val active = session ?: return
        if (active.resumedAtMillis != null) return
        active.resumedAtMillis = clock()
        // 復帰後に離脱すれば最終閲覧日時は更新すべきなので、書き直す対象とする。
        active.dirty = true
    }

    /**
     * 現在のセッションの訪問を書き出す。既にこの閲覧で書いた訪問があれば、
     * 増やさずにその1件を差し替える（1回の閲覧＝1訪問を保つ）。
     */
    private fun recordVisit() {
        val active = session ?: return
        // 前回の書き込みから何も変わっていなければ、SAF書込を出さない。
        if (!active.dirty) return
        // 相対パスが最後まで分からなかったノート（_AI補記 の一覧から開いた等）は記録しない。
        val path = active.vaultRelativePath ?: return
        if (active.elapsedMillis(clock()) < MIN_READING_MILLIS) return
        // 本文がまだ描画されていない（進捗報告が来ていない）場合は読んだと見なさない。
        if (active.totalBlocks <= 0) return

        val visit = ReadingVisit(
            atEpochMillis = clock(),
            deepestSectionTitle = active.deepestSectionTitle,
            progressPercent = progressPercent(
                active.deepestBlockIndex,
                active.deepestBlockFraction,
                active.totalBlocks
            )
        )
        // 起動前に消費済みにして、同じ状態で二重に書き込まないようにする。
        // 書けなかった場合はこの2つを戻し、次の契機で書き直させる（下の Failure 分岐）。
        val previous = active.recordedVisit
        active.recordedVisit = visit
        active.dirty = false
        val title = active.noteTitle
        val documentId = active.documentId
        val vaultKey = active.vaultKey
        val owner = active

        persistScope.launch {
            val result = withContext(ioDispatcher) {
                writeMutex.withLock {
                    val base = when (val existing = persistence.load(path, vaultKey)) {
                        is ReadingTraceReadResult.Valid -> {
                            // タイトルと documentId は最新の値へ寄せ直す（改名・別端末での再バインド）。
                            val trace = existing.trace.copy(noteTitle = title, documentId = documentId)
                            // この閲覧で既に書いた訪問が末尾にあれば、追記ではなく差し替える。
                            // 別端末が後から追記していれば末尾が一致しないので、その時は素直に追記する。
                            // withoutLastVisit は累計も戻す（戻さないと背面化のたびに回数が増える）。
                            if (previous != null && trace.visits.lastOrNull() == previous) {
                                trace.withoutLastVisit()
                            } else {
                                trace
                            }
                        }
                        // 未作成も破損も新規として作り直す。壊れたファイルは上書きで直す
                        // （過去の痕跡は失うが、ユーザーのノートには一切触れない）。
                        else -> ReadingTrace(
                            vaultRelativePath = path,
                            noteTitle = title,
                            documentId = documentId,
                            visits = emptyList()
                        )
                    }
                    persistence.save(base.withVisit(visit), vaultKey)
                }
            }
            // 書けていなければ「まだ書いていない」状態へ戻し、次の契機（背面化・離脱）で
            // 書き直させる。ここを捨てると、消費済みの印だけが残って
            // そのセッションの訪問は恒久的に失われる。
            //
            // 巻き戻すのは、自分が書こうとした訪問がまだ最新である場合だけ。待っている間に
            // さらに読み進めて別の訪問が積まれていたら、戻すと古い方を復活させてしまう。
            // 既に別ノートへ移っていた場合は owner が現役でないセッションを指すが、
            // 誰も読まないので害はない（そのための照合は置かない）。
            if (result is ReadingTraceSaveResult.Failure && owner.recordedVisit === visit) {
                owner.recordedVisit = previous
                owner.dirty = true
            }
        }
    }

    /**
     * 記録せずにセッションを捨てる。Vault切替時に使う。
     *
     * 起動済みの保存コルーチンには効かないが、それらは要求時点の [Session.vaultKey] を
     * 運んでおり、Gateway が現在のVaultと照合して不一致なら捨てる。ここで捨てるのは
     * 「切替後に新しく保存要求が生まれること」を止めるため。
     */
    fun discard() {
        session = null
    }

    /** ノート切替時に、進行中の照合・要約生成を捨てる（後着で別ノートのカードを出さない）。 */
    fun cancelForNoteChange() {
        revealJob?.cancel()
        activeRequestId++
    }

    /**
     * 「前回のあなた」を照合してカードに載せる。**Rediscover 経路からのみ呼ぶ**
     * （検索・関連・直接オープンでは呼ばない＝カードが出ない）。
     *
     * 痕跡が無い／破損しているときは何もしない。カードを出さないだけで、
     * ユーザーのノートには一切触れない。
     */
    fun revealTrace(vaultRelativePath: String) {
        revealJob?.cancel()
        val requestId = ++activeRequestId
        if (vaultRelativePath.isBlank()) return
        // どのVaultへの照合かは、サスペンドする前のこの時点で決める。
        val vaultKey = currentVaultKey() ?: return
        revealJob = scope.launch {
            val trace = withContext(ioDispatcher) {
                (persistence.load(vaultRelativePath, vaultKey) as? ReadingTraceReadResult.Valid)?.trace
            } ?: return@launch
            if (!isCurrent(requestId)) return@launch

            // まず生の痕跡でカードを出す。AIを待たせないのが要点。
            val needsSummary = trace.needsAiSummary
            setCard(cardOf(trace, isSummaryLoading = needsSummary))
            if (!needsSummary) return@launch

            val summary = generateSummary(trace)
            if (!isCurrent(requestId)) return@launch
            if (summary == null) {
                // 失敗しても生の痕跡は残す。読み込み表示だけ下げる。
                setCard(cardOf(trace))
                return@launch
            }
            setCard(cardOf(trace, aiSummary = summary))
            persistSummary(trace, summary, vaultKey)
        }
    }

    /** 「読んだ」で畳む。永続化しないので次回 Rediscover では再表示される。 */
    fun dismissCard() {
        state.update { it?.copy(isDismissed = true) }
    }

    private fun setCard(card: ReadingTraceCard) {
        state.update { current ->
            // 畳んだ状態は、後から届いた要約で開き直さない。
            val dismissed = current?.isDismissed == true
            card.copy(isDismissed = dismissed)
        }
    }

    private fun cardOf(
        trace: ReadingTrace,
        // 訪問が増えていればキャッシュ済み要約は古いので出さない。
        // 保持件数ではなく累計で見る（30件で頭打ちになると古い要約が出続ける）。
        aiSummary: String? = trace.aiSummary?.takeIf { trace.aiSummaryVisitCount == trace.totalVisitCount },
        isSummaryLoading: Boolean = false
    ): ReadingTraceCard {
        val last = trace.visits.last()
        return ReadingTraceCard(
            visitCount = trace.totalVisitCount,
            lastVisitAtMillis = last.atEpochMillis,
            lastSectionTitle = last.deepestSectionTitle,
            lastProgressPercent = last.progressPercent,
            aiSummary = aiSummary,
            isSummaryLoading = isSummaryLoading
        )
    }

    private suspend fun generateSummary(trace: ReadingTrace): String? = try {
        // 未ダウンロードでも自動DLしない（読むたびモデルDLを始めない）。黙って生のまま。
        if (aiClient.checkAvailability() != AiAvailability.Available) {
            null
        } else {
            val prompt = PromptBuilder.buildReadingTraceSummaryPrompt(
                noteTitle = trace.noteTitle,
                visits = trace.visits,
                totalVisitCount = trace.totalVisitCount
            )
            aiClient.generate(prompt)
                .trim()
                .takeIf { it.isNotBlank() }
                ?.let { truncateToUtf8Bytes(it, ReadingTraceLimits.MAX_AI_SUMMARY_BYTES) }
        }
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        // タイムアウト・生成失敗も黙って劣化させる。ユーザーが意識しない機能なので
        // エラー表示は出さず、生の痕跡だけが見えている状態に留める。
        null
    }

    /**
     * 生成した要約をサイドカーへ載せる。
     *
     * 訪問の保存（[recordVisit]）と違い、**保存結果を見ないし再試行もしない**。
     * 書けなければ `aiSummaryVisitCount` が更新されないだけで、次回の再会で
     * [needsAiSummary] が真になり自動的に作り直される（自己修復する）。
     * 同じ理由でアプリ寿命のスコープにも載せない — 失っても取り返せるものを
     * ノート切替後まで走らせ続ける必要はない。
     */
    private suspend fun persistSummary(trace: ReadingTrace, summary: String, vaultKey: String) {
        withContext(ioDispatcher) {
            writeMutex.withLock {
                // 生成中に flush が訪問を足している可能性があるので、最新を読み直して
                // 要約だけを載せる。件数は「要約が説明している訪問数」を記録するので、
                // 生成中に増えていれば次回の再会でちゃんと作り直される。
                val latest = (persistence.load(trace.vaultRelativePath, vaultKey) as? ReadingTraceReadResult.Valid)
                    ?.trace
                    ?: return@withLock
                persistence.save(
                    latest.copy(aiSummary = summary, aiSummaryVisitCount = trace.totalVisitCount),
                    vaultKey
                )
            }
        }
    }

    private fun isCurrent(requestId: Long): Boolean = requestId == activeRequestId

    /**
     * 到達率。分子は「読み終えたブロック数＋最深ブロックの可視割合」。
     *
     * 切り捨てにしているのは、100% を「最終ブロックの末端が画面に入った」場合だけに
     * 限定するため。丸めにすると末尾が少し残っていても100%になり、カードが
     * 「最後まで読んでいます」と誤って断定してしまう。
     */
    private fun progressPercent(
        deepestBlockIndex: Int,
        deepestBlockFraction: Float,
        totalBlocks: Int
    ): Int {
        if (totalBlocks <= 0) return 0
        val reached = deepestBlockIndex.coerceIn(0, totalBlocks - 1) +
            deepestBlockFraction.coerceIn(0f, 1f)
        return (reached * 100f / totalBlocks).toInt().coerceIn(0, 100)
    }

    private companion object {
        const val MIN_READING_MILLIS = 10_000L
    }
}
