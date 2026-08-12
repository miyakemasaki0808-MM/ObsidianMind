package com.example.newproject.controller

import com.example.newproject.data.ReadingTracePersistence
import com.example.newproject.data.ReadingTraceReadResult
import com.example.newproject.data.ReadingTraceSaveResult
import com.example.newproject.model.ReadingTraceStateWriter
import com.example.newproject.model.ReadingTrace
import com.example.newproject.model.state.ReadingTraceCard
import com.example.newproject.model.ReadingTraceLimits
import com.example.newproject.model.ReadingVisit
import com.example.newproject.model.Reflection
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

        /**
         * まだサイドカーへ載せていないひとこと（→ [setPendingRemark]）。
         *
         * 生成した直後に単独で保存しないのは、**痕跡ファイルがまだ存在しない**ことが
         * あるため。訪問は離脱・背面化でしか書かれず、`validateReadingTrace` は
         * 訪問が1件以上あることを要求する。初読の最中にボタンを押すこの機能では
         * 「生成できたら保存」と書くと初回のノートで必ず黙って失われる
         * （→ features/reflect_remark.md §2.1）。
         */
        var pendingRemark: Reflection? = null

        /** 背面にいた時間を除いた読書時間。10秒判定はこれで行う。 */
        fun elapsedMillis(now: Long): Long =
            activeMillis + (resumedAtMillis?.let { now - it } ?: 0L)
    }

    private var session: Session? = null

    /**
     * **書けなかった痕跡。セッションが終わった後も残す。**
     *
     * [flush] は保存コルーチンを起動した直後に `session = null` にするため、
     * 書き込みが後から失敗しても**戻す先のセッションがもう現役でない**。
     * `owner.pendingRemark` へ戻しても誰も読まず、ユーザーの返事がそこで消えていた。
     *
     * **持つのは返事ではなく、保存しようとした [ReadingTrace] そのもの。**
     * 返事だけを持つと「既存の痕跡へ載せ直す」ことしかできず、
     * **痕跡の新規作成が失敗した場合に復旧できない**（初読で返事まで書いた回が
     * まるごと落ちる）。完成済みの痕跡なら、ファイルが無くてもそのまま作れる。
     *
     * **1件ではなくノート単位で持つ。** 単一スロットだと、Aが退避中にBで返事を
     * 書いた瞬間にAが消える。キーは Vault＋相対パス。
     *
     * **プロセスが死ねば失われる**が、それは全ての未保存データと同じ条件になる。
     */
    private val pendingWrites = LinkedHashMap<String, PendingWrite>()

    /**
     * [pendingWrites] 専用のロック。
     *
     * このMapは Main（`persistScope` の土台）と IO の両方から触られる。
     * [writeMutex] と兼用しないのは、退避の書き直しが
     * 「スナップショットを取る → 1件ずつ writeMutex で保存する → 消す」という順で動き、
     * **保存の間はこちらを離しておく**必要があるため。
     * 取得順は常に `writeMutex → pendingMutex` の一方向に保つ（逆は作らない）。
     */
    private val pendingMutex = Mutex()

    /** 書けなかった痕跡。Vaultキーごと持つ（切替後に別Vaultへ書かないため）。 */
    private class PendingWrite(val vaultKey: String, val trace: ReadingTrace)

    private fun pendingKey(vaultKey: String, path: String) = "$vaultKey\n$path"

    /**
     * 書けなかった痕跡を覚える。**同じノートは上書きしてよい**（新しいほうが正しい）。
     *
     * **返事を持つ痕跡だけを積む。** 訪問だけの失敗はセッション側の巻き戻し
     * （`dirty` / `recordedVisit`）が同じ閲覧のうちに書き直すし、失っても
     * もう一度読めば付き直る。**返事は作り直せない**ので扱いが違う。
     *
     * これで「普通の訪問痕跡が溜まって、返事付きの退避を押し出す」経路が消える —
     * 上限に当たるのは**返事の保存が8ノートぶん失敗し続けたとき**だけになる。
     */
    private suspend fun rememberPendingWrite(vaultKey: String, trace: ReadingTrace) {
        if (trace.reflection?.hasReply != true) return
        pendingMutex.withLock {
            val key = pendingKey(vaultKey, trace.vaultRelativePath)
            if (key !in pendingWrites && pendingWrites.size >= MAX_PENDING_WRITES) {
                pendingWrites.remove(pendingWrites.keys.first())
            }
            pendingWrites[key] = PendingWrite(vaultKey, trace)
        }
    }

    /** 直接保存できたノートの退避を捨てる。残すと古い内容で上書きし直してしまう。 */
    private suspend fun forgetPendingWrite(vaultKey: String, path: String) {
        pendingMutex.withLock { pendingWrites.remove(pendingKey(vaultKey, path)) }
    }

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
     * ノートへのひとことを、次の書き込み契機（背面化・離脱）へ預ける。
     *
     * **`dirty` を立てるのが要点。** 立てないと、直前に訪問を書き終えていた場合に
     * [recordVisit] が「変化なし」で早期returnし、ひとことが書かれないまま
     * セッションが終わる。ひとことは「書き直す価値のある変化」そのものである。
     *
     * セッションが無い（Vault未選択・追跡対象外）ときは黙って捨てる。
     * 保存先が無いので、ここで作れる置き場所は無い。
     */
    fun setPendingRemark(reflection: Reflection) {
        val active = session ?: return
        active.pendingRemark = reflection
        active.dirty = true
    }

    /**
     * 読書中のノートの相対パス。**ひとことの保存先を引くのに使う。**
     *
     * UI へ配らずここから引くのは、相対パスの出所を1つに保つため。
     * 走査キャッシュが冷えていると表示後に [bindPath] で埋まるので、
     * `NoteState` に持たせると「まだ null の瞬間」を画面側が扱うことになる。
     */
    fun currentPath(): String? = session?.vaultRelativePath

    /**
     * 保存済みの「ひとこと＋返事」を読む。**専用画面を開いたときにだけ呼ぶ。**
     *
     * ノート表示の経路には置かない。開くたびにサイドカーを1件読むことになり、
     * 遠いプロバイダでは体感に乗る（→ 痕跡の索引コストと同じ問題圏）。
     */
    suspend fun loadReflection(vaultRelativePath: String): Reflection? {
        if (vaultRelativePath.isBlank()) return null
        val vaultKey = currentVaultKey() ?: return null
        return withContext(ioDispatcher) {
            writeMutex.withLock {
                (persistence.load(vaultRelativePath, vaultKey) as? ReadingTraceReadResult.Valid)
                    ?.trace
                    ?.reflection
            }
        }
    }

    /**
     * 返事を**即時に**書き出す。
     *
     * ひとこと（AI生成）は離脱時の書き込みへ相乗りさせるが、返事は違う。
     * **ユーザーが明示的に書いたものなので、アプリが落ちても失ってはいけない。**
     * 生成物は作り直せるが、書いた言葉は作り直せない。
     *
     * **戻り値を Boolean にしない。** 「書けた」「まだ書けていないが預かった」
     * 「どこにも残っていない」で**呼び出し側の次の行動が違う**ため
     * （→ lessons L28）。true/false に畳むと、預かっただけの状態と
     * 完全に失った状態が同じ顔になり、実際そうなっていた。
     */
    suspend fun saveReply(
        vaultRelativePath: String,
        reply: String,
        atEpochMillis: Long
    ): ReplySaveOutcome {
        val vaultKey = currentVaultKey()
            // Vault未選択では保存先が無く、セッションも無いので預ける先も無い。
            ?: return holdOrLose(reply, atEpochMillis)
        return withContext(ioDispatcher) {
            flushPendingWrites()
            writeMutex.withLock {
                val existing = try {
                    (persistence.load(vaultRelativePath, vaultKey) as? ReadingTraceReadResult.Valid)
                        ?.trace
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
                if (existing == null) {
                    // 痕跡がまだ無い（または読めない）＝この閲覧で訪問が確定していない。
                    // セッションへ預け直して、離脱時の書き込みに載せる。
                    return@withLock holdOrLose(reply, atEpochMillis)
                }
                val reflection = existing.reflection?.withReply(reply, atEpochMillis)
                    // ひとこと無しに返事だけを保存する経路は作らない（組で持つため）。
                    ?: return@withLock ReplySaveOutcome.Lost
                val saved = try {
                    persistence.save(existing.copy(reflection = reflection), vaultKey)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
                if (saved is ReadingTraceSaveResult.Success) {
                    // **ここで退避を捨てる必要は無い。** 一度書いてから消した —
                    // 退避側が日時で新旧を見るため、古い退避は次の契機で
                    // 書き戻されずにそのまま捨てられる（読み込み1回で済み、書き込みは出ない）。
                    // 実際、外しても落ちるテストが1つも無かった
                    // （→ lessons L11。冗長なガードは足さない）。
                    ReplySaveOutcome.Saved
                } else {
                    // **書けなかったぶんを必ず退避する。** ここを握り潰すと、
                    // 画面に「保存済み」と出たまま返事が消える。
                    rememberPendingWrite(vaultKey, existing.copy(reflection = reflection))
                    holdOrLose(reply, atEpochMillis)
                    ReplySaveOutcome.Held
                }
            }
        }
    }

    /**
     * 退避してある痕跡を書き直す。**書き込み契機のたびに先頭で試す。**
     *
     * ファイルが無ければ退避した痕跡をそのまま作る（**新規作成の失敗を復旧できる**のが、
     * 返事だけを持っていた頃との違い）。既にあれば、そこへ [Reflection] を載せ直す —
     * 待っている間に訪問が増えている可能性があるので、丸ごと上書きはしない。
     */
    private suspend fun flushPendingWrites(excludePath: String? = null) {
        // Vaultが切り替わっていたら書かない。旧Vaultの内容を新Vaultへ入れない。
        val current = currentVaultKey() ?: return
        val targets = pendingMutex.withLock {
            pendingWrites.entries
                .filter { it.value.vaultKey == current }
                // **これから書くノートは触らない。** 現セッションの巻き戻し（dirty）と
                // 二重に走り、同じファイルへ2回書くことになる。
                .filterNot { it.value.trace.vaultRelativePath == excludePath }
                .map { it.key to it.value }
        }
        targets.forEach { (key, pending) ->
            // **保存中は pendingMutex を離す。** 握ったまま writeMutex を取ると
            // ロック順が逆向きの経路（保存の中から退避を積む）と噛み合わない。
            val written = writeMutex.withLock {
                val existing = (
                    persistence.load(pending.trace.vaultRelativePath, pending.vaultKey)
                        as? ReadingTraceReadResult.Valid
                    )?.trace
                val next = when {
                    existing == null -> pending.trace
                    // 内容が既に反映されていれば書き直す必要はない。
                    // **文字列ではなく Reflection 全体で見る** — 返事が同じでも
                    // 元の問い・日時・映し返しが違えば別物である。
                    existing.reflection == pending.trace.reflection -> return@withLock true
                    // **古い退避で新しい返事を潰さない。** 退避中に直接保存が成功して
                    // いれば、ファイル側のほうが新しい。日時で見て退いた場合も
                    // 「用済み」として true を返し、退避を捨てる。
                    isStaleAgainst(existing, pending.trace) -> return@withLock true
                    else -> existing.copy(reflection = pending.trace.reflection)
                }
                persistence.save(next, pending.vaultKey) is ReadingTraceSaveResult.Success
            }
            if (written) {
                pendingMutex.withLock {
                    // スナップショットを取った後に同じキーへ新しい退避が入っていたら
                    // 消さない（消すと、書けていない新しい返事まで捨てる）。
                    if (pendingWrites[key] === pending) pendingWrites.remove(key)
                }
            }
        }
    }

    /**
     * 退避してある内容が、ファイル側より古いか。
     *
     * 返事の日時で比べる。退避を積んでから直接保存が成功していると
     * ファイル側が新しく、そのまま書き戻すと**新しい返事を古い返事で上書きする**。
     */
    private fun isStaleAgainst(existing: ReadingTrace, pending: ReadingTrace): Boolean {
        val existingAt = existing.reflection?.repliedAtEpochMillis ?: return false
        val pendingAt = pending.reflection?.repliedAtEpochMillis ?: return true
        return existingAt > pendingAt
    }

    /**
     * 映し返しを添える。**無くても成立する**ので、書けなければ黙って諦める
     * （返事と違い、これはAIの生成物なので作り直せる）。
     */
    suspend fun saveMirrored(vaultRelativePath: String, mirrored: String) {
        val vaultKey = currentVaultKey() ?: return
        withContext(ioDispatcher) {
            writeMutex.withLock {
                val existing =
                    (persistence.load(vaultRelativePath, vaultKey) as? ReadingTraceReadResult.Valid)
                        ?.trace
                if (existing == null) {
                    // 痕跡がまだ無い（初読で返事まで書いた場合）。捨てずに預ける —
                    // 画面には映し返しが出ているのに保存だけ落ちると、
                    // 次に開いたとき返事だけが残って応答が消えている状態になる。
                    session?.pendingRemark?.takeIf { it.hasReply }?.let { held ->
                        session?.pendingRemark = held.withMirrored(mirrored)
                        session?.dirty = true
                    }
                    return@withLock
                }
                val reflection = existing.reflection?.takeIf { it.hasReply } ?: return@withLock
                persistence.save(existing.copy(reflection = reflection.withMirrored(mirrored)), vaultKey)
            }
        }
    }

    /**
     * 書けなかった返事をセッションへ預ける。預ける先が無ければ [ReplySaveOutcome.Lost]。
     *
     * 預けられるのは「この閲覧で作ったひとこと」がある場合だけ。
     * ひとことが無ければ組にできないので、返事だけを持ち回っても保存できない。
     */
    private fun holdOrLose(reply: String, atEpochMillis: Long): ReplySaveOutcome {
        val active = session ?: return ReplySaveOutcome.Lost
        val base = active.pendingRemark ?: return ReplySaveOutcome.Lost
        active.pendingRemark = base.withReply(reply, atEpochMillis)
        active.dirty = true
        // ここでは完成した痕跡を作れない（訪問がまだ無い）ので、退避には積まない。
        // 離脱時に痕跡ごと組み立てて保存を試み、そこで失敗したら丸ごと退避される。
        return ReplySaveOutcome.Held
    }

    /**
     * 現在のセッションの訪問を書き出す。既にこの閲覧で書いた訪問があれば、
     * 増やさずにその1件を差し替える（1回の閲覧＝1訪問を保つ）。
     *
     * 預かっているひとこと（[Session.pendingRemark]）があれば、**同じ
     * read-modify-write の中で**一緒に載せる。別のコルーチンで保存すると、
     * 訪問より先に走った側が「痕跡が無い」で諦めるか、後から走った側が
     * 古い読み取りで上書きするかのどちらかになる。
     */
    private fun recordVisit() {
        val active = session ?: return
        // 前回の書き込みから何も変わっていなければ、SAF書込を出さない。
        if (!active.dirty) return
        // 相対パスが最後まで分からなかったノート（_AI補記 の一覧から開いた等）は記録しない。
        val path = active.vaultRelativePath ?: return
        // **返事を預かっているときは読書量の門番を通す。**
        // 10秒・1ブロックは「一瞬引いてすぐ送った表示を訪問に数えない」ための条件だが、
        // ユーザーが返事を書いたなら、それはスクロールより強い関与である。
        // ここを通さないと、条件未達で離れた瞬間に**預かった返事が消える**
        // （画面には「保存中」と出たまま）。
        val holdsReply = active.pendingRemark?.hasReply == true
        if (!holdsReply) {
            if (active.elapsedMillis(clock()) < MIN_READING_MILLIS) return
            // 本文がまだ描画されていない（進捗報告が来ていない）場合は読んだと見なさない。
            if (active.totalBlocks <= 0) return
        }

        val visit = ReadingVisit(
            atEpochMillis = clock(),
            deepestSectionTitle = active.deepestSectionTitle,
            // totalBlocks が 0 のまま（返事だけ書いて離れた）なら到達率は 0。
            // progressPercent は 0 除算を自分で防ぐので、そのまま渡してよい。
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
        // 訪問と同じく、起動前に消費済みにして二重書き込みを防ぐ。
        // 失敗時は下の分岐で戻し、次の契機で書き直させる。
        val pendingRemark = active.pendingRemark
        active.pendingRemark = null
        val title = active.noteTitle
        val documentId = active.documentId
        val vaultKey = active.vaultKey
        val owner = active

        persistScope.launch {
            // 前回書けなかったぶんがあれば、まずそれを片付ける。
            // これから書くノートは除く（下の保存が同じファイルを扱う）。
            withContext(ioDispatcher) { flushPendingWrites(excludePath = path) }
            // 保存しようとした痕跡。失敗したときに丸ごと退避するために掴んでおく。
            var attempted: ReadingTrace? = null
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
                    // ひとことを預かっていなければ、読み込んだ値をそのまま残す。
                    // ここで無条件に copy(reflection = pendingRemark) にすると、
                    // 過去に保存した組を訪問のたびに消してしまう。
                    val withVisit = base.withVisit(visit)
                    val next = if (pendingRemark != null) {
                        withVisit.copy(reflection = pendingRemark)
                    } else {
                        withVisit
                    }
                    attempted = next
                    persistence.save(next, vaultKey)
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
                // ひとことも戻す。戻さないと、訪問だけ次の契機で書き直されて
                // ひとことは恒久的に失われる（生成し直す導線はユーザーの再操作しかない）。
                // 待っている間に新しいひとことが預けられていたら、そちらを優先する。
                if (pendingRemark != null && owner.pendingRemark == null) {
                    owner.pendingRemark = pendingRemark
                }
            }
            // **書けなかった痕跡を丸ごと退避する。** ここが「痕跡の新規作成が
            // 失敗した回」を救う唯一の場所 — 返事だけを持っていた頃は、
            // ファイルが無いと載せる先が無く復旧できなかった。
            attempted?.let { trace ->
                if (result is ReadingTraceSaveResult.Failure) {
                    rememberPendingWrite(vaultKey, trace)
                } else {
                    // 書けたので、同じノートの退避は用済み。
                    forgetPendingWrite(vaultKey, trace.vaultRelativePath)
                }
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
        // 旧Vaultの内容を新Vaultへ書かない。現在のVault以外の退避は捨てる。
        val current = currentVaultKey()
        // discard は Main から同期に呼ばれる契約なので、ここだけはロックを取らない。
        // 取り違えを避けるため、参照ではなくキーで消す。
        pendingWrites.keys.removeAll(
            pendingWrites.filterValues { it.vaultKey != current }.keys.toSet()
        )
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
            // 追加のI/Oは無い。この経路は既に痕跡を読んでいる。
            hasReflectionReply = trace.reflection?.hasReply == true,
            visitCount = trace.totalVisitCount,
            lastVisitAtMillis = last.atEpochMillis,
            lastSectionTitle = last.deepestSectionTitle,
            lastProgressPercent = last.progressPercent,
            aiSummary = aiSummary,
            isSummaryLoading = isSummaryLoading
        )
    }

    private suspend fun generateSummary(trace: ReadingTrace): String? = try {
        when (aiClient.checkAvailability()) {
            // 未ダウンロードでも自動DLしない（読むたびモデルDLを始めない）。黙って生のまま。
            // **非対応も取得失敗も同じ枝でよい**（意図的）— 読書痕跡はユーザーが意識しない
            // 機能なので、理由を出し分けても見せる先が無い。
            AiAvailability.NeedsDownload,
            AiAvailability.Downloading,
            AiAvailability.Unsupported,
            is AiAvailability.CheckFailed -> null
            AiAvailability.Ready -> {
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
        /** 退避の上限。失敗が続いても無制限に溜めない。 */
        const val MAX_PENDING_WRITES = 8

        const val MIN_READING_MILLIS = 10_000L
    }
}

/**
 * 返事の保存結果。**Boolean へ畳まない** — 呼び出し側の次の行動が3通りに分かれる。
 *
 * - [Saved]   … サイドカーへ書けた。画面は「保存済み」でよい
 * - [Held]    … まだ書けていないが預かった。離脱時に書かれるので、失敗として見せない
 * - [Lost]    … どこにも残っていない。**画面は未保存として見せ、書き直せる状態を保つ**
 */
internal enum class ReplySaveOutcome { Saved, Held, Lost }
