package com.example.newproject.model

data class RelatedNote(
    val title: String,
    val ref: DocumentRef,
    val isWikilinked: Boolean,
    val lastModified: Long? = null
)

enum class AiRecommendationStatus {
    Ready,
    Unavailable,
    NeedsDownload,
    Error
}
