package com.example.newproject.model.state

enum class QuizFormat(val displayName: String) {
    TrueFalse("○×問題"),
    ThreeChoice("3択問題"),
    FourChoice("4択問題")
}

data class QuizCard(
    val question: String,
    val choices: List<String>,
    val correctIndex: Int,
    val explanation: String = "",
    val format: QuizFormat = QuizFormat.FourChoice
)

sealed class QuizState {
    object Idle : QuizState()
    data class Loading(val sourceTitle: String, val format: QuizFormat = QuizFormat.FourChoice) : QuizState()
    data class Success(
        val sourceTitle: String,
        val cards: List<QuizCard>,
        val isViewed: Boolean = false
    ) : QuizState()
    data class Error(
        val message: String,
        val sourceTitle: String,
        val isViewed: Boolean = false
    ) : QuizState()
}

// Snackbar通知の発火判定キー。値が変わったときだけ通知を出し直す。
// nullはIdle（通知対象なし）を表す。
internal fun QuizState.toEventKey(): String? = when (this) {
    is QuizState.Idle -> null
    is QuizState.Loading -> "loading:$sourceTitle:$format"
    is QuizState.Success -> "success:$sourceTitle:${cards.hashCode()}:$isViewed"
    is QuizState.Error -> "error:$sourceTitle:$message:$isViewed"
}
