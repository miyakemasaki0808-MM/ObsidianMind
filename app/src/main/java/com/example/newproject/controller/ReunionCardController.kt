package com.example.newproject.controller

import com.example.newproject.ai.AiAvailability
import com.example.newproject.ai.AiClient
import com.example.newproject.ai.PromptBuilder
import com.example.newproject.ai.ReunionCandidateLine
import com.example.newproject.data.ReadingTracePersistence
import com.example.newproject.data.ReadingTraceReadResult
import com.example.newproject.domain.decideReunionKind
import com.example.newproject.domain.forKind
import com.example.newproject.domain.parseCandidateIds
import com.example.newproject.domain.reunionCandidateId
import com.example.newproject.domain.scanReunionCandidates
import com.example.newproject.model.ReadingTrace
import com.example.newproject.model.ReadingTraceLimits
import com.example.newproject.model.ReadingTraceStateWriter
import com.example.newproject.model.ReunionKind
import com.example.newproject.model.isReunionNone
import com.example.newproject.model.needsAiSummary
import com.example.newproject.model.state.ReadingTraceCard
import com.example.newproject.model.truncateToUtf8Bytes
import com.example.newproject.model.wasEmptyReunionAttempt
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
    private val clock: () -> Long = System::currentTimeMillis,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /** 候補の列挙は入力サイズに比例するので Main の外で回す（→ lessons L13）。 */
    private val scanDispatcher: CoroutineDispatcher = Dispatchers.Default,
    /**
     * サイドカーの read-modify-write を直列化する錠。**外から受け取る。**
     *
     * 同じサイドカーを訪問の追記（[ReadingTraceController]）と読み戻しの適用
     * （[ReadingTraceBackupController]）も書く。要約と印の書き戻しが別の錠で走ると、
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

    /** ノート切替時に、進行中の照合・要約生成を捨てる（後着で別ノートのカードを出さない）。 */
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
            val trace = withContext(ioDispatcher) {
                (persistence.load(vaultRelativePath, vaultKey) as? ReadingTraceReadResult.Valid)?.trace
            } ?: return@launch
            if (!isCurrent(requestId)) return@launch

            // **印があれば、この時点で再掲が確定する。** 生成もしない。
            // 印は*その内容*への意図なので、作り直すと別の文が出て意図とずれる。
            if (trace.hasMark) {
                setCard(cardOf(trace))
                return@launch
            }

            // まず生の痕跡でカードを出す。AIを待たせないのが要点。
            val needsSummary = trace.needsAiSummary
            setCard(cardOf(trace, isSummaryLoading = needsSummary))
            if (!needsSummary) return@launch

            // 列挙は入力サイズに比例するので Main の外で回す（→ lessons L13）。
            val candidates = withContext(scanDispatcher) { scanReunionCandidates(content) }
            if (!isCurrent(requestId)) return@launch
            // **前回が空振りなら俯瞰要約へ倒す。** 候補は本文から決まるので、同じノートを
            // 開き直すと同じ候補が同じ理由で拒否され続け、枠が見出しだけのまま止まる
            // （→ features/reunion_card.md「空振りの扱い」）。
            val kind = if (trace.wasEmptyReunionAttempt) ReunionKind.Overview else decideReunionKind(candidates)

            val outcome = generateForKind(trace, kind, candidates.forKind(kind))
            if (!isCurrent(requestId)) return@launch
            // **空振りも失敗も、生の痕跡は残して読み込み表示だけ下げる。**
            val generated = outcome as? ReunionOutcome.Generated
            setCard(cardOf(trace, aiSummary = generated?.summary, aiSummaryKind = generated?.kind))
            persistOutcome(trace, outcome, vaultKey)
        }
    }

    /**
     * 「まだ考えたい」を切り替える。
     *
     * **押した時点で枠に出ていた内容ごと控える。** 次の再会で作り直すと別の文が出て
     * 意図とずれるため（→ features/reunion_card.md §6）。
     * **もう一度押すと外れる。**「読んだ」で畳んでも外れない（閉じる操作と取り消しは別）。
     */
    fun toggleMark() {
        val card = state.current ?: return
        val vaultRelativePath = revealedPath ?: return
        val vaultKey = currentVaultKey() ?: return
        val summary = card.aiSummary
        val kind = card.aiSummaryKind
        // 出ているものが無ければ印の付けようがない。
        if (!card.isMarked && (summary == null || kind == null)) return

        // 画面は先に返す。**保存の往復を待たせない**（失敗しても次の再会で作り直せる）。
        val marking = !card.isMarked
        val markKey = markKey(vaultKey, vaultRelativePath)
        val requestId = markRequestIds.merge(markKey, 1L, Long::plus)!!
        state.update { it?.copy(isMarked = marking) }
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
                            summary = requireNotNull(summary),
                            kind = requireNotNull(kind),
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
        aiSummaryKind: ReunionKind? = trace.aiSummaryKind?.takeIf { aiSummary != null },
        isSummaryLoading: Boolean = false
    ): ReadingTraceCard {
        val last = trace.visits.last()
        // **印があれば、枠の中身は保存済みのものへ差し替える。** 生成は行わない。
        val marked = trace.markedSummary
        return ReadingTraceCard(
            // 追加のI/Oは無い。この経路は既に痕跡を読んでいる。
            hasReflectionReply = trace.reflection?.hasReply == true,
            visitCount = trace.totalVisitCount,
            lastVisitAtMillis = last.atEpochMillis,
            lastSectionTitle = last.deepestSectionTitle,
            lastProgressPercent = last.progressPercent,
            aiSummary = marked ?: aiSummary,
            aiSummaryKind = if (marked != null) trace.markedKind else aiSummaryKind,
            isMarked = marked != null,
            isSummaryLoading = isSummaryLoading
        )
    }

    /**
     * 生成の結果。**null へ畳まない。**
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
         * 空振りとして記録し、次の生成契機では俯瞰要約へ倒す。
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
     * 種別に応じて1回だけ生成する。**同じ契機の中で2回目を呼ばない。**
     *
     * Nano は Mutex 直列なので、空振りしたからといって別の種別で引き直すと待ち時間が倍になる
     * （→ features/reunion_card.md「空振りの扱い」）。
     */
    private suspend fun generateForKind(
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
                // **生の痕跡は出し終えている。** 待たせるのは要約の生成だけ（→ background_ai_ux.md §7）。
                awaitDwell()
                when (kind) {
                    ReunionKind.Overview -> generateOverview(trace)
                    ReunionKind.Question, ReunionKind.Staleness -> selectCandidate(trace, kind, candidates)
                }
            }
        }
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        // タイムアウト・生成失敗も黙って劣化させる。ユーザーが意識しない機能なので
        // エラー表示は出さず、生の痕跡だけが見えている状態に留める。
        ReunionOutcome.Unavailable
    }

    private suspend fun generateOverview(trace: ReadingTrace): ReunionOutcome {
        val prompt = PromptBuilder.buildReadingTraceSummaryPrompt(
            noteTitle = trace.noteTitle,
            visits = trace.visits,
            totalVisitCount = trace.totalVisitCount
        )
        val summary = aiClient.generate(prompt)
            .trim()
            .takeIf { it.isNotBlank() }
            // 空応答は「まとめるものが無い」ではなく生成の失敗。記録せず次回試し直す。
            ?: return ReunionOutcome.Unavailable
        return ReunionOutcome.Generated(
            truncateToUtf8Bytes(summary, ReadingTraceLimits.MAX_AI_SUMMARY_BYTES),
            ReunionKind.Overview
        )
    }

    /**
     * 候補から1件選ばせる。**返ってくるのはIDだけ**で、表示するのは手元の原文。
     *
     * **明示的な `NONE` だけを空振りとして扱う。** 候補外のIDや空応答は
     * 「該当が無い」ではなく約束違反なので、記録せず次回試し直す。
     */
    private suspend fun selectCandidate(
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
     * 生成の結果をサイドカーへ載せる。**[ReunionOutcome.Unavailable] は何も書かない。**
     *
     * 訪問の保存（[ReadingTraceController]）と違い、**保存結果を見ないし再試行もしない**。
     * 書けなければ次回の再会で作り直される（自己修復する）。
     *
     * **空振りは書く。** 書かないと再生成の判定が真のまま残り、同じノートを開くたびに
     * 同じ候補で生成し直す（Mutex 直列なので待ち時間だけが増える）。
     * **記録する種別は [ReunionKind.Overview]** — 次の生成契機で俯瞰要約へ倒すため
     * （→ features/reunion_card.md「空振りの扱い」）。
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
                // 生成中に訪問の書き出し（[ReadingTraceController]）が訪問を足している可能性があるので、最新を読み直して
                // 要約だけを載せる。件数は「生成を試みた訪問数」を記録するので、
                // 生成中に増えていれば次回の再会でちゃんと作り直される。
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
