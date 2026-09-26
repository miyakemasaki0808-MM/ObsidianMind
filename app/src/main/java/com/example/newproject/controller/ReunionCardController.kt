package com.example.newproject.controller

import com.example.newproject.ai.AiAvailability
import com.example.newproject.ai.AiClient
import com.example.newproject.ai.PromptBuilder
import com.example.newproject.ai.ReunionCandidateLine
import com.example.newproject.data.ReadingTracePersistence
import com.example.newproject.data.ReadingTraceReadResult
import com.example.newproject.domain.ReunionSlot
import com.example.newproject.domain.SummaryCache
import com.example.newproject.domain.cleanReunionPassageOutput
import com.example.newproject.domain.decideReunionKind
import com.example.newproject.domain.forKind
import com.example.newproject.domain.markdown.NoteSectionModel
import com.example.newproject.domain.parseCandidateIds
import com.example.newproject.domain.reunionCandidateId
import com.example.newproject.domain.scanReunionCandidates
import com.example.newproject.model.NoteExcerptLimits
import com.example.newproject.model.ReadingTrace
import com.example.newproject.model.ReadingTraceLimits
import com.example.newproject.model.ReadingTraceStateWriter
import com.example.newproject.model.ReunionKind
import com.example.newproject.model.ReunionPassage
import com.example.newproject.model.isReunionNone
import com.example.newproject.model.state.ReadingTraceCard
import com.example.newproject.model.truncateToUtf8Bytes
import com.example.newproject.model.withMark
import com.example.newproject.model.withoutMark
import java.util.concurrent.ConcurrentHashMap
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
 * Rediscover で再会したときに「前回のあなた」カードを出し、「まだ考えたい」の印を保存する。
 *
 * 訪問の記録（[ReadingTraceController]）とは別に持つ。**UI 状態を書くのはこちらだけ**で、
 * 訪問と返事の側は痕跡サイドカーへ書くだけで画面の状態を持たない。
 * 両者が触る共有物は痕跡サイドカーと、その read-modify-write を直列化する錠である。
 *
 * **枠の役目は、前回の最後の訪問が途中までか最後までかで分かれる**（→ features/reunion_card.md 判断6）。
 * 途中までなら読み進めたところの前後の要約と続きから読む、最後までなら問い・古い前提か、ノートの要約。
 */
internal class ReunionCardController(
    private val scope: CoroutineScope,
    /**
     * 印の書き出し専用スコープ。**アプリ寿命であること**が前提。
     * 押した直後に画面が畳まれても、押した印を失わないため（訪問の書き出しと同じ理由）。
     */
    private val persistScope: CoroutineScope,
    private val aiClient: AiClient,
    /** 生成の直前に通す門番。ノートを離れたら `CancellationException` で抜ける。 */
    private val awaitDwell: suspend () -> Unit,
    private val state: ReadingTraceStateWriter,
    private val persistence: ReadingTracePersistence,
    /** 現在のVaultの識別子。照合と印の保存は、要求を出した時点の値へ向けてだけ行う。 */
    private val currentVaultKey: () -> String?,
    /**
     * いま表示しているノートの解析結果を待つ。**自前で解析し直さない**（最大1MBで数百ミリ秒かかる
     * → lessons L13）。到達率を測っているのと同じブロック列なので、番号がそのまま一致する。
     */
    private val awaitSectionModel: suspend () -> NoteSectionModel,
    /**
     * 前後の要約の保存。**鍵は完成したプロンプト**で、訪問数では失効させない
     * （→ features/reunion_card.md 判断6「途中までのとき — 前後の要約」）。
     */
    private val passageCache: SummaryCache,
    /**
     * 離れたノートの訪問の保存が終わるまで待つ。**照合は痕跡を読む前にこれを通す**
     * （保存前の痕跡を読むと、初めて読んだ直後の再会でカードが出ない）。
     */
    private val awaitVisitSaves: suspend () -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /** 候補の列挙と前後の切り出しは入力サイズに比例するので Main の外で回す（→ lessons L13）。 */
    private val scanDispatcher: CoroutineDispatcher = Dispatchers.Default,
    /**
     * サイドカーの read-modify-write を直列化する錠。**外から受け取る。**
     *
     * 同じサイドカーを訪問の追記（[ReadingTraceController]）と読み戻しの適用
     * （[ReadingTraceBackupController]）も書く。選別結果と印の書き戻しが別の錠で走ると、
     * 読み取りが古いまま上書きして訪問を取りこぼしうる。既定値は単独で使うテスト用。
     */
    private val writeMutex: Mutex = Mutex()
) {

    private var revealJob: Job? = null
    private var activeRequestId = 0L

    /**
     * いま出ているカードがどの痕跡のものか。**印はここへ書く。**
     *
     * 押した瞬間の「現在のノート」を読み直さない。カードは特定の痕跡に対応しているので、
     * 対象は**カードを出した時点で決まっている**（→ [lessons L26](../../../../../../../../docs/dev/lessons/L26.md)）。
     */
    private var revealedPath: String? = null

    /**
     * 印の要求世代。**痕跡ごとに数える。**
     *
     * `writeMutex` は同時書き込みを防ぐが、**要求の到着順までは保証しない。**
     * 同じカードで素早く2回押すと、IOへディスパッチされた順が入れ替わり、
     * **画面では外れているのにサイドカーには付いている**状態が作れる。
     * ユーザーの明示的な意図を逆に保存することになるので、古い要求はロックの中で捨てる。
     *
     * **全体で1つの世代にしない。** 「最新」の単位は、操作対象＝保存先と一致していなければならない。
     * 単一の世代だと、ノートAで印を押した直後にノートBで押しただけで、
     * **競合していないAの保存まで「古い要求」として捨てられる**（Aの押下が黙って消える）。
     *
     * Main で採番し IO で読むので並行なマップを使う。エントリは操作したノートぶんだけで、
     * **完了時に消さない** — 消すと、遅れて走る同じ要求が自分の世代を見失って捨てられる。
     */
    private val markRequestIds = ConcurrentHashMap<String, Long>()

    private fun markKey(vaultKey: String, path: String) = "$vaultKey\n$path"

    /** ノート切替時に、進行中の照合・生成を捨てる（後着で別ノートのカードを出さない）。 */
    fun cancelForNoteChange() {
        revealJob?.cancel()
        activeRequestId++
        // カードごと差し替わるので、印の宛先も捨てる（旧ノートへ書かない）。
        revealedPath = null
    }

    /**
     * 「前回のあなた」を照合してカードに載せる。**Rediscover 経路からのみ呼ぶ**
     * （検索・関連・直接オープンでは呼ばない＝カードが出ない）。
     *
     * 痕跡が無い／破損しているときは何もしない。カードを出さないだけで、
     * ユーザーのノートには一切触れない。
     *
     * **[content] は原文全体を渡す。** 候補の列挙を抜粋へ当てると、長文で切り落とされた
     * 区間の問いが永久に届かない（→ features/reunion_card.md「候補の列挙は原文全体へ当てる」）。
     */
    fun revealTrace(vaultRelativePath: String, content: String) {
        revealJob?.cancel()
        val requestId = ++activeRequestId
        if (vaultRelativePath.isBlank()) return
        // どのVaultへの照合かは、サスペンドする前のこの時点で決める。
        val vaultKey = currentVaultKey() ?: return
        revealedPath = vaultRelativePath
        revealJob = scope.launch {
            // **離れた訪問の保存を待ってから読む。** 切替の直後に同じノートを引くと、
            // 保存前の痕跡を読んでその再会ではカードが出ない。
            // 待っている間の切替は、この Job の取消で抜ける（世代照合は重ねない）。
            awaitVisitSaves()
            val trace = withContext(ioDispatcher) {
                (persistence.load(vaultRelativePath, vaultKey) as? ReadingTraceReadResult.Valid)?.trace
            } ?: return@launch
            if (!isCurrent(requestId)) return@launch

            if (trace.visits.last().progressPercent < 100) {
                revealMidway(trace, requestId)
            } else {
                revealFinished(trace, content, vaultKey, requestId)
            }
        }
    }

    /**
     * 途中まで読んだノート。**痕跡の `aiSummary` は読まない**（印を除く）。
     *
     * 旧仕様は途中・読了を分けずに問いを選別していたので、途中までのノートにも
     * 問い・空振り・俯瞰要約が「訪問数の合った試行済み」として残っている。それを使うと
     * 前後の要約を作らないまま枠が空になる（→ features/reunion_card.md 判断6「保存済みの種別を、今回の分岐で使えるか」）。
     */
    private suspend fun revealMidway(trace: ReadingTrace, requestId: Long) {
        // まず見出しの1文を出す。**印があれば枠は保存済みの再掲で確定し、生成しない。**
        setCard(cardOf(trace, isSlotLoading = !trace.hasMark))

        val model = awaitSectionModel()
        if (!isCurrent(requestId)) return
        val last = trace.visits.last()
        val located = withContext(scanDispatcher) {
            model.readFrontier(last.deepestSectionTitle, last.progressPercent)?.let { frontier ->
                Located(
                    resumeBlockIndex = model.resumeBlockFor(frontier.block),
                    passage = model.passageAround(frontier, NoteExcerptLimits.REUNION_PASSAGE)
                )
            }
        } ?: run {
            // 本文にブロックが無い。送る先も要約する前後も無い。
            if (isCurrent(requestId)) mergeIntoCard { copy(isSummaryLoading = false) }
            return
        }
        if (!isCurrent(requestId)) return
        mergeIntoCard { copy(resumeBlockIndex = located.resumeBlockIndex) }
        // 照合した時点で印があれば、枠は再掲で確定している。**待っている間に外されていても作らない**
        // — 外したあとは押した文がそのまま残る（印を外したときの見え方と同じ）。
        if (trace.hasMark) return

        val passage = passageSummary(trace.noteTitle, located.passage)
        if (!isCurrent(requestId)) return
        mergeIntoCard { withSlotResult(passage?.let { Item(it, ReunionKind.Passage) }) }
    }

    private class Located(val resumeBlockIndex: Int, val passage: ReunionPassage)

    /**
     * 前後の要約を引き、無ければ1回だけ作る。**痕跡へは書かない。**
     *
     * **保存済みは端末AIの状態に関わらず出す。** 生成は決定的なので、同じプロンプトの保存済みは
     * いま作っても同じ文になる（要約の保存と同じ理由 → features/note_summary.md 判断6）。
     * 失敗・AI非対応は null — 枠を出さず、見出しの1文と続きから読むだけが残る。
     */
    private suspend fun passageSummary(noteTitle: String, passage: ReunionPassage): String? = try {
        val prompt = PromptBuilder.buildReunionPassagePrompt(noteTitle, passage)
        passageCache.find(prompt) ?: when (aiClient.checkAvailability()) {
            // 未ダウンロードでも自動DLしない（読むたびモデルDLを始めない）。黙って生のまま。
            AiAvailability.NeedsDownload,
            AiAvailability.Downloading,
            AiAvailability.Unsupported,
            is AiAvailability.TemporarilyUnavailable -> null
            AiAvailability.Ready -> {
                // **見出しの1文は出し終えている。** 待たせるのは生成だけ（→ background_ai_ux.md §7）。
                awaitDwell()
                // 印の復唱は表示・保存の前に落とす（残すとカードにも印の保存にも記号が載る）。
                cleanReunionPassageOutput(aiClient.generate(prompt), passage)
                    .takeIf { it.isNotBlank() }
                    // 印の内容として保存できる長さへ先に切る（切らないと印が検証で弾かれる）。
                    ?.let { truncateToUtf8Bytes(it, ReadingTraceLimits.MAX_AI_SUMMARY_BYTES) }
                    ?.also { passageCache.save(prompt, it) }
            }
        }
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        // タイムアウト・生成失敗も黙って劣化させる。ユーザーが意識しない機能なので
        // エラー表示は出さず、見出しの1文だけが見えている状態に留める。
        null
    }

    /**
     * 最後まで読んだノート。問い・古い前提を選び、無ければ枠はノートの要約の先頭1文になる
     * （要約は `SummaryState` から画面が読む。ここでは何も生成しない）。
     */
    private suspend fun revealFinished(trace: ReadingTrace, content: String, vaultKey: String, requestId: Long) {
        if (trace.hasMark) {
            setCard(cardOf(trace))
            return
        }
        when (val stored = storedFinishedItem(trace)) {
            is Stored.Found -> {
                setCard(cardOf(trace, item = stored.item))
                return
            }
            Stored.Empty -> {
                // 空振りの記録。同じ訪問数で同じ候補を2度聞かない。
                setCard(cardOf(trace))
                return
            }
            null -> Unit
        }

        // **選別が決まるまで、ノートの要約を先に出さない**（出して差し替えると中身が入れ替わる）。
        setCard(cardOf(trace, isSlotLoading = true))
        // 列挙は入力サイズに比例するので Main の外で回す（→ lessons L13）。
        val candidates = withContext(scanDispatcher) { scanReunionCandidates(content) }
        if (!isCurrent(requestId)) return
        val kind = decideReunionKind(candidates)
        if (kind == ReunionKind.Overview) {
            // 候補が無い。**Nano を呼ばず、痕跡にも書かない。** 列挙は非AIなので次の再会でやり直せる。
            mergeIntoCard { withSlotResult(null) }
            return
        }

        val outcome = selectCandidate(trace, kind, candidates.forKind(kind))
        if (!isCurrent(requestId)) return
        // **空振りも失敗も、見出しの1文は残して読み込み表示だけ下げる。**
        val generated = outcome as? ReunionOutcome.Generated
        mergeIntoCard { withSlotResult(generated?.let { Item(it.summary, it.kind) }) }
        persistOutcome(trace, outcome, vaultKey)
    }

    private class Item(val text: String, val kind: ReunionKind)

    /** 読了のノートで、痕跡に残っている選別の結果をそのまま使えるか。null なら選び直す。 */
    private sealed interface Stored {
        class Found(val item: Item) : Stored

        /** 空振りの記録。枠はノートの要約になる。 */
        data object Empty : Stored
    }

    /**
     * 保存済みを使うかどうかは、**訪問数が合うかではなく、今回の分岐でその種別を使えるか**で決める
     * （→ features/reunion_card.md 判断6 の表）。文を持つ `Overview` は旧仕様の俯瞰要約なので使わない。
     */
    private fun storedFinishedItem(trace: ReadingTrace): Stored? {
        if (trace.aiSummaryVisitCount != trace.totalVisitCount) return null
        val text = trace.aiSummary
        val kind = trace.aiSummaryKind ?: return null
        return when (kind) {
            ReunionKind.Question, ReunionKind.Staleness -> text?.let { Stored.Found(Item(it, kind)) }
            ReunionKind.Overview -> if (text == null) Stored.Empty else null
            ReunionKind.Passage -> null
        }
    }

    /**
     * 「まだ考えたい」を切り替える。[slot] は**いま枠に出ているもの**で、表示と同じ純関数から求めたもの。
     *
     * **押した時点で枠に出ていた内容ごと控える。** 次の再会で作り直すと別の文が出て
     * 意図とずれるため（→ features/reunion_card.md §6）。ノートの要約はカードの状態に無いので、
     * 表示と同じ関数で求めた中身を受け取らないと、見えていない文に印が付く。
     * **もう一度押すと外れる。**「読んだ」で畳んでも外れない（閉じる操作と取り消しは別）。
     */
    fun toggleMark(slot: ReunionSlot) {
        val card = state.current ?: return
        val vaultRelativePath = revealedPath ?: return
        val vaultKey = currentVaultKey() ?: return
        val shown = slot as? ReunionSlot.Shown
        // 出ているものが無ければ印の付けようがない。
        if (!card.isMarked && shown == null) return

        // 画面は先に返す。**保存の往復を待たせない**（失敗しても次の再会で作り直せる）。
        val marking = !card.isMarked
        val markKey = markKey(vaultKey, vaultRelativePath)
        val requestId = markRequestIds.merge(markKey, 1L, Long::plus)!!
        state.update { current ->
            if (marking) {
                // 印の付いた枠は、押した時点の文をそのまま出し続ける。
                current?.copy(isMarked = true, aiSummary = shown?.text, aiSummaryKind = shown?.kind)
            } else {
                current?.copy(isMarked = false)
            }
        }
        persistScope.launch {
            withContext(ioDispatcher) {
                writeMutex.withLock {
                    // **ロックを取れた時点で最新かを見る。** 取る前に確かめても、
                    // 待っている間に次の要求が来れば同じ逆転が起きる。
                    // 見るのは**同じ痕跡への**最新要求だけ（別ノートの操作では失効しない）。
                    if (requestId != markRequestIds[markKey]) return@withLock
                    val latest = (persistence.load(vaultRelativePath, vaultKey) as? ReadingTraceReadResult.Valid)
                        ?.trace
                        ?: return@withLock
                    val updated = if (marking) {
                        latest.withMark(
                            summary = requireNotNull(shown).text,
                            kind = shown.kind,
                            atEpochMillis = clock()
                        )
                    } else {
                        latest.withoutMark()
                    }
                    persistence.save(updated, vaultKey)
                }
            }
        }
    }

    /** 「読んだ」で畳む。永続化しないので次回 Rediscover では再表示される。 */
    fun dismissCard() {
        state.update { it?.copy(isDismissed = true) }
    }

    /** 照合した痕跡から最初のカードを出す。**後から届く結果には使わない**（→ [mergeIntoCard]）。 */
    private fun setCard(card: ReadingTraceCard) {
        state.update { current ->
            // 畳んだ状態は、後から届いた結果で開き直さない。
            val dismissed = current?.isDismissed == true
            card.copy(isDismissed = dismissed)
        }
    }

    /**
     * 後から届いた結果を**いまのカードへ合流する。** カードを組み直さない。
     *
     * 組み直すと、待っている間にユーザーが「まだ考えたい」を押した結果や畳んだ状態が、
     * 照合した時点の痕跡で上書きされる。同じノートのままなので、要求の世代では見分けられない。
     */
    private fun mergeIntoCard(transform: ReadingTraceCard.() -> ReadingTraceCard) {
        state.update { it?.transform() }
    }

    /** 枠の1件が届いた。**印が付いていれば枠は印の内容のまま**にし、読み込み表示だけ下げる。 */
    private fun ReadingTraceCard.withSlotResult(item: Item?): ReadingTraceCard =
        if (isMarked) {
            copy(isSummaryLoading = false)
        } else {
            copy(aiSummary = item?.text, aiSummaryKind = item?.kind, isSummaryLoading = false)
        }

    /**
     * カードを組み立てる。**枠の1件は呼び出し側が決めて渡す** — 痕跡の `aiSummary` を既定で拾わない。
     * 拾うと、旧仕様の俯瞰要約や途中のノートに残った問いが、今回の分岐と無関係に出る。
     */
    private fun cardOf(
        trace: ReadingTrace,
        item: Item? = null,
        isSlotLoading: Boolean = false
    ): ReadingTraceCard {
        val last = trace.visits.last()
        // **印があれば、枠の中身は保存済みのものへ差し替える。** 生成は行わない。
        val marked = trace.markedSummary
        return ReadingTraceCard(
            // 追加のI/Oは無い。この経路は既に痕跡を読んでいる。
            visitCount = trace.totalVisitCount,
            lastVisitAtMillis = last.atEpochMillis,
            lastSectionTitle = last.deepestSectionTitle,
            lastProgressPercent = last.progressPercent,
            aiSummary = marked ?: item?.text,
            aiSummaryKind = if (marked != null) trace.markedKind else item?.kind,
            // **欄を足したら、値を供給する側まで監査する。**
            // 読む側（UIの入口）だけ直して既定値 false のまま出荷すると、
            // 行が一度も出ないまま緑になる。
            hasMemos = trace.memos.isNotEmpty(),
            isMarked = marked != null,
            isSummaryLoading = isSlotLoading
        )
    }

    /**
     * 選別の結果。**null へ畳まない。**
     *
     * 「呼べなかった」「AIがどれも選ばなかった」「1件決まった」は、**呼び出し側の次の行動が全部違う**。
     * 畳むと、モデル未取得の回まで「試行済み」として記録され、**利用可能になっても
     * 訪問数が変わるまで枠が出ない**（→ [lessons L28](../../../../../../../../docs/dev/lessons.md)）。
     */
    private sealed interface ReunionOutcome {
        /** 1件決まった。保存して表示する。 */
        data class Generated(val summary: String, val kind: ReunionKind) : ReunionOutcome

        /**
         * **AIが明示的に「どれも該当しない」と答えた。**
         * 空振りとして記録し、同じ訪問数では聞き直さない。枠はノートの要約になる。
         */
        data object NoCandidate : ReunionOutcome

        /**
         * 呼べなかった・失敗した・約束の形で返ってこなかった。**何も記録しない。**
         *
         * 候補外のIDや空応答をここへ入れるのは、**モデルが約束を守らなかっただけで
         * 「該当が無い」という判断ではない**ため。次に開いたときに素直に試し直す。
         */
        data object Unavailable : ReunionOutcome
    }

    /**
     * 候補から1件選ばせる。**同じ契機の中で2回目を呼ばない**（Nano は Mutex 直列なので、
     * 空振りしたからといって別の種別で引き直すと待ち時間が倍になる）。
     *
     * **返ってくるのはIDだけ**で、表示するのは手元の原文。
     * **明示的な `NONE` だけを空振りとして扱う。** 候補外のIDや空応答は
     * 「該当が無い」ではなく約束違反なので、記録せず次回試し直す。
     */
    private suspend fun selectCandidate(
        trace: ReadingTrace,
        kind: ReunionKind,
        candidates: List<String>
    ): ReunionOutcome = try {
        when (aiClient.checkAvailability()) {
            // 未ダウンロードでも自動DLしない（読むたびモデルDLを始めない）。黙って生のまま。
            // **非対応も取得失敗も同じ枝でよい**（意図的）— 読書痕跡はユーザーが意識しない
            // 機能なので、理由を出し分けても見せる先が無い。
            // **ただし「記録しない」ことは重要** — 記録すると、モデルが使えるようになっても
            // 訪問数が変わるまで枠が出なくなる。
            AiAvailability.NeedsDownload,
            AiAvailability.Downloading,
            AiAvailability.Unsupported,
            is AiAvailability.TemporarilyUnavailable -> ReunionOutcome.Unavailable
            AiAvailability.Ready -> {
                // **見出しの1文は出し終えている。** 待たせるのは選別の生成だけ（→ background_ai_ux.md §7）。
                awaitDwell()
                generateSelection(trace, kind, candidates)
            }
        }
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        // タイムアウト・生成失敗も黙って劣化させる。
        ReunionOutcome.Unavailable
    }

    private suspend fun generateSelection(
        trace: ReadingTrace,
        kind: ReunionKind,
        candidates: List<String>
    ): ReunionOutcome {
        if (candidates.isEmpty()) return ReunionOutcome.Unavailable
        val lines = candidates.mapIndexed { index, text ->
            ReunionCandidateLine(reunionCandidateId(index), text)
        }
        val prompt = PromptBuilder.buildReunionSelectionPrompt(trace.noteTitle, kind, lines)
        val response = aiClient.generate(prompt.text).trim()
        if (response.isBlank()) return ReunionOutcome.Unavailable
        if (isReunionNone(response)) return ReunionOutcome.NoCandidate

        val picked = parseCandidateIds(response, prompt.validIds, limit = 1, prefix = 'R')
            .firstOrNull()
            ?: return ReunionOutcome.Unavailable
        val text = lines.first { it.id == picked }.text
        return ReunionOutcome.Generated(
            truncateToUtf8Bytes(text, ReadingTraceLimits.MAX_AI_SUMMARY_BYTES),
            kind
        )
    }

    /**
     * 選別の結果をサイドカーへ載せる。**[ReunionOutcome.Unavailable] は何も書かない。**
     *
     * 訪問の保存（[ReadingTraceController]）と違い、**保存結果を見ないし再試行もしない**。
     * 書けなければ次回の再会で選び直される（自己修復する）。
     *
     * **空振りは書く。** 書かないと、同じノートを開くたびに同じ候補で生成し直す
     * （Mutex 直列なので待ち時間だけが増える）。記録する種別は [ReunionKind.Overview]。
     */
    private suspend fun persistOutcome(
        trace: ReadingTrace,
        outcome: ReunionOutcome,
        vaultKey: String
    ) {
        val (summary, kind) = when (outcome) {
            is ReunionOutcome.Generated -> outcome.summary to outcome.kind
            ReunionOutcome.NoCandidate -> null to ReunionKind.Overview
            // 呼べていない・失敗した回は「試した」に数えない。次に開いたとき素直に試し直す。
            ReunionOutcome.Unavailable -> return
        }
        withContext(ioDispatcher) {
            writeMutex.withLock {
                // 生成中に訪問の書き出し（[ReadingTraceController]）が訪問を足している可能性があるので、
                // 最新を読み直して結果だけを載せる。件数は「選別を試みた訪問数」を記録するので、
                // 生成中に増えていれば次回の再会でちゃんと選び直される。
                val latest = (persistence.load(trace.vaultRelativePath, vaultKey) as? ReadingTraceReadResult.Valid)
                    ?.trace
                    ?: return@withLock
                persistence.save(
                    latest.copy(
                        aiSummary = summary,
                        aiSummaryVisitCount = trace.totalVisitCount,
                        aiSummaryKind = kind
                    ),
                    vaultKey
                )
            }
        }
    }

    private fun isCurrent(requestId: Long): Boolean = requestId == activeRequestId
}
