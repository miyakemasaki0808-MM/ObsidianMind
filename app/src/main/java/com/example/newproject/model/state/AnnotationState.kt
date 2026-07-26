package com.example.newproject.model.state

import android.net.Uri
import com.example.newproject.model.NoteFile

sealed class AnnotationState {
    object Idle : AnnotationState()
    data class Loading(val sourceTitle: String) : AnnotationState()
    data class Success(
        val sourceTitle: String,
        val savedUri: Uri,
        val fileName: String,
        val content: String,
        val isViewed: Boolean = false
    ) : AnnotationState()
    data class Error(
        val message: String,
        val sourceTitle: String? = null,
        val isViewed: Boolean = false
    ) : AnnotationState()
}

internal fun AnnotationState.toEventKey(): String? = when (this) {
    is AnnotationState.Idle -> null
    is AnnotationState.Loading -> "loading:$sourceTitle"
    is AnnotationState.Success -> "success:$savedUri:$isViewed"
    is AnnotationState.Error -> "error:$sourceTitle:$message:$isViewed"
}

sealed class AnnotationListState {
    object Idle : AnnotationListState()
    object Loading : AnnotationListState()
    /**
     * @param deleteFailureCount 直前の削除操作で失敗した件数。0 なら表示しない。
     *   失敗を [Error] に倒すと一覧ごと消えて再削除できなくなるため、
     *   一覧は保ったまま件数だけ添える。
     */
    data class Success(
        val files: List<NoteFile>,
        val deleteFailureCount: Int = 0
    ) : AnnotationListState()
    data class Error(val message: String) : AnnotationListState()
}
