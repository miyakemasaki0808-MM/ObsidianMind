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
    data class NeedsDownload(val sourceTitle: String) : DistillState()
    data class Downloading(val sourceTitle: String, val downloaded: Long, val total: Long) : DistillState()
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
