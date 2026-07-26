package com.example.newproject.model.state

sealed class SummaryState {
    object Idle : SummaryState()
    object Loading : SummaryState()
    data class Success(val summary: String) : SummaryState()
    // DL進捗: downloaded=-1 は「開始待ち」、total=0 はサイズ不明
    data class Downloading(val downloaded: Long, val total: Long) : SummaryState()
    object AiUnavailable : SummaryState()
    data class Error(val message: String) : SummaryState()
}
