package com.example.newproject.model.state

import com.example.newproject.model.AiRecommendationStatus
import com.example.newproject.model.RelatedNote

// AIピッカー（さがすタブ）の検索状態。キーワード/ランダム両モードで共有する。
sealed class SearchState {
    object Idle : SearchState()
    object Loading : SearchState()
    data class Success(
        val results: List<RelatedNote>,
        val aiStatus: AiRecommendationStatus = AiRecommendationStatus.Ready
    ) : SearchState()
    data class Error(val message: String) : SearchState()
}
