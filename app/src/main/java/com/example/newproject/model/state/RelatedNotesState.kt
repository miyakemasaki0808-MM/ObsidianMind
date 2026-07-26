package com.example.newproject.model.state

import com.example.newproject.domain.AiRecommendationStatus
import com.example.newproject.domain.RelatedNote

sealed class RelatedNotesState {
    object Idle : RelatedNotesState()
    object Loading : RelatedNotesState()
    data class Success(
        val relatedNotes: List<RelatedNote>,
        val aiNotes: List<RelatedNote>,
        val aiStatus: AiRecommendationStatus = AiRecommendationStatus.Ready,
        val aiErrorMessage: String? = null
    ) : RelatedNotesState()
    data class Error(val message: String) : RelatedNotesState()
}
