package com.example.newproject.model.state

data class DistillCandidateItem(
    val id: String,
    val text: String,
    val heading: String?,
    val positionLabel: String,
    val context: String?,
    val isSelected: Boolean = true
)

enum class DistillRecoveryKind { Diverged, Inaccessible, Corrupt }

sealed class DistillState {
    object Idle : DistillState()
    data class Analyzing(val sourceTitle: String) : DistillState()
    /**
     * 端末AIの状態をユーザーへ説明している。文言も導線も [AiStatusNotice] が持つ。
     *
     * **旧 `NeedsDownload` を畳んである。** 「DLが必要」「DL中」「非対応」「取得失敗」は
     * どれも同じ「押した機能が理由を説明する」場面で、違うのは添える導線だけだった。
     */
    data class AiNotice(val notice: AiStatusNotice) : DistillState()
    data class Downloading(val sourceTitle: String, val downloaded: Long, val total: Long) : DistillState()
    /** **ノート側の理由**で蒸留できない（本文が大きすぎる等）。端末AIの状態は [AiNotice] が持つ。 */
    data class Unavailable(val message: String) : DistillState()
    data class Candidates(
        val sourceTitle: String,
        val items: List<DistillCandidateItem>,
        val projectedBoldRatio: Double,
        val isWithinBoldLimit: Boolean,
        val isSingleSentenceException: Boolean = false
    ) : DistillState() {
        val selectedCount: Int get() = items.count { it.isSelected }
        val canSaveSelection: Boolean
            get() = selectedCount > 0 && (isWithinBoldLimit || isSingleSentenceException)
    }
    data class Saving(val sourceTitle: String, val verifying: Boolean = false) : DistillState()
    data class Saved(val sourceTitle: String, val sentenceCount: Int) : DistillState()
    data class Conflict(val message: String) : DistillState()
    data class RecoveryRequired(
        val kind: DistillRecoveryKind,
        val message: String,
        val canRestore: Boolean,
        val canExport: Boolean,
        val canKeepCurrent: Boolean
    ) : DistillState()
    data class RecoveryResolved(val message: String) : DistillState()
    data class Error(val message: String, val canRetry: Boolean = true) : DistillState()
}
