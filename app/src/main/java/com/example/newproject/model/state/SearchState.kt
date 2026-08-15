package com.example.newproject.model.state

import com.example.newproject.model.RelatedNote

// AIピッカー（さがすタブ）の検索状態。キーワード/ランダム両モードで共有する。
sealed class SearchState {
    object Idle : SearchState()
    object Loading : SearchState()
    data class Success(
        val results: List<RelatedNote>,
        /**
         * AIが選定に関与したか。**関与しなかった理由は持たない。**
         *
         * 非対応・未取得・DL中・取得失敗のどれでも、この画面で起きることは
         * 「キーワード一致の結果が出る」で同じであり、ユーザーの次の行動も変わらない。
         */
        val isAiAssisted: Boolean = true
    ) : SearchState()
    data class Error(val message: String) : SearchState()
}
