package com.example.newproject

import com.example.newproject.ai.AiAvailability
import com.example.newproject.ai.AiClient
import com.example.newproject.ai.PromptBuilder
import com.example.newproject.domain.DistillCandidate
import com.example.newproject.domain.DistillLimits
import com.example.newproject.domain.DistillSourceModel
import com.example.newproject.domain.DistillTextRange
import com.example.newproject.domain.applyDistillBold
import com.example.newproject.domain.buildDistillSourceModel
import com.example.newproject.domain.isWithinDistillBoldLimit
import com.example.newproject.domain.parseDistillResponseIds
import com.example.newproject.domain.projectedBoldRatio
import com.example.newproject.domain.selectDistillCandidates
import com.google.mlkit.genai.common.DownloadStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Reflectの蒸留分析・候補選択・保存・復旧を1つのrequestIdで直列化する。 */
internal class DistillController(
    private val scope: CoroutineScope,
    private val aiClient: AiClient,
    private val uiState: MutableStateFlow<NoteUiState>,
    private val persistence: DistillPersistence,
    private val reloadBody: suspend (targetUri: String, expectedHash: String?) -> Boolean,
    private val analysisDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private data class AnalysisInput(
        val title: String,
        val content: String,
        val targetUri: String,
        val baselineHash: String
    )

    private data class ActiveSession(
        val requestId: Long,
        val input: AnalysisInput,
        val model: DistillSourceModel,
        val candidatesById: Map<String, DistillCandidate>,
        val singleSentenceExceptionId: String?
    )

    private var activeRequestId = 0L
    private var job: Job? = null
    private var pendingDownload: AnalysisInput? = null
    private var session: ActiveSession? = null

    fun start() {
        if (uiState.value.distillState is DistillState.Analyzing ||
            uiState.value.distillState is DistillState.Downloading ||
            uiState.value.distillState is DistillState.Saving
        ) return

        val note = uiState.value.noteState as? NoteState.Success
        if (note == null) {
            update(DistillState.Error("先にノートを開いてください。", canRetry = false))
            return
        }
        note.distillUnavailableReason?.let { reason ->
            update(DistillState.Unavailable(reason))
            return
        }
        val hash = note.originalHash
        if (note.targetUri.isBlank() || hash == null) {
            update(DistillState.Unavailable("このノートは蒸留用の安全確認情報を取得できません。"))
            return
        }

        val input = AnalysisInput(note.title, note.content, note.targetUri, hash)
        val requestId = ++activeRequestId
        pendingDownload = null
        session = null
        update(DistillState.Analyzing(note.title))
        job?.cancel()
        job = scope.launch {
            try {
                when (aiClient.checkAvailability()) {
                    AiAvailability.Unavailable -> ifCurrent(requestId) {
                        update(DistillState.Unavailable("蒸留はこの端末のGemini Nanoでは利用できません。"))
                    }
                    AiAvailability.NeedsDownload -> ifCurrent(requestId) {
                        pendingDownload = input
                        update(DistillState.NeedsDownload(input.title))
                    }
                    AiAvailability.Available -> analyze(requestId, input)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                ifCurrent(requestId) {
                    update(DistillState.Error(error.message ?: "蒸留を開始できませんでした。"))
                }
            }
        }
    }

    fun downloadModelAndResume() {
        val input = pendingDownload ?: return
        val requestId = activeRequestId
        job?.cancel()
        job = scope.launch {
            update(DistillState.Downloading(input.title, downloaded = -1L, total = 0L))
            try {
                aiClient.downloadModel().collect { status ->
                    if (!isCurrent(requestId)) return@collect
                    when (status) {
                        is DownloadStatus.DownloadStarted -> update(
                            DistillState.Downloading(input.title, 0L, status.bytesToDownload)
                        )
                        is DownloadStatus.DownloadProgress -> {
                            val total = (uiState.value.distillState as? DistillState.Downloading)?.total ?: 0L
                            update(DistillState.Downloading(input.title, status.totalBytesDownloaded, total))
                        }
                        is DownloadStatus.DownloadCompleted -> {
                            pendingDownload = null
                            analyze(requestId, input)
                        }
                        is DownloadStatus.DownloadFailed -> update(
                            DistillState.Error("モデルのダウンロードに失敗しました: ${status.e.message}")
                        )
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                ifCurrent(requestId) {
                    update(DistillState.Error(error.message ?: "モデルをダウンロードできませんでした。"))
                }
            }
        }
    }

    private suspend fun analyze(requestId: Long, input: AnalysisInput) {
        val prepared = withContext(analysisDispatcher) {
            val model = buildDistillSourceModel(input.content)
            val candidates = selectDistillCandidates(model, input.title)
            Triple(model, candidates, PromptBuilder.buildDistillPrompt(input.title, candidates))
        }
        if (!isCurrent(requestId)) return
        val (model, _, prompt) = prepared
        if (prompt.candidates.isEmpty()) {
            update(DistillState.Error("蒸留できる本文候補がありません。", canRetry = false))
            return
        }

        val response = aiClient.generate(prompt.text)
        if (!isCurrent(requestId)) return
        val selectedIds = parseDistillResponseIds(response, prompt.validIds)
        if (selectedIds.isEmpty()) {
            update(DistillState.Error("AIが有効な候補を選べませんでした。もう一度お試しください。"))
            return
        }
        val byId = prompt.candidates.associateBy { it.id }
        val selectedCandidates = selectedIds.mapNotNull(byId::get)
        val initialSelectionIds = initialSelectionIds(model, selectedCandidates)
        val exceptionId = initialSelectionIds.singleOrNull()?.takeIf { selectedId ->
            val range = byId.getValue(selectedId).sentence.range
            !isWithinDistillBoldLimit(model, listOf(range))
        }
        session = ActiveSession(
            requestId,
            input,
            model,
            byId.filterKeys(selectedIds::contains),
            exceptionId
        )
        val items = selectedCandidates.map { candidate ->
            val sentence = candidate.sentence
            DistillCandidateItem(
                id = candidate.id,
                text = sentence.text,
                heading = sentence.heading,
                positionLabel = "${sentence.sourceIndex + 1} / ${model.sentences.size}",
                context = model.sentences.getOrNull(sentence.sourceIndex - 1)?.text,
                isSelected = candidate.id in initialSelectionIds
            )
        }
        updateCandidateState(input.title, items)
    }

    fun toggleCandidate(id: String) {
        val current = uiState.value.distillState as? DistillState.Candidates ?: return
        if (session?.candidatesById?.containsKey(id) != true) return
        updateCandidateState(
            current.sourceTitle,
            current.items.map { item -> if (item.id == id) item.copy(isSelected = !item.isSelected) else item }
        )
    }

    private fun updateCandidateState(title: String, items: List<DistillCandidateItem>) {
        val active = session ?: return
        val selectedItems = items.filter { it.isSelected }
        val ranges = selectedItems.mapNotNull { active.candidatesById[it.id]?.sentence?.range }
        val isWithinLimit = isWithinDistillBoldLimit(active.model, ranges)
        val isSingleSentenceException = !isWithinLimit &&
            active.singleSentenceExceptionId != null &&
            selectedItems.singleOrNull()?.id == active.singleSentenceExceptionId
        update(
            DistillState.Candidates(
                sourceTitle = title,
                items = items,
                projectedBoldRatio = projectedBoldRatio(active.model, ranges),
                isWithinBoldLimit = isWithinLimit,
                isSingleSentenceException = isSingleSentenceException
            )
        )
    }

    fun saveSelection() {
        val active = session ?: return
        val current = uiState.value.distillState as? DistillState.Candidates ?: return
        val selected = current.items.filter { it.isSelected }
        if (!current.canSaveSelection) return
        val ranges = selected.mapNotNull { active.candidatesById[it.id]?.sentence?.range }
        val transformed = applyDistillBold(active.input.content, ranges).content
        val requestId = active.requestId
        update(DistillState.Saving(active.input.title, verifying = true))
        job?.cancel()
        job = scope.launch {
            val result = withContext(ioDispatcher) {
                persistence.write(
                    DistillWriteRequest(
                        targetUri = active.input.targetUri,
                        baselineHash = active.input.baselineHash,
                        outputBytes = transformed.toByteArray(Charsets.UTF_8)
                    )
                )
            }
            if (!isCurrent(requestId)) return@launch
            when (result) {
                is DistillWriteResult.Success -> {
                    val reloaded = reloadBody(active.input.targetUri, result.outputHash)
                    if (!isCurrent(requestId)) return@launch
                    if (reloaded) {
                        session = null
                        update(DistillState.Saved(active.input.title, selected.size))
                    } else {
                        update(DistillState.Error("保存は完了しましたが、本文を再読込できませんでした。"))
                    }
                }
                is DistillWriteResult.Conflict -> update(DistillState.Conflict(result.message))
                is DistillWriteResult.InsufficientStorage -> update(
                    DistillState.Error("内部ストレージの空き容量が不足しています。", canRetry = false)
                )
                is DistillWriteResult.StorageUnavailable -> update(
                    DistillState.Error(result.message, canRetry = false)
                )
                is DistillWriteResult.PendingRecovery -> showRecoveryAssessment(
                    withContext(ioDispatcher) { persistence.assessPendingRecovery() }
                )
                is DistillWriteResult.Failure -> {
                    if (result.recoveryRequired) {
                        showRecoveryAssessment(withContext(ioDispatcher) { persistence.assessPendingRecovery() })
                    } else {
                        update(DistillState.Error(result.message))
                    }
                }
            }
        }
    }

    /** AIの重要度順を保ちつつ、累積上限内へ収まる候補だけを初期選択する。 */
    private fun initialSelectionIds(
        model: DistillSourceModel,
        candidates: List<DistillCandidate>
    ): Set<String> {
        val selectedIds = linkedSetOf<String>()
        val selectedRanges = mutableListOf<DistillTextRange>()
        candidates.forEach { candidate ->
            val proposed = selectedRanges + candidate.sentence.range
            if (isWithinDistillBoldLimit(model, proposed)) {
                selectedIds += candidate.id
                selectedRanges += candidate.sentence.range
            }
        }
        if (selectedIds.isNotEmpty()) return selectedIds

        // 既存太字だけで上限に達している場合は例外追加しない。本文が短く、最重要文を
        // 1文選ぶだけで上限を越える場合に限って、ユーザー確認付きの例外候補とする。
        val existingRatio = if (model.eligibleBodyCharacterCount <= 0) 0.0 else {
            model.existingBoldCharacterCount.toDouble() / model.eligibleBodyCharacterCount
        }
        val topCandidate = candidates.firstOrNull() ?: return emptySet()
        val topSentenceRatio = projectedBoldRatio(model.copy(existingBoldCharacterCount = 0), listOf(topCandidate.sentence.range))
        return if (
            existingRatio < DistillLimits.MAX_BOLD_RATIO &&
            topSentenceRatio > DistillLimits.MAX_BOLD_RATIO
        ) setOf(topCandidate.id) else emptySet()
    }

    fun retry() {
        val target = session?.input?.targetUri
        if (uiState.value.distillState is DistillState.Conflict && target != null) {
            val requestId = ++activeRequestId
            job?.cancel()
            update(DistillState.Analyzing(session?.input?.title.orEmpty()))
            job = scope.launch {
                val reloaded = reloadBody(target, null)
                if (!isCurrent(requestId)) return@launch
                session = null
                update(DistillState.Idle)
                if (reloaded) start() else update(DistillState.Error("最新の本文を再読込できませんでした。"))
            }
        } else {
            update(DistillState.Idle)
            start()
        }
    }

    fun dismissResult() {
        if (uiState.value.distillState is DistillState.RecoveryRequired) return
        session = null
        update(DistillState.Idle)
    }

    fun checkRecovery() {
        scope.launch {
            val assessment = withContext(ioDispatcher) { persistence.assessPendingRecovery() }
            when (assessment) {
                is DistillRecoveryAssessment.OriginalStillPresent,
                is DistillRecoveryAssessment.ExpectedOutputPresent -> {
                    val deleted = withContext(ioDispatcher) { persistence.discardResolvedRecovery(assessment) }
                    if (!deleted) {
                        update(
                            DistillState.RecoveryRequired(
                                DistillRecoveryKind.Corrupt,
                                "書き込み内容は安全ですが、古い復旧情報を削除できませんでした。",
                                canRestore = false,
                                canExport = false,
                                canKeepCurrent = true
                            )
                        )
                    }
                }
                DistillRecoveryAssessment.None -> Unit
                else -> showRecoveryAssessment(assessment)
            }
        }
    }

    private suspend fun showRecoveryAssessment(assessment: DistillRecoveryAssessment) {
        val state = when (assessment) {
            is DistillRecoveryAssessment.Diverged -> DistillState.RecoveryRequired(
                DistillRecoveryKind.Diverged,
                "保存が中断されたか、ファイルが外部で変更されました。現在のファイルを維持するか、保存前へ復元してください。",
                canRestore = true,
                canExport = true,
                canKeepCurrent = true
            )
            is DistillRecoveryAssessment.Inaccessible -> DistillState.RecoveryRequired(
                DistillRecoveryKind.Inaccessible,
                "元のノートへアクセスできません。保存前の本文を別ファイルへ書き出してください。",
                canRestore = false,
                canExport = true,
                canKeepCurrent = false
            )
            is DistillRecoveryAssessment.Corrupt -> DistillState.RecoveryRequired(
                DistillRecoveryKind.Corrupt,
                "復旧情報が破損しています。現在のファイルを確認してから復旧情報を破棄してください。",
                canRestore = false,
                canExport = false,
                canKeepCurrent = true
            )
            is DistillRecoveryAssessment.OriginalStillPresent,
            is DistillRecoveryAssessment.ExpectedOutputPresent -> {
                val deleted = withContext(ioDispatcher) { persistence.discardResolvedRecovery(assessment) }
                if (deleted) {
                    DistillState.RecoveryResolved("以前の保存処理を安全に確認しました。もう一度お試しください。")
                } else {
                    DistillState.RecoveryRequired(
                        DistillRecoveryKind.Corrupt,
                        "書き込み内容は安全ですが、古い復旧情報を削除できませんでした。",
                        canRestore = false,
                        canExport = false,
                        canKeepCurrent = true
                    )
                }
            }
            DistillRecoveryAssessment.None -> DistillState.Idle
        }
        update(state)
    }

    fun keepCurrentAndFinishRecovery() {
        scope.launch {
            val deleted = withContext(ioDispatcher) { persistence.discardPendingRecovery() }
            update(
                if (deleted) DistillState.RecoveryResolved("現在のファイルを維持しました。")
                else DistillState.Error("復旧情報を削除できませんでした。", canRetry = false)
            )
        }
    }

    fun restoreOriginal() {
        update(DistillState.Saving("復旧", verifying = true))
        scope.launch {
            when (val result = withContext(ioDispatcher) { persistence.restoreOriginal() }) {
                is DistillRecoveryResolutionResult.Restored -> {
                    reloadBody(result.targetUri, result.originalHash)
                    update(DistillState.RecoveryResolved("保存前の本文へ復元しました。"))
                }
                is DistillRecoveryResolutionResult.Failure -> showRecoveryAssessment(
                    withContext(ioDispatcher) { persistence.assessPendingRecovery() }
                )
                DistillRecoveryResolutionResult.NoValidRecord -> update(
                    DistillState.Error("復元できる本文がありません。", canRetry = false)
                )
            }
        }
    }

    fun exportOriginal(write: suspend (ByteArray) -> Unit) {
        scope.launch {
            try {
                val original = withContext(ioDispatcher) { persistence.pendingOriginal() }
                if (original == null) {
                    update(DistillState.Error("書き出せる元本文がありません。", canRetry = false))
                    return@launch
                }
                withContext(ioDispatcher) { write(original.bytes) }
                val deleted = withContext(ioDispatcher) { persistence.discardPendingRecovery() }
                update(
                    if (deleted) DistillState.RecoveryResolved("保存前の本文を書き出しました。")
                    else DistillState.Error("書き出し後に復旧情報を削除できませんでした。", canRetry = false)
                )
            } catch (error: Exception) {
                update(DistillState.Error(error.message ?: "元本文を書き出せませんでした。", canRetry = false))
            }
        }
    }

    fun cancelForNoteChange() {
        activeRequestId++
        job?.cancel()
        job = null
        pendingDownload = null
        session = null
        if (uiState.value.distillState !is DistillState.RecoveryRequired) update(DistillState.Idle)
    }

    private fun update(state: DistillState) {
        uiState.update { current -> current.copy(distillState = state) }
    }

    private fun isCurrent(requestId: Long): Boolean = requestId == activeRequestId

    private inline fun ifCurrent(requestId: Long, block: () -> Unit) {
        if (isCurrent(requestId)) block()
    }
}
