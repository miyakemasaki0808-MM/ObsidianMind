package com.example.newproject

import com.example.newproject.ai.AiAvailability
import com.example.newproject.ai.AiClient
import com.example.newproject.ai.PromptBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
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
 * I/Oは離脱時の1回に絞る。
 */
internal class ReadingTraceController(
    private val scope: CoroutineScope,
    private val aiClient: AiClient,
    private val uiState: MutableStateFlow<NoteUiState>,
    private val persistence: ReadingTracePersistence,
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
        val openedAtMillis: Long
    ) {
        var deepestBlockIndex = 0
        var totalBlocks = 0
        var deepestSectionTitle: String? = null
        var recorded = false
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
        session = Session(
            id = id,
            vaultRelativePath = vaultRelativePath?.takeIf { it.isNotBlank() },
            noteTitle = noteTitle,
            documentId = documentId,
            openedAtMillis = clock()
        )
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
     * 表示位置の報告。[blockIndex] は「最後に見えていたブロック」の index。
     * 先頭可視ブロックではなく最終可視ブロックを見るのは、先頭基準だと画面に
     * 収まる分だけ最後まで届かず「読み切った」を表現できないため。
     * [sectionTitle] は UI 側が sectionModel から解決済みの値を渡す
     * （本文の再パースを避け、Controller が ui パッケージへ依存しないようにする）。
     */
    fun onReadingProgress(blockIndex: Int, totalBlocks: Int, sectionTitle: String?) {
        val active = session ?: return
        if (totalBlocks > active.totalBlocks) active.totalBlocks = totalBlocks
        // スクロールを戻しても最深到達点は下げない。
        if (blockIndex >= active.deepestBlockIndex) {
            active.deepestBlockIndex = blockIndex
            active.deepestSectionTitle = sectionTitle
        }
    }

    /**
     * ノートを離れる／アプリが背面に回るときに呼ぶ。条件を満たしていれば訪問を1件記録する。
     *
     * 一定時間読んでいない表示は記録しない。一瞬引いてすぐ次のノートへ送った分を
     * 訪問に数えると痕跡が濁るうえ、ノートを表示するたびSAF書込が走る
     * （Vaultがクラウドなら同期トラフィックにもなる）。
     */
    fun flush() {
        val active = session ?: return
        if (active.recorded) return
        // 相対パスが最後まで分からなかったノート（_AI補記 の一覧から開いた等）は記録しない。
        val path = active.vaultRelativePath ?: return
        if (clock() - active.openedAtMillis < MIN_READING_MILLIS) return
        // 本文がまだ描画されていない（進捗報告が来ていない）場合は読んだと見なさない。
        if (active.totalBlocks <= 0) return

        // 同じセッションで二重に記録しないよう、起動前に消費済みにする。
        active.recorded = true
        val visit = ReadingVisit(
            atEpochMillis = clock(),
            deepestSectionTitle = active.deepestSectionTitle,
            progressPercent = progressPercent(active.deepestBlockIndex, active.totalBlocks)
        )
        val title = active.noteTitle
        val documentId = active.documentId

        scope.launch {
            withContext(ioDispatcher) {
                writeMutex.withLock {
                    val base = when (val existing = persistence.load(path)) {
                        is ReadingTraceReadResult.Valid ->
                            // タイトルと documentId は最新の値へ寄せ直す（改名・別端末での再バインド）。
                            existing.trace.copy(noteTitle = title, documentId = documentId)
                        // 未作成も破損も新規として作り直す。壊れたファイルは上書きで直す
                        // （過去の痕跡は失うが、ユーザーのノートには一切触れない）。
                        else -> ReadingTrace(
                            vaultRelativePath = path,
                            noteTitle = title,
                            documentId = documentId,
                            visits = emptyList()
                        )
                    }
                    persistence.save(base.withVisit(visit))
                }
            }
        }
    }

    /**
     * 記録せずにセッションを捨てる。Vault切替時に使う。
     * 保存先は書き込み時点の vaultUri から解決されるため、切替後に flush すると
     * 旧Vaultのノートの痕跡を新Vaultへ書いてしまう。
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
        revealJob = scope.launch {
            val trace = withContext(ioDispatcher) {
                (persistence.load(vaultRelativePath) as? ReadingTraceReadResult.Valid)?.trace
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
            persistSummary(trace, summary)
        }
    }

    /** 「読んだ」で畳む。永続化しないので次回 Rediscover では再表示される。 */
    fun dismissCard() {
        uiState.update { current ->
            current.copy(readingTraceCard = current.readingTraceCard?.copy(isDismissed = true))
        }
    }

    private fun setCard(card: ReadingTraceCard) {
        uiState.update { current ->
            // 畳んだ状態は、後から届いた要約で開き直さない。
            val dismissed = current.readingTraceCard?.isDismissed == true
            current.copy(readingTraceCard = card.copy(isDismissed = dismissed))
        }
    }

    private fun cardOf(
        trace: ReadingTrace,
        // 訪問が増えていればキャッシュ済み要約は古いので出さない。
        aiSummary: String? = trace.aiSummary?.takeIf { trace.aiSummaryVisitCount == trace.visits.size },
        isSummaryLoading: Boolean = false
    ): ReadingTraceCard {
        val last = trace.visits.last()
        return ReadingTraceCard(
            visitCount = trace.visits.size,
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
            val prompt = PromptBuilder.buildReadingTraceSummaryPrompt(trace.noteTitle, trace.visits)
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

    private suspend fun persistSummary(trace: ReadingTrace, summary: String) {
        withContext(ioDispatcher) {
            writeMutex.withLock {
                // 生成中に flush が訪問を足している可能性があるので、最新を読み直して
                // 要約だけを載せる。件数は「要約が説明している訪問数」を記録するので、
                // 生成中に増えていれば次回の再会でちゃんと作り直される。
                val latest = (persistence.load(trace.vaultRelativePath) as? ReadingTraceReadResult.Valid)
                    ?.trace
                    ?: return@withLock
                persistence.save(
                    latest.copy(aiSummary = summary, aiSummaryVisitCount = trace.visits.size)
                )
            }
        }
    }

    private fun isCurrent(requestId: Long): Boolean = requestId == activeRequestId

    private fun progressPercent(deepestBlockIndex: Int, totalBlocks: Int): Int {
        if (totalBlocks <= 0) return 0
        val reached = (deepestBlockIndex + 1).coerceIn(1, totalBlocks)
        return (reached * 100 / totalBlocks).coerceIn(0, 100)
    }

    private companion object {
        const val MIN_READING_MILLIS = 10_000L
    }
}
