package com.example.newproject.model.state

enum class ChatRole { User, Ai }

data class ChatMessage(val role: ChatRole, val text: String)

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
     * **要約**の生成が失敗したときの文言。**端末AIの状態は [aiNotice] が持つ。**
     *
     * **回答側の失敗は [answerError] へ入れる。** 1つの欄で兼ねていたころは、
     * 要約が出ている状態で回答が失敗すると**要約の表示が優先されて文言が出ず、
     * 未回答の質問だけが残った**（タイムアウト・出力打ち切りがこれに当たる）。
     */
    val error: String? = null,
    /**
     * **回答**の生成が失敗したときの文言。Q&Aログの直後に出す。
     *
     * 置き場所を [error] と分けているのは、要約エリアと回答エリアで**出す位置が違う**ため。
     * どちらも再試行できる（`retryAi()` が未回答の質問を作り直す）。
     */
    val answerError: String? = null,
    /**
     * 端末AIが使えないことの説明。
     *
     * **[error] へ文字列だけ入れない。** 導線（[AiNoticeAction]）を捨てると
     * 一時的に使えないだけの場合に再試行できず、赤いエラー表示にもなってしまう
     * （状態の説明は失敗ではないので、色で区別しない）。
     */
    val aiNotice: AiStatusNotice? = null
)
