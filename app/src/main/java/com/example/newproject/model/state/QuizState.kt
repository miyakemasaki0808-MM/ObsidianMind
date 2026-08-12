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

    /**
     * 端末AIの状態を説明している。
     *
     * **[Error] へ畳まない。** 畳んでいたころは非対応端末で
     * `エラー: Q&Aはこの端末では利用できません。` と出たうえに再試行導線が付き、
     * **何度押しても直らないものを押させていた。**
     *
     * **`isViewed` を持たない。** 押した直後に答えが確定するので、
     * 「まだ見ていない結果」として追い続ける対象ではない。
     */
    data class AiNotice(val notice: AiStatusNotice, val sourceTitle: String) : QuizState()
}

/**
 * セクションチャットの「クイズ」ボタンを押させてよいか。
 *
 * 生成中は二重に走らせないため無効。説明が出ているときは
 * **[AiStatusNotice.canTryAgainLater] だけで決める。**
 *
 * **`action == Retry` で判定しない。** DL実行中の説明は「押しても始まるものが無い」ので
 * CTAを持たないが、入口まで閉じると**DL完了後に押し直せず、同じノートでクイズが
 * 二度と作れなくなる**（実際にその不具合を作った）。閉じてよいのは恒久非対応だけ。
 */
internal fun QuizState.isQuizActionEnabled(): Boolean = when (this) {
    is QuizState.Loading -> false
    is QuizState.AiNotice -> notice.canTryAgainLater
    else -> true
}

// Snackbar通知の発火判定キー。値が変わったときだけ通知を出し直す。
// nullはIdle（通知対象なし）を表す。
internal fun QuizState.toEventKey(): String? = when (this) {
    is QuizState.Idle -> null
    is QuizState.Loading -> "loading:$sourceTitle:$format"
    is QuizState.Success -> "success:$sourceTitle:${cards.hashCode()}:$isViewed"
    is QuizState.Error -> "error:$sourceTitle:$message:$isViewed"
    is QuizState.AiNotice -> "notice:$sourceTitle:${notice.message}"
}
