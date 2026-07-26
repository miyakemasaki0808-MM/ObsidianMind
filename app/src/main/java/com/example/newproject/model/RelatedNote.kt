package com.example.newproject.model

import android.net.Uri

data class RelatedNote(
    val title: String,
    val uri: Uri,
    val isWikilinked: Boolean,
    val lastModified: Long? = null
)

enum class AiRecommendationStatus {
    Ready,
    Unavailable,
    NeedsDownload,
    Error
}
