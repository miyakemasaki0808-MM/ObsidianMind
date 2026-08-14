package com.example.newproject.model.state

enum class ChatRole { User, Ai }

data class ChatMessage(val role: ChatRole, val text: String)

/**
 * セクションチャットで「いま出せない」ことの説明。
 *
 * **失敗と状態を型で分ける。** 前者は実際に落ちたもの（赤で出す）、後者は端末AIの状態で
 * まだ何も失敗していない（通常色で出す）。文字列1本で兼ねると、色も導線も選べない。
 */
sealed class SectionChatProblem {
    /** 生成そのものが失敗した（タイムアウト・出力打ち切り・例外）。 */
    data class GenerationFailed(val message: String) : SectionChatProblem()

    /** 端末AIが使えない。文言と導線は [AiStatusNotice] が持つ。 */
    data class AiStatus(val notice: AiStatusNotice) : SectionChatProblem()
}

// セクション単位のAIチャット。null のときシートは閉じている。
data class SectionChatState(
    val sectionTitle: String,
    val sectionContext: String,     // LLM に渡す本文（表示はしない）
    val summary: String? = null,
    val isSummaryLoading: Boolean = false,
    val suggestions: List<String> = emptyList(),
    val messages: List<ChatMessage> = emptyList(),  // 質問タップによる Q&A ログ
    val isGenerating: Boolean = false,              // 質問の回答生成中
    /**
     * 質問候補を生成中か。
     *
     * **`suggestions.isEmpty()` から推測しない。** 推測していたころは、端末AIが使えず
     * 候補生成を**始めてすらいない**場合や、例外・正常な0件で終わった場合にも
     * 「質問候補を準備中…」が永久に残った。**走行状態は走行フラグでしか分からない。**
     */
    val isSuggestionsLoading: Boolean = false,
    /**
     * **要約**が出せなかった理由。要約エリアへ出す。
     *
     * **回答側と別の欄にしてある。** 1つで兼ねていたころは
     * ①要約があると回答の失敗が表示に負けて消え、
     * ②2つが同時に起きたとき「再試行がどちらを指すか」を決められなかった。
     * **欄が分かれていれば、押されたボタンの位置が対象を決める。**
     */
    val summaryProblem: SectionChatProblem? = null,
    /** **回答**が出せなかった理由。Q&Aログの直後へ出す。 */
    val answerProblem: SectionChatProblem? = null
)

/** 質問候補の欄に何を出すか。 */
enum class SuggestionsDisplay { Loading, Ready, None }

/**
 * 質問候補の表示を決める。
 *
 * 要約が走っている間は候補もこれから来るので「準備中」でよい。
 * **終わったのに空**なら、それは終端であって進行中ではない。
 */
internal fun SectionChatState.suggestionsDisplay(): SuggestionsDisplay = when {
    isSummaryLoading || isSuggestionsLoading -> SuggestionsDisplay.Loading
    suggestions.isNotEmpty() -> SuggestionsDisplay.Ready
    else -> SuggestionsDisplay.None
}
