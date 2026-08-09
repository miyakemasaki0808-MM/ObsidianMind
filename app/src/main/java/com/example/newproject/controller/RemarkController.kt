package com.example.newproject.controller

import com.example.newproject.ai.AiAvailability
import com.example.newproject.ai.AiClient
import com.example.newproject.ai.PromptBuilder
import com.example.newproject.ai.RemarkCandidateLine
import com.example.newproject.domain.RemarkResult
import com.example.newproject.domain.buildNoteExcerpt
import com.example.newproject.domain.composeRemark
import com.example.newproject.domain.remarkCandidateId
import com.example.newproject.domain.toObsidianNoteTitle
import com.example.newproject.model.NoteExcerptLimits
import com.example.newproject.model.RelatedNote
import com.example.newproject.model.RemarkStateWriter
import com.example.newproject.model.state.RemarkState
import com.google.mlkit.genai.common.DownloadStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ノートへのひとこと（旧「AI補記メモ」）の生成。
 *
 * 旧補記との違いは3つで、いずれも design/reflect_remark.md の判断に対応する。
 *
 * - **出力は1文だけ。** 出力枠（256トークン）はゼロサムなので、分類ラベルを
 *   同時に出させると価値のある側が圧迫される（§0・§1）
 * - **Vaultへファイルを作らない。** 保存は読書痕跡サイドカーへ委ね、
 *   ここは生成と検証だけを持つ（§2）。したがって [VaultBrowser] に依存しない
 * - **未確認管理を持たない。** 結果は専用の1文画面（`RemarkScreen`）で読むので、
 *   AIタブのボタン自体が「見る」の導線になり `markViewed()` に相当する概念が要らない（§7.1）
 *
 * 保存を自分で行わず [onRemarkReady] へ渡すのは、痕跡ファイルが未作成のうちに
 * 単独保存すると初回のノートで黙って失われるため（§2.1）。
 */
internal class RemarkController(
    private val scope: CoroutineScope,
    private val aiClient: AiClient,
    private val state: RemarkStateWriter,
    /**
     * 検証を通ったひとことの渡し先。`ReadingTraceController.setPendingRemark` を繋ぐ。
     * **保存の成否はここでは見ない** — 痕跡側が次の書き込み契機で面倒を見る。
     */
    private val onRemarkReady: (String) -> Unit,
    private val excerptDispatcher: CoroutineDispatcher = Dispatchers.Default
) {
    private var pending: PendingRemark? = null
    private var createJob: Job? = null
    private var downloadJob: Job? = null
    private var activeRequestId = 0L

    fun create(
        title: String,
        content: String,
        relatedNotes: List<RelatedNote>,
        aiNotes: List<RelatedNote>
    ) {
        // 生成中の連続タップで二重に走らせない。
        if (state.current is RemarkState.Loading) return

        val requestId = ++activeRequestId
        val sourceTitle = title.toObsidianNoteTitle()
        val candidates = selectCandidates(sourceTitle, relatedNotes, aiNotes)

        val request = PendingRemark(
            requestId = requestId,
            title = title,
            sourceTitle = sourceTitle,
            content = content,
            candidates = candidates
        )

        state.update { RemarkState.Loading(sourceTitle) }
        createJob = scope.launch {
            try {
                when (aiClient.checkAvailability()) {
                    AiAvailability.Unavailable -> updateError(
                        requestId = requestId,
                        sourceTitle = sourceTitle,
                        message = "ひとことはこの端末では利用できません。"
                    )
                    AiAvailability.NeedsDownload -> {
                        pending = request
                        startModelDownload()
                    }
                    AiAvailability.Available -> generateWithAvailableModel(request)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                updateError(requestId, sourceTitle, e.message ?: "Unknown error")
            }
        }
    }

    /**
     * プロンプトへ載せる候補を選ぶ。
     *
     * **AI推薦を先に置く。** 本文まで見て選ばれており、かつ本文スニペットを
     * 持っているのはこちらだけなので、枠を絞るときに残す価値が高い。
     * 決定的な関連ノートのうち**既にwikilinkされているものは最後**へ回す —
     * 既に繋がっているノートへ「つなげると」と提案しても新しくない。
     *
     * 重複はタイトルで畳む（同じノートが2つのIDを持つと、モデルがどちらを選んでも
     * 同じ結果になるだけで候補枠を無駄にする）。
     */
    private fun selectCandidates(
        sourceTitle: String,
        relatedNotes: List<RelatedNote>,
        aiNotes: List<RelatedNote>
    ): List<RemarkCandidateLine> {
        val ordered = aiNotes +
            relatedNotes.filterNot { it.isWikilinked } +
            relatedNotes.filter { it.isWikilinked }
        return ordered
            .map { it.title.toObsidianNoteTitle() to it.snippet }
            .filter { (candidateTitle, _) -> candidateTitle.isNotBlank() && candidateTitle != sourceTitle }
            .distinctBy { (candidateTitle, _) -> candidateTitle }
            .take(REMARK_CANDIDATE_LIMIT)
            .mapIndexed { index, (candidateTitle, snippet) ->
                RemarkCandidateLine(
                    id = remarkCandidateId(index),
                    title = candidateTitle,
                    snippet = snippet?.take(REMARK_SNIPPET_CHARS)
                )
            }
    }

    /** ノート・Vault切替時に生成を止め、旧ノートの結果が後から混入するのを防ぐ。 */
    fun cancelAndClear() {
        activeRequestId++
        createJob?.cancel()
        downloadJob?.cancel()
        createJob = null
        downloadJob = null
        pending = null
        state.update { RemarkState.Idle }
    }

    private suspend fun generateWithAvailableModel(request: PendingRemark) {
        try {
            val excerpt = withContext(excerptDispatcher) {
                buildNoteExcerpt(request.content, NoteExcerptLimits.ANNOTATION)
            }
            val prompt = PromptBuilder.buildRemarkPrompt(
                title = request.sourceTitle,
                excerpt = excerpt,
                candidates = request.candidates
            )
            val generated = aiClient.generate(prompt)
            if (!isCurrent(request.requestId)) return

            // 根拠の判定は**モデルへ渡した抜粋**に対して行う。原文全体で測ると、
            // 抜粋から切り落とされた区間の語をモデルが知っていたことになってしまう。
            val composed = composeRemark(
                response = generated,
                groundingSource = excerpt.text,
                candidateTitlesById = request.candidates.associate { it.id to it.title }
            )
            if (!isCurrent(request.requestId)) return

            when (composed) {
                is RemarkResult.Accepted -> {
                    // 先に痕跡へ預ける。表示だけして預け忘れると、画面に出た
                    // ひとことがノートを離れた瞬間に消える。
                    onRemarkReady(composed.remark)
                    state.update { RemarkState.Ready(request.sourceTitle, composed.remark) }
                }
                // **理由の内訳は見せないが、2種類には分ける。**
                // 「一般論だったので捨てた」を見せても次の行動は変わらないが、
                // 「出すものが無い」と「書式を守れなかった」では**再試行が効くかが違う**。
                // 畳むと、モデルの失敗が「問いが見つかりませんでした」に化ける。
                is RemarkResult.Rejected -> {
                    val next = if (composed.reason.isModelFailure) {
                        RemarkState.Unusable(request.sourceTitle)
                    } else {
                        RemarkState.Empty(request.sourceTitle)
                    }
                    state.update { next }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            updateError(request.requestId, request.sourceTitle, e.message ?: "Unknown error")
        }
    }

    private fun startModelDownload() {
        downloadJob?.cancel()
        downloadJob = scope.launch {
            try {
                aiClient.downloadModel().collect { status ->
                    when (status) {
                        is DownloadStatus.DownloadStarted,
                        is DownloadStatus.DownloadProgress -> {
                            // Loading には開始時の対象タイトルを保持したままにする。
                        }
                        is DownloadStatus.DownloadCompleted -> {
                            val request = pending ?: return@collect
                            if (!isCurrent(request.requestId)) return@collect
                            pending = null
                            generateWithAvailableModel(request)
                        }
                        is DownloadStatus.DownloadFailed -> {
                            val request = pending ?: return@collect
                            pending = null
                            updateError(
                                requestId = request.requestId,
                                sourceTitle = request.sourceTitle,
                                message = "モデルのダウンロードに失敗しました: ${status.e.message}"
                            )
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val request = pending ?: return@launch
                pending = null
                updateError(
                    requestId = request.requestId,
                    sourceTitle = request.sourceTitle,
                    message = "ダウンロードエラー: ${e.message}"
                )
            }
        }
    }

    private fun isCurrent(requestId: Long): Boolean = activeRequestId == requestId

    private fun updateError(requestId: Long, sourceTitle: String, message: String) {
        if (!isCurrent(requestId)) return
        state.update { RemarkState.Error(message = message, sourceTitle = sourceTitle) }
    }

    private data class PendingRemark(
        val requestId: Long,
        val title: String,
        val sourceTitle: String,
        val content: String,
        val candidates: List<RemarkCandidateLine>
    )

    private companion object {
        /**
         * プロンプトへ載せる候補ノートの上限。
         *
         * 旧補記は関連ノート・AI推薦・**無制限の全wikilink**の3ブロックを渡しながら
         * 出力で使わせていなかった。リンク集ノートで候補が本文を押し出すのを防ぐため、
         * ひとことでは件数を固定する。
         *
         * **8件のタイトルより3件＋抜粋を選ぶ**（2026-08-09 の実機確認の指摘）。
         * タイトルだけでは中身に踏み込んだ接続理由を作れず、
         * 「並べると別の角度から見えそう」のような当たり障りのない文になりやすい。
         */
        const val REMARK_CANDIDATE_LIMIT = 3

        /**
         * 候補1件あたりの抜粋の長さ。関連ノートAIの上限（150）より短くするのは、
         * あちらが候補を選ばせるために読ませるのに対し、こちらは
         * **接続の手がかりが分かれば足りる**ため。3件で合計240文字に収まる。
         */
        const val REMARK_SNIPPET_CHARS = 80
    }
}
