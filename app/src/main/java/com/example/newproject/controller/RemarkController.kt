package com.example.newproject.controller

import com.example.newproject.ai.AiAvailability
import com.example.newproject.ai.AiClient
import com.example.newproject.ai.PromptBuilder
import com.example.newproject.ai.RemarkCandidateLine
import com.example.newproject.domain.RemarkResult
import com.example.newproject.domain.buildNoteExcerpt
import com.example.newproject.domain.composeMirroredRemark
import com.example.newproject.domain.composeRemark
import com.example.newproject.domain.excerptReplyForPrompt
import com.example.newproject.domain.remarkCandidateId
import com.example.newproject.domain.toObsidianNoteTitle
import com.example.newproject.model.NoteExcerptLimits
import com.example.newproject.model.Reflection
import com.example.newproject.model.RelatedNote
import com.example.newproject.model.RemarkStateWriter
import com.example.newproject.model.state.RemarkState
import com.example.newproject.model.state.ReplyStatus
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
 * 旧補記との違いは3つで、いずれも features/reflect_remark.md の判断に対応する。
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
    private val onRemarkReady: (Reflection) -> Unit,
    /**
     * 返事の即時保存。`ReadingTraceController.saveReply` を繋ぐ。
     * **生成物と違い、ユーザーが書いた言葉は作り直せない**ので離脱を待たない。
     */
    private val persistReply: suspend (vaultRelativePath: String, reply: String, at: Long) -> ReplySaveOutcome,
    /** 保存済みの組の読み込み。専用画面を開いたときにだけ呼ぶ。 */
    private val loadReflection: suspend (vaultRelativePath: String) -> Reflection?,
    /**
     * いま表示しているノートの本文。映し返しのプロンプトへ渡す。
     *
     * **`create()` が受け取った本文を持ち回らない。** 保存済みを読み戻した経路
     * （`restoreSaved`）はそこを通らないため、フィールドに写す形だと
     * 映し返しが片方の経路でだけ出なくなる。
     */
    private val currentContent: () -> String?,
    /** 映し返しの保存。失敗しても状態は進める（無くても成立するため）。 */
    private val persistMirrored: suspend (vaultRelativePath: String, mirrored: String) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
    private val excerptDispatcher: CoroutineDispatcher = Dispatchers.Default
) {
    private var pending: PendingRemark? = null


    private var createJob: Job? = null
    private var downloadJob: Job? = null
    private var replyJob: Job? = null
    private var restoreJob: Job? = null
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

    /**
     * 専用画面を開いたときに、保存済みの組を読み込む。
     *
     * **生成中・生成済みなら何もしない。** 走行中の結果を古い保存値で上書きしないため。
     * 読めなければ Idle のまま（「まだひとことはありません」が出る）。
     */
    fun restoreSaved(vaultRelativePath: String?, title: String) {
        if (state.current !is RemarkState.Idle) return
        val path = vaultRelativePath?.takeIf { it.isNotBlank() } ?: return
        val requestId = ++activeRequestId
        val sourceTitle = title.toObsidianNoteTitle()
        restoreJob?.cancel()
        restoreJob = scope.launch {
            val saved = try {
                loadReflection(path)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            } ?: return@launch
            if (!isCurrent(requestId)) return@launch
            // 待っている間に生成が始まっていたら割り込まない。
            if (state.current !is RemarkState.Idle) return@launch
            // 読み戻したものは既にファイルにある＝保存済み。
            val status = if (saved.hasReply) ReplyStatus.Saved else ReplyStatus.None
            state.update { RemarkState.Ready(sourceTitle, saved, status) }
        }
    }

    /**
     * 返事を残す。
     *
     * **保存結果を握り潰さない。** 預かった（[ReplySaveOutcome.Held]）なら
     * 離脱時に書かれるので失敗として見せないが、
     * [ReplySaveOutcome.Lost] は本当に消えるので画面へ出す。
     * 以前はすべて「保存済み」と表示しており、**ユーザーの返事を黙って失う**経路があった。
     */
    fun saveReply(vaultRelativePath: String?, reply: String) {
        val current = state.current as? RemarkState.Ready ?: return
        if (current.replyStatus == ReplyStatus.Saving) return
        val trimmed = reply.trim()
        if (trimmed.isBlank()) return
        val path = vaultRelativePath?.takeIf { it.isNotBlank() } ?: return

        val requestId = ++activeRequestId
        state.update { current.copy(replyStatus = ReplyStatus.Saving) }
        replyJob?.cancel()
        replyJob = scope.launch {
            val at = clock()
            val outcome = try {
                persistReply(path, trimmed, at)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 例外も「どこにも残っていない」として扱う。握り潰すと
                // 画面だけ保存済みになり、返事が黙って消える。
                ReplySaveOutcome.Lost
            }
            if (!isCurrent(requestId)) return@launch
            state.update { latest ->
                val ready = latest as? RemarkState.Ready ?: return@update latest
                ready.copy(
                    // Lost でも本文は状態へ残す。消してしまうと書き直しもできない。
                    reflection = ready.reflection.withReply(trimmed, at),
                    replyStatus = when (outcome) {
                        ReplySaveOutcome.Saved -> ReplyStatus.Saved
                        // **保存済みとは呼ばない。** 離脱時の書き込みで確定する。
                        ReplySaveOutcome.Held -> ReplyStatus.Held
                        ReplySaveOutcome.Lost -> ReplyStatus.Failed
                    }
                )
            }
            // **返事を保存し終えてから映し返しを作る。** 先に生成すると、
            // 生成の失敗やキャンセルでユーザーの言葉まで巻き込まれる。
            // 保存できなかった（Lost）ときは作らない — 消える返事へ応じても仕方がない。
            if (outcome != ReplySaveOutcome.Lost) {
                generateMirror(requestId, path, trimmed)
            }
        }
    }

    /**
     * 返事を受けて1文だけ返す（映し返し）。**問いは返させない。**
     *
     * 失敗しても何も出さない。**返事は既に保存済み**なので、
     * ここが空でもユーザーの言葉は失われない。エラーを見せないのは、
     * 対話の閉じ方が「うまくいけば添えられる」ものだからで、
     * 失敗を通知すると返事を残したこと自体が失敗したように見える。
     */
    private suspend fun generateMirror(requestId: Long, vaultRelativePath: String, reply: String) {
        val ready = state.current as? RemarkState.Ready ?: return
        // **表示中のノートから引く。** 以前は create() が受け取った本文を
        // フィールドへ写していたが、`restoreSaved` は通らないので
        // **保存済みのひとことに後から返事を書くと、映し返しが黙って出なかった**
        // （Rediscover の「前回の返事を見る」から入った場合がこれ）。
        // 単一の出所（表示中のノート）から引けば、どちらの経路でも同じになる。
        val content = currentContent() ?: return
        try {
            if (aiClient.checkAvailability() != AiAvailability.Available) return
            val excerpt = withContext(excerptDispatcher) {
                buildNoteExcerpt(content, NoteExcerptLimits.ANNOTATION)
            }
            val prompt = PromptBuilder.buildRemarkMirrorPrompt(
                title = ready.sourceTitle,
                excerpt = excerpt,
                remark = ready.reflection.remark,
                // 保存した原文ではなく抜粋を渡す。出力枠は256トークン固定なので、
                // 入力を長くしても返ってくる1文は変わらない。
                reply = excerptReplyForPrompt(reply)
            )
            val generated = aiClient.generate(prompt)
            if (!isCurrent(requestId)) return
            val mirrored = composeMirroredRemark(generated) as? RemarkResult.Accepted ?: return

            // 保存は「あれば添える」程度なので、失敗しても状態だけ進める。
            persistMirrored(vaultRelativePath, mirrored.remark)
            if (!isCurrent(requestId)) return
            state.update { latest ->
                val current = latest as? RemarkState.Ready ?: return@update latest
                // 待っている間に返事が書き直されていたら、古い応答は載せない。
                if (current.reflection.reply != reply) return@update latest
                current.copy(reflection = current.reflection.withMirrored(mirrored.remark))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 映し返しは無くても成立する。黙って諦める。
        }
    }

    /** ノート・Vault切替時に生成を止め、旧ノートの結果が後から混入するのを防ぐ。 */
    fun cancelAndClear() {
        activeRequestId++
        createJob?.cancel()
        downloadJob?.cancel()
        replyJob?.cancel()
        restoreJob?.cancel()
        createJob = null
        downloadJob = null
        replyJob = null
        restoreJob = null
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
                    // **新しいひとことは返事を持たない**（前の返事は前の問いへのもの）。
                    val reflection = Reflection(
                        remark = composed.remark,
                        remarkedAtEpochMillis = clock()
                    )
                    onRemarkReady(reflection)
                    state.update { RemarkState.Ready(request.sourceTitle, reflection) }
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
