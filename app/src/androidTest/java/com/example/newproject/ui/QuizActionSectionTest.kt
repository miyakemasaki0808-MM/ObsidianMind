package com.example.newproject.ui

import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.newproject.ai.AiAvailability
import com.example.newproject.domain.aiStatusNotice
import com.example.newproject.model.state.QuizState
import com.example.newproject.ui.screen.QuizActionSection
import com.example.newproject.ui.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * **クイズが使えない理由が、押した場所に出ることを固定する。**
 *
 * ## なぜ Compose 側なのか
 *
 * 純関数（`quizNotice` / `showsQuizAction` / `isQuizActionEnabled`）はJVM側の
 * `SectionChatCombinationTest` が押さえている。**しかし「シートがその関数を呼ぶ」ことは
 * 純関数側からは一切観測できない。** 実機で見つかった欠陥はまさにそこにあった —
 * 状態はすべて正しく、シートがそれをボタンのラベルへ潰していた。
 * 恒久非対応ではボタンも無効になるため、理由を描く `QuizScreen` へ到達できなかった。
 *
 * **到達できない画面の説明を、説明した根拠にしない。** ここが見るのは描画結果だけである。
 *
 * ## `SectionChatSheet` ごと開かない理由
 *
 * `ModalBottomSheet` の開閉アニメーションを待つ必要があり、検査したいものと無関係に
 * 落ちうる。クイズ欄は [QuizActionSection] として切り出してあるので直接描ける。
 */
@RunWith(AndroidJUnit4::class)
class QuizActionSectionTest {

    @get:Rule
    val composeRule = createComposeRule()

    /**
     * 恒久非対応: 理由だけが出る。
     *
     * **要約側の失敗表示は描いていない。** クイズの理由がそれに相乗りしていれば、
     * ここで何も表示されない。
     */
    @Test
    fun 恒久非対応では理由が出て再試行は出ない() {
        val notice = requireNotNull(aiStatusNotice(AiAvailability.Unsupported, "クイズ"))
        var taps = 0

        composeRule.setContent {
            AppTheme(darkTheme = false) {
                QuizActionSection(
                    quizState = QuizState.AiNotice(notice, "対象ノート.md"),
                    onQuizTap = { taps++ }
                )
            }
        }

        composeRule.onNodeWithText(notice.message).assertIsDisplayed()
        composeRule.onNodeWithText("↻ クイズを再試行").assertDoesNotExist()
        composeRule.onNodeWithText("再試行").assertDoesNotExist()
        composeRule.onNodeWithText("クイズを使えません").assertDoesNotExist()
        assertEquals(0, taps)
    }

    /** 一時的な不可: 同じ場所に理由と、押し直せる導線の両方が出る。 */
    @Test
    fun 一時的に使えないなら理由と再試行が同じ場所に出る() {
        val notice = requireNotNull(
            aiStatusNotice(
                AiAvailability.TemporarilyUnavailable(IllegalStateException("AICore not bound")),
                "クイズ"
            )
        )
        var taps = 0

        composeRule.setContent {
            AppTheme(darkTheme = false) {
                QuizActionSection(
                    quizState = QuizState.AiNotice(notice, "対象ノート.md"),
                    onQuizTap = { taps++ }
                )
            }
        }

        composeRule.onNodeWithText(notice.message).assertIsDisplayed()
        composeRule.onNodeWithText("↻ クイズを再試行")
            .assertIsDisplayed()
            .assertIsEnabled()
            .assertHasClickAction()
            .performClick()

        assertEquals("押し直せること", 1, taps)
    }
}
