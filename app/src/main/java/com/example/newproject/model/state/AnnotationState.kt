package com.example.newproject.model.state

import com.example.newproject.model.NoteFile

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
