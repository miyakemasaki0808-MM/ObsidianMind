package com.example.newproject.controller

import com.example.newproject.data.ReadingTracePersistence
import com.example.newproject.data.ReadingTraceReadResult
import com.example.newproject.data.ReadingTraceSaveResult
import com.example.newproject.model.ReadingTrace
import com.example.newproject.model.ReadingVisit
import com.example.newproject.model.MarginMemo
import com.example.newproject.model.ReadingTraceLimits
import com.example.newproject.model.mergeMarginMemos
import com.example.newproject.model.withVisit
import com.example.newproject.model.withoutLastVisit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 読んだ位置を追跡してサイドカーへ記録し、ひとことと返事を同じサイドカーへ保存する。
 * 再会したときのカードは [ReunionCardController] が出す。
 *
 * ユーザーはこの機能を操作しない（残す導線もボタンも無い）。普通に読むだけで訪問が
 * 溜まる。中身は全部ユーザー自身の読み方なので「前回の自分」であり続ける。
 *
 * 進捗報告はスクロールごとに来るのでメモリ上の最大値更新だけに留め、
 * I/Oは離脱時（[flush]）と背面化時（[pause]）に絞る。背面化で書いた訪問は
 * 復帰後の離脱で差し替えるので、1回の閲覧＝1訪問が保たれる。
 */
internal class ReadingTraceController(
    /**
     * 訪問の書き出し専用スコープ。**アプリ寿命であること**が前提。
     *
     * `viewModelScope` に載せてはいけない。タスクスワイプや Activity finish では
     * `onStop()` → [pause] の直後に `onCleared()` が走るため、IOへディスパッチされる
     * 前のコルーチンがキャンセルされ、確定させたはずの訪問が失われる。
     *
     * 土台は Main.immediate であることも前提。[Session] の各フィールドはメインスレッド
     * からのみ触る規律で書かれており、保存失敗時の巻き戻しもそこへ戻ってくる。
     */
    private val persistScope: CoroutineScope,
    private val persistence: ReadingTracePersistence,
    /**
     * 現在のVaultの識別子。ノートを開いた時点の値をセッションへ写し取り、保存要求に
     * 添えて運ぶ。保存は非同期に走るため、書込時点の現在Vaultから保存先を解決すると
     * 旧ノートの痕跡が切替後の新Vaultへ書き込まれ得る。
     */
    private val currentVaultKey: () -> String?,
    private val clock: () -> Long = System::currentTimeMillis,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /**
     * サイドカーの read-modify-write を直列化する錠。訪問の追記と要約・印の書き戻しが
     * 交差すると、読み取りが古いまま上書きして訪問を取りこぼしうる。
     * （AI生成の直列化は AiClient 側の責務で、こちらとは別の関心事）
     *
     * **外から受け取る。** 同じサイドカーを read-modify-write する経路は
     * このクラスだけではない — [ReunionCardController] の要約・印と、
     * [ReadingTraceBackupController] の読み戻しが同じ形で書く。
     * 錠をクラスごとに持つと、**錠があるのに守られない**という最も気づきにくい形になる。
     * 既定値は単独で使うテスト用。
     */
    private val writeMutex: Mutex = Mutex()
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
        /**
         * 読書区間の開始時刻。**停止中に開いたセッションでは null。**
         *
         * 背面や冊子のまま読込が終わってセッションができることがあり
         * （遅いSAFで「これを読む」→ホームへ出る、など）、そこで時刻を入れると
         * **本文が一度も前景に出ていないのに読書時間が積まれる**。
         * 停止理由が全部消えた時点（[resume]）を最初の区間の開始にする。
         */
        startedAtMillis: Long?
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

        /** 現在の読書区間の開始時刻。背面化中・停止中は null。 */
        var resumedAtMillis: Long? = startedAtMillis

        /**
         * この閲覧で書き込み済みの訪問。背面化のたびに訪問を増やさず、同じ1件を
         * 更新し続けるための目印（→ [recordVisit]）。
         */
        var recordedVisit: ReadingVisit? = null

        /** 前回の書き込み以降に、書き直す価値のある変化があったか。 */
        var dirty = false

        /**
         * **まだサイドカーへ載せていない余白メモ。**
         *
         * 置いた直後に単独で保存できないことがある。訪問は離脱・背面化でしか書かれず、
         * `validateReadingTrace` は訪問が1件以上あることを要求するので、
         * **初読の最中に置いた1件目には載せる先のファイルがまだ無い。**
         *
         * **保存できる条件を「生成物があるか」に結び付けない**のがこの機能の契約で、
         * ここは純粋に「置き場所がまだ無い」だけを表す
         * （→ features/reflect_margin_memo.md 判断3）。
         */
        var pendingMemos: List<MarginMemo> = emptyList()

        /** 背面にいた時間を除いた読書時間。10秒判定はこれで行う。 */
        fun elapsedMillis(now: Long): Long =
            activeMillis + (resumedAtMillis?.let { now - it } ?: 0L)
    }

    private var session: Session? = null

    /**
     * いま計測を止めている理由。**空になったときだけ計測を再開する。**
     * セッションをまたいで残す — 冊子を見たまま次のノートへ渡ることはできないが、
     * 背面のままノートが切り替わる経路（通知からの復帰など）はあり得る。
     */
    private val pauseReasons = mutableSetOf<ReadingPauseReason>()

    /**
     * **書けなかった痕跡。セッションが終わった後も残す。**
     *
     * [flush] は保存コルーチンを起動した直後に `session = null` にするので、
     * 書き込みが後から失敗しても**戻す先のセッションはもう現役でない**。
     *
     * **持つのは返事ではなく、保存しようとした [ReadingTrace] そのもの。** 返事だけでは
     * 既存の痕跡へ載せ直すことしかできず、初読で痕跡の新規作成が失敗した回を復旧できない。
     *
     * **ノート単位で持つ**（キーは Vault＋相対パス）。単一スロットだと、Aが退避中にBで返事を
     * 書いた瞬間にAが消える。プロセスが死ねば失われるが、それは全ての未保存データと同じ条件である。
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
     * 書けなかった痕跡を覚える。
     *
     * **メモを持つ痕跡だけを積む。** 訪問だけの失敗はセッション側の巻き戻し
     * （`dirty` / `recordedVisit`）が同じ閲覧のうちに書き直し、失ってももう一度読めば付き直る。
     * **メモは作り直せない。** 訪問だけの退避で上限を埋めて、メモ付きの退避を押し出さないためでもある。
     *
     * **同じノートの既存の退避を置換せず、メモを合流させる。** 置換すると、
     * 「Aの保存に失敗 → 退避 → Bを置いて保存に失敗」でAが消える
     * （返事は1ノート1組の上書きだったので置換でよかったが、メモは独立に並ぶ
     * → features/reflect_margin_memo.md §7）。
     */
    private suspend fun rememberPendingWrite(vaultKey: String, trace: ReadingTrace) {
        if (trace.memos.isEmpty()) return
        pendingMutex.withLock {
            val key = pendingKey(vaultKey, trace.vaultRelativePath)
            val existing = pendingWrites[key]
            if (existing == null && pendingWrites.size >= MAX_PENDING_WRITES) {
                pendingWrites.remove(pendingWrites.keys.first())
            }
            val merged = if (existing == null) {
                trace
            } else {
                trace.copy(memos = mergeMarginMemos(existing.trace.memos, trace.memos))
            }
            pendingWrites[key] = PendingWrite(vaultKey, merged)
        }
    }

    /**
     * 退避しているメモ。**書き込みの直前に合流させるために引く。**
     *
     * 真実は永続ファイル・セッションの預かり・退避の3箇所に分かれて存在しうるので、
     * **どれか1つを真実と見なさない**（→ features/reflect_margin_memo.md §7 の契約1・2）。
     */
    private suspend fun pendingWriteMemos(vaultKey: String, path: String): List<MarginMemo> =
        pendingMutex.withLock { pendingWrites[pendingKey(vaultKey, path)]?.trace?.memos }.orEmpty()

    /**
     * 退避を捨てる。**中身がファイルに在ることを確かめた呼び出し側だけが呼ぶ**
     * （→ 契約3）。ノート単位で無条件に捨てると、書けていないメモを道連れにする。
     */
    private suspend fun forgetPendingWrite(vaultKey: String, path: String) {
        pendingMutex.withLock { pendingWrites.remove(pendingKey(vaultKey, path)) }
    }

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
                // **停止中に始まったセッションは計測しない。** 理由が残っている間は
                // まだ本文が前景に出ていない。
                startedAtMillis = clock().takeIf { pauseReasons.isEmpty() }
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
     * 読書時間の計測を止め、条件を満たしていれば訪問を記録する。
     *
     * ここで記録するのは、背面のままプロセスが終了しても読書が失われないようにするため。
     * ただしセッションは**残す**ので、[resume] 後に読み進めれば同じ訪問が更新される
     * （ホームボタンを押すたび「これまで◯回開いています」が増えるのを防ぐ）。
     *
     * **理由ごとに数える。** 停止の要求は独立に重なる — 冊子を開いたまま背面へ回れば
     * 「冊子を見ている」と「アプリが背面」の2つが同時に成り立つ。
     * 真偽1つで持つと、**片方が解けた時点でもう片方の停止理由が消える**
     * （背面から戻っただけで、冊子を見ている間の計測が再開する）。
     */
    fun pause(reason: ReadingPauseReason) {
        pauseReasons += reason
        val active = session ?: return
        val resumedAt = active.resumedAtMillis
        if (resumedAt != null) {
            active.activeMillis += clock() - resumedAt
            active.resumedAtMillis = null
        }
        recordVisit()
    }

    /**
     * 読書時間の計測を再開する。**停止理由が1つも残っていないときだけ動く。**
     *
     * 背面にいた時間は積算しないので、「5秒読んで放置し、戻ってすぐ離れた」が
     * 10秒の訪問条件を満たしてしまうことはない。冊子も同じ扱いで、
     * 冊子を眺めていた時間は読書時間へ入らない。
     */
    fun resume(reason: ReadingPauseReason) {
        pauseReasons -= reason
        if (pauseReasons.isNotEmpty()) return
        val active = session ?: return
        if (active.resumedAtMillis != null) return
        active.resumedAtMillis = clock()
        // 復帰後に離脱すれば最終閲覧日時は更新すべきなので、書き直す対象とする。
        active.dirty = true
    }

    /**
     * 読書中のノートの相対パス。**余白メモの保存先を引くのに使う。**
     *
     * UI へ配らずここから引くのは、相対パスの出所を1つに保つため。
     * 走査キャッシュが冷えていると表示後に [bindPath] で埋まるので、
     * `NoteState` に持たせると「まだ null の瞬間」を画面側が扱うことになる。
     */
    fun currentPath(): String? = session?.vaultRelativePath

    /**
     * このノートの余白メモを読む。**シートを開いたときにだけ呼ぶ。**
     *
     * ノート表示の経路には置かない。開くたびにサイドカーを1件読むことになり、
     * 遠いプロバイダでは体感に乗る（→ 痕跡の索引コストと同じ問題圏）。
     *
     * **3箇所を合流して返す**（→ features/reflect_margin_memo.md §7 の契約1・2）。
     * ファイルだけを見せると、**まだ書けていないメモが画面から消える。**
     */
    suspend fun loadMemos(vaultRelativePath: String): List<MarginMemo> {
        if (vaultRelativePath.isBlank()) return emptyList()
        val vaultKey = currentVaultKey() ?: return emptyList()
        val held = heldMemosFor(vaultRelativePath)
        return withContext(ioDispatcher) {
            val stored = writeMutex.withLock {
                (persistence.load(vaultRelativePath, vaultKey) as? ReadingTraceReadResult.Valid)
                    ?.trace
                    ?.memos
                    .orEmpty()
            }
            val pending = pendingWriteMemos(vaultKey, vaultRelativePath)
            mergeMarginMemos(mergeMarginMemos(stored, pending), held)
        }
    }

    /** 現在のセッションが預かっているメモ。**別ノートのものは渡さない。** */
    private fun heldMemosFor(vaultRelativePath: String): List<MarginMemo> {
        val active = session ?: return emptyList()
        if (active.vaultRelativePath != vaultRelativePath) return emptyList()
        return active.pendingMemos
    }

    /**
     * 余白メモを**即時に**書き足す。
     *
     * **ユーザーが書いた言葉なので、アプリが落ちても失ってはいけない。**
     * 生成物は作り直せるが、書いた言葉は作り直せない。
     *
     * **戻り値を Boolean にしない。** 「書けた」「まだ書けていないが預かった」
     * 「置けない」「どこにも残っていない」で**呼び出し側の次の行動が違う**ため
     * （→ lessons L28）。とくに [MemoSaveOutcome.Full] を
     * [MemoSaveOutcome.Lost] へ畳まない — 再試行で直るものと、
     * 1件消さなければ直らないものは別である。
     *
     * **保存の可否を「生成物があるか」で決めない**（→ features/reflect_margin_memo.md 判断3）。
     * ここには組の相手を確かめる分岐が無い。
     */
    suspend fun appendMemo(
        vaultRelativePath: String,
        memo: MarginMemo
    ): MemoSaveOutcome {
        // **要求の所有者を、非同期へ入る前に固定する**（→ docs/dev/lessons.md L26）。
        // この後の `persistence.load` は同期I/Oで、戻る頃には別のノートを開いている場合がある。
        val ownerSessionId = session?.id
        val vaultKey = currentVaultKey()
            // Vault未選択では保存先が無く、セッションも無いので預ける先も無い。
            ?: return holdOrLose(ownerSessionId, vaultRelativePath, memo)
        return withContext(ioDispatcher) {
            // **これから書くノートは触らない。** 下の保存が同じファイルを扱う。
            flushPendingWrites(excludePath = vaultRelativePath)
            writeMutex.withLock {
                val existing = try {
                    (persistence.load(vaultRelativePath, vaultKey) as? ReadingTraceReadResult.Valid)
                        ?.trace
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
                // **3箇所を合流してから足す**（→ §7 の契約2）。
                // ファイルだけを基準にすると、退避中のメモが次の保存で消える。
                val base = mergeMarginMemos(
                    mergeMarginMemos(
                        existing?.memos.orEmpty(),
                        pendingWriteMemos(vaultKey, vaultRelativePath)
                    ),
                    heldMemosFor(vaultRelativePath)
                )
                val next = mergeMarginMemos(base, listOf(memo))
                // **古いものから捨てない。** 置けないと伝えて、入力は呼び出し側が保つ。
                if (next.size > ReadingTraceLimits.MAX_MEMOS) return@withLock MemoSaveOutcome.Full
                if (existing == null) {
                    // 痕跡がまだ無い（または読めない）＝この閲覧で訪問が確定していない。
                    // セッションへ預け直して、離脱時の書き込みに載せる。
                    return@withLock holdOrLose(ownerSessionId, vaultRelativePath, memo)
                }
                val attempted = existing.copy(memos = next)
                val saved = try {
                    persistence.save(attempted, vaultKey)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
                if (saved is ReadingTraceSaveResult.Success) {
                    // **ファイルに在ることを確かめたので、預かりと退避から外してよい**（→ 契約3）。
                    // `next` は両方を合流した結果なので、取りこぼしは無い。
                    clearHeldMemos(ownerSessionId, vaultRelativePath)
                    forgetPendingWrite(vaultKey, vaultRelativePath)
                    MemoSaveOutcome.Saved
                } else {
                    // **書けなかったぶんを必ず退避する。** ここを握り潰すと、
                    // 画面に「保存済み」と出たままメモが消える。
                    rememberPendingWrite(vaultKey, attempted)
                    holdOrLose(ownerSessionId, vaultRelativePath, memo)
                    MemoSaveOutcome.Held
                }
            }
        }
    }

    /**
     * 余白メモを1件消す。**3箇所すべてから、書き込みの錠の内側で外す**（→ §7 の契約5）。
     *
     * 1箇所でも残すと、次の書き込み契機で合流して**消したはずのメモが復活する。**
     */
    suspend fun deleteMemo(
        vaultRelativePath: String,
        memo: MarginMemo
    ): MemoDeleteOutcome {
        val vaultKey = currentVaultKey() ?: return MemoDeleteOutcome.Failed
        val ownerSessionId = session?.id
        return withContext(ioDispatcher) {
            writeMutex.withLock {
                // 1. セッションの預かり
                removeHeldMemo(ownerSessionId, vaultRelativePath, memo)
                // 2. 退避
                pendingMutex.withLock {
                    val key = pendingKey(vaultKey, vaultRelativePath)
                    pendingWrites[key]?.let { pending ->
                        val remaining = pending.trace.memos.filterNot { it == memo }
                        pendingWrites[key] = PendingWrite(
                            pending.vaultKey,
                            pending.trace.copy(memos = remaining)
                        )
                    }
                }
                // 3. 永続ファイル
                val existing = try {
                    (persistence.load(vaultRelativePath, vaultKey) as? ReadingTraceReadResult.Valid)
                        ?.trace
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    return@withLock MemoDeleteOutcome.Failed
                } ?: return@withLock MemoDeleteOutcome.Deleted
                if (existing.memos.none { it == memo }) return@withLock MemoDeleteOutcome.Deleted
                val saved = try {
                    persistence.save(existing.copy(memos = existing.memos.filterNot { it == memo }), vaultKey)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
                if (saved is ReadingTraceSaveResult.Success) {
                    MemoDeleteOutcome.Deleted
                } else {
                    MemoDeleteOutcome.Failed
                }
            }
        }
    }

    /**
     * 退避してある痕跡を書き直す。**書き込み契機のたびに先頭で試す。**
     *
     * ファイルが無ければ退避した痕跡をそのまま作る（**新規作成の失敗も復旧できる**）。
     * 既にあれば、そこへメモを**合流**させる — 待っている間に訪問が増えている可能性が
     * あるので丸ごと上書きはせず、メモは配列なのでどちらも残せる。
     *
     * **日時で新旧を決めない。** 1ノート1組だった返事は「どちらを残すか」を決める必要が
     * あったが、メモは両方を残せる（→ features/reflect_margin_memo.md 判断5）。
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
                .map { it.key }
        }
        targets.forEach { key ->
            // **保存中は pendingMutex を離す。** 握ったまま writeMutex を取ると
            // ロック順が逆向きの経路（保存の中から退避を積む）と噛み合わない。
            val written = writeMutex.withLock {
                // **錠の内側で退避を読み直す**（→ §7 の契約4）。
                // 先に取ったスナップショットで書くと、待っている間に消されたメモを
                // **書き戻して復活させる。**
                val pending = pendingMutex.withLock { pendingWrites[key] }
                    ?: return@withLock false
                if (pending.trace.memos.isEmpty()) return@withLock true
                val existing = (
                    persistence.load(pending.trace.vaultRelativePath, pending.vaultKey)
                        as? ReadingTraceReadResult.Valid
                    )?.trace
                val next = if (existing == null) {
                    pending.trace
                } else {
                    existing.copy(memos = mergeMarginMemos(existing.memos, pending.trace.memos))
                }
                // 合流が上限を超えるなら書かない。**退避は残す** — 捨てるとメモが消える。
                if (next.memos.size > ReadingTraceLimits.MAX_MEMOS) return@withLock false
                // 内容が既に反映されていれば書き直す必要はない。
                if (existing != null && existing.memos == next.memos) return@withLock true
                persistence.save(next, pending.vaultKey) is ReadingTraceSaveResult.Success
            }
            if (written) {
                pendingMutex.withLock {
                    // 書いた後に同じキーへ新しいメモが積まれていたら消さない
                    // （消すと、書けていない新しいメモまで捨てる）。
                    val latest = pendingWrites[key]
                    if (latest != null && latest.trace.memos.isEmpty()) pendingWrites.remove(key)
                }
            }
        }
    }

    /**
     * 書けなかったメモを**要求を出したセッションへ**預ける。預ける先が無ければ
     * [MemoSaveOutcome.Lost]。
     *
     * **[ownerSessionId] が現在のセッションと違えば預けない。**
     * メモを置いたノートを既に離れているので、預けても行き先は別のノートの痕跡になる。
     * **[MemoSaveOutcome.Lost] を返すのは、そのほうが正直だから** —
     * 別のノートへ混ぜて「保存できた」ことにするより、保存できなかったと言うほうがよい。
     *
     * **`dirty` を立てるのが要点。** 立てないと、直前に訪問を書き終えていた場合に
     * [recordVisit] が「変化なし」で早期returnし、メモが書かれないままセッションが終わる。
     */
    private fun holdOrLose(
        ownerSessionId: Long?,
        vaultRelativePath: String,
        memo: MarginMemo
    ): MemoSaveOutcome {
        val active = session ?: return MemoSaveOutcome.Lost
        if (active.id != ownerSessionId) return MemoSaveOutcome.Lost
        if (active.vaultRelativePath != vaultRelativePath) return MemoSaveOutcome.Lost
        active.pendingMemos = mergeMarginMemos(active.pendingMemos, listOf(memo))
        active.dirty = true
        // ここでは完成した痕跡を作れない（訪問がまだ無い）ので、退避には積まない。
        // 離脱時に痕跡ごと組み立てて保存を試み、そこで失敗したら丸ごと退避される。
        return MemoSaveOutcome.Held
    }

    /** ファイルに在ることを確かめたメモを預かりから外す（→ §7 の契約3）。 */
    private fun clearHeldMemos(ownerSessionId: Long?, vaultRelativePath: String) {
        val active = session ?: return
        if (active.id != ownerSessionId) return
        if (active.vaultRelativePath != vaultRelativePath) return
        active.pendingMemos = emptyList()
    }

    /** 消されたメモを預かりから外す（→ §7 の契約5）。 */
    private fun removeHeldMemo(
        ownerSessionId: Long?,
        vaultRelativePath: String,
        memo: MarginMemo
    ) {
        val active = session ?: return
        if (active.id != ownerSessionId) return
        if (active.vaultRelativePath != vaultRelativePath) return
        active.pendingMemos = active.pendingMemos.filterNot { it == memo }
    }

    /**
     * 現在のセッションの訪問を書き出す。既にこの閲覧で書いた訪問があれば、
     * 増やさずにその1件を差し替える（1回の閲覧＝1訪問を保つ）。
     *
     * 預かっているメモ（[Session.pendingMemos]）と**退避しているメモ**があれば、
     * **同じ read-modify-write の中で**一緒に載せる。別のコルーチンで保存すると、
     * 訪問より先に走った側が「痕跡が無い」で諦めるか、後から走った側が
     * 古い読み取りで上書きするかのどちらかになる。
     *
     * **退避も合流させるのが要点**（→ §7 の契約3）。
     * 退避を載せずに訪問だけ書いて成功扱いにすると、
     * **書けていないメモを持ったまま退避を捨てる**ことになる。
     */
    private fun recordVisit() {
        val active = session ?: return
        // 前回の書き込みから何も変わっていなければ、SAF書込を出さない。
        if (!active.dirty) return
        // 相対パスが最後まで分からなかったノート（_AI補記 の一覧から開いた等）は記録しない。
        val path = active.vaultRelativePath ?: return
        // **メモを預かっているときは読書量の門番を通す。**
        // 10秒・1ブロックは「一瞬引いてすぐ送った表示を訪問に数えない」ための条件だが、
        // ユーザーがメモを置いたなら、それはスクロールより強い関与である。
        // ここを通さないと、条件未達で離れた瞬間に**預かったメモが消える**
        // （画面には「保存中」と出たまま）。
        val holdsMemos = active.pendingMemos.isNotEmpty()
        if (!holdsMemos) {
            if (active.elapsedMillis(clock()) < MIN_READING_MILLIS) return
            // 本文がまだ描画されていない（進捗報告が来ていない）場合は読んだと見なさない。
            if (active.totalBlocks <= 0) return
        }

        val visit = ReadingVisit(
            atEpochMillis = clock(),
            deepestSectionTitle = active.deepestSectionTitle,
            // totalBlocks が 0 のまま（メモだけ置いて離れた）なら到達率は 0。
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
        val pendingMemos = active.pendingMemos
        active.pendingMemos = emptyList()
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
            // 上限に入りきらなかったメモ。**捨てずに預かりへ戻す。**
            var overflow: List<MarginMemo> = emptyList()
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
                    // **3箇所を合流させる**（→ §7 の契約2・3）。退避を載せずに書いて
                    // 成功扱いにすると、書けていないメモを持ったまま退避を捨てることになる。
                    val withVisit = base.withVisit(visit)
                    val merged = mergeMarginMemos(
                        mergeMarginMemos(withVisit.memos, pendingWriteMemos(vaultKey, path)),
                        pendingMemos
                    )
                    // 上限を超える分は**捨てずに預かりへ戻す。** 古い側から書けるだけ書く。
                    // ここへ来るのは別端末が同じノートへ書いた場合だけで、
                    // 自分の追記は appendMemo が先に Full で止める。
                    overflow = merged.drop(ReadingTraceLimits.MAX_MEMOS)
                    val next = withVisit.copy(memos = merged.take(ReadingTraceLimits.MAX_MEMOS))
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
                // メモも戻す。戻さないと、訪問だけ次の契機で書き直されて
                // **メモは恒久的に失われる**（ユーザーが書いた言葉は作り直せない）。
                // 待っている間に新しいメモが預けられていたら、合流させる。
                owner.pendingMemos = mergeMarginMemos(owner.pendingMemos, pendingMemos)
            }
            // 入りきらなかったぶんを預かりへ戻す。**成否に関わらず捨てない。**
            if (overflow.isNotEmpty()) {
                owner.pendingMemos = mergeMarginMemos(owner.pendingMemos, overflow)
                owner.dirty = true
            }
            // **書けなかった痕跡を丸ごと退避する。** 痕跡の新規作成が失敗した回を救えるのはここだけ
            // （ファイルが無いと、メモだけでは載せる先が無い）。
            attempted?.let { trace ->
                if (result is ReadingTraceSaveResult.Failure) {
                    rememberPendingWrite(vaultKey, trace)
                } else if (overflow.isEmpty()) {
                    // 書けた痕跡には退避のメモも合流済みなので、退避は用済み（→ 契約3）。
                    // **入りきらなかったぶんがあるときは捨てない。**
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
 * 余白メモの保存結果。**Boolean へ畳まない** — 呼び出し側の次の行動が4通りに分かれる。
 *
 * - [Saved] … サイドカーへ書けた。画面は「保存済み」でよい
 * - [Held]  … まだ書けていないが預かった。離脱時に書かれるので、失敗として見せない
 * - [Full]  … 上限に達して置けない。**入力欄の文字は消さない** — 1件消せばそのまま置ける
 * - [Lost]  … どこにも残っていない。**画面は未保存として見せ、書き直せる状態を保つ**
 *
 * **[Full] を [Lost] へ畳まない。** 再試行で直るものと、
 * 1件消さなければ直らないものは、ユーザーの次の行動が違う。
 */
internal enum class MemoSaveOutcome { Saved, Held, Full, Lost }

/**
 * 余白メモの削除結果。
 *
 * **[Deleted] は「3箇所のどこにも残っていない」ことを意味する**（→ §7 の契約5）。
 * 1箇所でも残っていれば、次の書き込み契機で合流して復活してしまう。
 */
internal enum class MemoDeleteOutcome { Deleted, Failed }
