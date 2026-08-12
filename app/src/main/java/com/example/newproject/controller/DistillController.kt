package com.example.newproject.controller

import com.example.newproject.data.DistillPersistence
import com.example.newproject.data.DistillRecoveryAssessment
import com.example.newproject.data.DistillRecoveryResolutionResult
import com.example.newproject.data.DistillWriteRequest
import com.example.newproject.data.DistillWriteResult
import com.example.newproject.model.state.DistillCandidateItem
import com.example.newproject.model.state.DistillRecoveryKind
import com.example.newproject.model.state.DistillState
import com.example.newproject.model.state.NoteState
import com.example.newproject.model.DistillStateWriter
import com.example.newproject.ai.AiAvailability
import com.example.newproject.ai.AiClient
import com.example.newproject.ai.PromptBuilder
import com.example.newproject.model.DistillCandidate
import com.example.newproject.model.DistillLimits
import com.example.newproject.model.DistillSourceModel
import com.example.newproject.model.DistillTextRange
import com.example.newproject.domain.aiStatusNotice
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Reflectの蒸留分析・候補選択・保存・復旧を1つのrequestIdで直列化する。 */
internal class DistillController(
    private val scope: CoroutineScope,
    private val aiClient: AiClient,
    private val state: DistillStateWriter,
    private val currentNote: () -> NoteState.Success?,
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

    /**
     * 復旧確認の追跡Job。**分析（[job]・[activeRequestId]）とは寿命が違う**ので別に持つ。
     *
     * 復旧レコードはアプリ内部ストレージにある未解決1件で、ノートにもVaultにも紐づかない。
     * したがってノート切替では止めてはならず（[cancelForNoteChange] は触らない）、
     * 取り下げの契機は「次の [checkRecovery] が始まったとき」だけである。
     * 分析側の世代へ相乗りさせると、確認中にユーザーが蒸留を始めただけで
     * データ安全性の警告が捨てられてしまう。
     */
    private var recoveryJob: Job? = null

    fun start() {
        if (state.current is DistillState.Analyzing ||
            state.current is DistillState.Downloading ||
            state.current is DistillState.Saving
        ) return

        val note = currentNote()
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
                when (val availability = aiClient.checkAvailability()) {
                    AiAvailability.Ready -> analyze(requestId, input)
                    AiAvailability.NeedsDownload -> ifCurrent(requestId) {
                        pendingDownload = input
                        updateAiNotice(availability)
                    }
                    // **DL中は待つだけ。** `downloadModel()` を呼んで合流はできない
                    // （→ AiAvailability.Downloading）。CTAも出さないので、
                    // ユーザーは完了後にもう一度押すことになる。
                    // 非対応は再試行を出さず、一時的な不可は出す。畳むとどちらかが必ず誤る。
                    AiAvailability.Downloading,
                    AiAvailability.Unsupported,
                    is AiAvailability.TemporarilyUnavailable -> ifCurrent(requestId) {
                        pendingDownload = null
                        updateAiNotice(availability)
                    }
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
        job = scope.launch { collectDownload(requestId, input) }
    }

    /**
     * DLの進捗を購読し、完了したら分析へ進む。
     *
     * **呼ぶのは [downloadModelAndResume] だけ**、つまり
     * [AiAvailability.NeedsDownload]（＝`DOWNLOADABLE`）を見てユーザーが承諾した後に限る。
     * 既にDL中の状態からここへ来てはいけない理由は [AiAvailability.Downloading] にある。
     */
    private suspend fun collectDownload(requestId: Long, input: AnalysisInput) {
        update(DistillState.Downloading(input.title, downloaded = -1L, total = 0L))
        try {
            aiClient.downloadModel().collect { status ->
                if (!isCurrent(requestId)) return@collect
                when (status) {
                    is DownloadStatus.DownloadStarted -> update(
                        DistillState.Downloading(input.title, 0L, status.bytesToDownload)
                    )
                    is DownloadStatus.DownloadProgress -> {
                        val total = (state.current as? DistillState.Downloading)?.total ?: 0L
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

    /**
     * 端末AIの状態を、蒸留パネルが描ける形へ移す。
     *
     * `aiStatusNotice()` が全域関数なので、状態が増えても取りこぼしはここで起きない。
     * [AiAvailability.Ready] だけが null を返すが、その枝はここへ来ない。
     */
    private fun updateAiNotice(availability: AiAvailability) {
        val notice = aiStatusNotice(availability, DISTILL_FEATURE_LABEL) ?: return
        update(DistillState.AiNotice(notice))
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
        val current = state.current as? DistillState.Candidates ?: return
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
        val current = state.current as? DistillState.Candidates ?: return
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
        if (state.current is DistillState.Conflict && target != null) {
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
        if (state.current is DistillState.RecoveryRequired) return
        session = null
        update(DistillState.Idle)
    }

    /**
     * 起動時とVault切替時に、中断された保存の痕跡（未解決の復旧レコード）を確認する。
     *
     * SAF I/O を伴うので遅れることがあり、その間にユーザーが蒸留を始められる。
     * そのため結果の反映は [applyRecoveryResult] を通し、**表示の直前に分析世代を
     * 無効化する**。世代を進めないと、復旧警告を出した直後に走行中の分析が候補を返して
     * 上書きしてしまう（保存層が未解決レコードを再確認するので危険な書き込みは止まるが、
     * 警告が消えて不要なAI実行も走る）。
     */
    fun checkRecovery() {
        // 走行中の確認を先に取り下げる。これが「取り下げた確認の結果が届かない」唯一の仕組み。
        recoveryJob?.cancel()
        recoveryJob = scope.launch {
            val assessment = withContext(ioDispatcher) { persistence.assessPendingRecovery() }
            when (assessment) {
                is DistillRecoveryAssessment.OriginalStillPresent,
                is DistillRecoveryAssessment.ExpectedOutputPresent -> {
                    val deleted = withContext(ioDispatcher) { persistence.discardResolvedRecovery(assessment) }
                    // 解決済みなら黙って捨てる（起動のたびに知らせる意味がない）。
                    // 削除に失敗したときだけ、確認を促すために表示する。
                    if (!deleted) applyRecoveryResult(resolvedButUndeletableState())
                }
                DistillRecoveryAssessment.None -> Unit
                else -> unresolvedRecoveryState(assessment)?.let { applyRecoveryResult(it) }
            }
        }
    }

    /**
     * 復旧の判定結果を画面へ反映する。分析の無効化をここ1箇所へ集約する
     * （呼び出し側へ散らすと、テストで検出できない等価な分岐が増える）。
     *
     * 取り下げられた確認の結果が届かないのは [recoveryJob] のキャンセルによる。
     * `recoveryRequestId` との照合は書いた上で1行消す変異確認を行ったが
     * **どのテストも落ちなかった** — `recoveryRequestId` を進めるのは [checkRecovery] だけで、
     * そこでは必ず先にJobをキャンセルするため照合へ到達する経路が無い。
     * `NoteSectionController` と同じ判断で冗長として削除した。
     * **再び足す場合は、先に「照合を消すと落ちるテスト」を書けることを確かめること。**
     */
    private fun applyRecoveryResult(next: DistillState) {
        // 走行中の分析を無効化してから表示する。順序が要点で、先に表示すると
        // 直後に返ってきた候補が同じ世代のまま復旧表示を上書きする。
        activeRequestId++
        job?.cancel()
        job = null
        pendingDownload = null
        session = null
        update(next)
    }

    private fun resolvedButUndeletableState() = DistillState.RecoveryRequired(
        DistillRecoveryKind.Corrupt,
        "書き込み内容は安全ですが、古い復旧情報を削除できませんでした。",
        canRestore = false,
        canExport = false,
        canKeepCurrent = true
    )

    /** 中断・破損の3種を表示状態へ写す（I/Oなし）。解決済みの2種はここでは扱わないので null。 */
    private fun unresolvedRecoveryState(assessment: DistillRecoveryAssessment): DistillState? =
        when (assessment) {
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
            else -> null
        }

    /**
     * ユーザー操作（復元の失敗後）から呼ぶ判定表示。起動時の [checkRecovery] と違い、
     * 解決済みだったことを黙らせずに知らせる（ユーザーが自分で操作した結果のため）。
     */
    private suspend fun showRecoveryAssessment(assessment: DistillRecoveryAssessment) {
        val state = unresolvedRecoveryState(assessment) ?: when (assessment) {
            is DistillRecoveryAssessment.OriginalStillPresent,
            is DistillRecoveryAssessment.ExpectedOutputPresent -> {
                val deleted = withContext(ioDispatcher) { persistence.discardResolvedRecovery(assessment) }
                if (deleted) {
                    DistillState.RecoveryResolved("以前の保存処理を安全に確認しました。もう一度お試しください。")
                } else {
                    resolvedButUndeletableState()
                }
            }
            else -> DistillState.Idle
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
        if (state.current !is DistillState.RecoveryRequired) update(DistillState.Idle)
    }

    private fun update(next: DistillState) {
        state.update { next }
    }

    private fun isCurrent(requestId: Long): Boolean = requestId == activeRequestId

    private inline fun ifCurrent(requestId: Long, block: () -> Unit) {
        if (isCurrent(requestId)) block()
    }

    private companion object {
        /** 説明文へ埋め込む機能名（「この端末では**蒸留**を利用できません。」）。 */
        const val DISTILL_FEATURE_LABEL = "蒸留"
    }
}
