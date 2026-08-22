package com.example.newproject.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.newproject.model.ReunionKind
import com.example.newproject.model.state.ReadingTraceCard
import com.example.newproject.ui.component.ReadingTraceCardPanel
import com.example.newproject.ui.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * **種別ごとの前置きと「まだ考えたい」が、実際に描かれることを固定する。**
 *
 * ## なぜ Compose 側なのか
 *
 * 前置きを決める純関数（`reunionLead`）はJVM側の `ReunionLeadTest` が押さえている。
 * **しかし「カードがその関数を呼ぶ」ことは純関数側からは一切観測できない。**
 * 種別の値が正しくても、Composable が前置きを描かない・ボタンを出さない配線退行は
 * そこを通り抜ける（→ `QuizActionSectionTest` が同じ理由で置かれている）。
 *
 * **APKが組み立つことは、描画の受け入れ条件を代替しない。**
 */
@RunWith(AndroidJUnit4::class)
class ReadingTraceCardPanelTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun 当時の問いには専用の前置きが出る() {
        show(card(kind = ReunionKind.Question, summary = QUESTION))

        composeRule.onNodeWithText("前回のあなたはこの問いで止まっていました").assertIsDisplayed()
        composeRule.onNodeWithText(QUESTION).assertIsDisplayed()
    }

    @Test
    fun 古い前提には確認をうながす前置きが出る() {
        show(card(kind = ReunionKind.Staleness, summary = STALE))

        composeRule.onNodeWithText("今も有効か確認したい箇所があります").assertIsDisplayed()
        composeRule.onNodeWithText(STALE).assertIsDisplayed()
    }

    /** 俯瞰要約は現行の見え方のまま。**前置きを足さない。** */
    @Test
    fun 俯瞰要約には前置きを足さない() {
        show(card(kind = ReunionKind.Overview, summary = OVERVIEW))

        composeRule.onNodeWithText(OVERVIEW).assertIsDisplayed()
        composeRule.onNodeWithText("前回のあなたはこの問いで止まっていました").assertDoesNotExist()
        composeRule.onNodeWithText("今も有効か確認したい箇所があります").assertDoesNotExist()
    }

    @Test
    fun 印が付いていれば印の前置きになり文言も変わる() {
        show(card(kind = ReunionKind.Question, summary = QUESTION, isMarked = true))

        composeRule.onNodeWithText("前回「まだ考えたい」と印を付けています").assertIsDisplayed()
        composeRule.onNodeWithText("✓ まだ考えたい").assertIsDisplayed()
    }

    /** **枠が空なら押せない。** 控えるものが無いまま押せると「中身の無い印」ができる。 */
    @Test
    fun 枠が空なら印のボタンを出さない() {
        show(card(kind = null, summary = null))

        composeRule.onNodeWithText("まだ考えたい").assertDoesNotExist()
        composeRule.onNodeWithText("✓ まだ考えたい").assertDoesNotExist()
        // 見出しの1文だけで意味が通る状態は保つ。
        composeRule.onNodeWithText("読んだ").assertIsDisplayed()
    }

    @Test
    fun 印のボタンは押すと呼び出しへつながる() {
        var taps = 0
        show(card(kind = ReunionKind.Question, summary = QUESTION), onToggleMark = { taps++ })

        composeRule.onNodeWithText("まだ考えたい").performClick()

        assertEquals(1, taps)
    }

    private fun show(card: ReadingTraceCard, onToggleMark: () -> Unit = {}) {
        composeRule.setContent {
            AppTheme(darkTheme = false) {
                ReadingTraceCardPanel(
                    card = card,
                    nowMillis = NOW,
                    onDismiss = {},
                    onToggleMark = onToggleMark
                )
            }
        }
    }

    private fun card(
        kind: ReunionKind?,
        summary: String?,
        isMarked: Boolean = false
    ) = ReadingTraceCard(
        visitCount = 3,
        lastVisitAtMillis = NOW,
        lastSectionTitle = "導入",
        lastProgressPercent = 40,
        aiSummary = summary,
        aiSummaryKind = kind,
        isMarked = isMarked
    )

    private companion object {
        const val NOW = 1_800_000_000_000L
        const val QUESTION = "この方式で本当に速くなるのだろうか。"
        const val STALE = "いまは v2.1 を使っている。"
        const val OVERVIEW = "これまで3回開いて、いずれも前半で止まっています。"
    }
}
