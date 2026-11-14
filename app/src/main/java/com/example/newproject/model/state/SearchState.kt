package com.example.newproject.model.state

import com.example.newproject.model.RelatedNote

// Explore の AI のモードの検索状態。
sealed class SearchState {
    object Idle : SearchState()
    object Loading : SearchState()
    data class Success(
        /** 結果の行。同じ名前のノートが並ぶときだけ場所を持つ（タイトルのモードと同じ形）。 */
        val results: List<TitleMatch>,
        /**
         * 出す結果をAIが選んだか。**選ばなかった理由は持たない。**
         *
         * 非対応・未取得・DL中・取得失敗、動いてIDを1件も返さなかった、のどれでも、この画面で起きることは
         * 「キーワード一致の結果が出る」で同じであり、ユーザーの次の行動も変わらない。
         */
        val isAiAssisted: Boolean = true
    ) : SearchState()
    data class Error(val message: String) : SearchState()
}

/** Explore の結果の1行。タイトルのモードと AI のモードで同じ形にする。 */
data class TitleMatch(
    val note: RelatedNote,
    /** 同じ名前のノートが他にあるときだけ入る、フォルダの場所。Vault の直下は空文字。 */
    val folderPath: String? = null
)

/** Explore のタイトルのモードの状態。打った言葉は `ExploreQueryStore` が持つ（日本語の変換中に入力が崩れないように）。 */
sealed class TitleSearchState {
    object Idle : TitleSearchState()
    /** 打ったが、Vault 全体の一覧がまだ届いていない。 */
    object Scanning : TitleSearchState()
    data class Matches(val matches: List<TitleMatch>) : TitleSearchState()
    /** 一覧を読めなかった。 */
    data class Error(val message: String) : TitleSearchState()
}
