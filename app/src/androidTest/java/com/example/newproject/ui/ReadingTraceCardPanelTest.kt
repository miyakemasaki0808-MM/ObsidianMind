package com.example.newproject.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.newproject.domain.ReunionSlot
import com.example.newproject.model.ReunionKind
import com.example.newproject.model.state.ReadingTraceCard
import com.example.newproject.ui.component.ReadingTraceCardPanel
import com.example.newproject.ui.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * **枠の中身・前置き・「続きから読む」が、実際に描かれることを固定する。**
 *
 * ## なぜ Compose 側なのか
 *
 * 枠の中身を決める純関数（`reunionSlot`）と前置き（`reunionLead`）はJVM側が押さえている。
 * **しかし「カードがその結果を描く」ことは純関数側からは一切観測できない。**
 * 値が正しくても、Composable が前置きを描かない・ボタンを出さない配線退行は
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
        show(finished(), ReunionSlot.Shown(QUESTION, ReunionKind.Question))

        composeRule.onNodeWithText("前回のあなたはこの問いで止まっていました").assertIsDisplayed()
        composeRule.onNodeWithText(QUESTION).assertIsDisplayed()
    }

    @Test
    fun 古い前提には確認をうながす前置きが出る() {
        show(finished(), ReunionSlot.Shown(STALE, ReunionKind.Staleness))

        composeRule.onNodeWithText("今も有効か確認したい箇所があります").assertIsDisplayed()
        composeRule.onNodeWithText(STALE).assertIsDisplayed()
    }

    /** 前後の要約には前置きを足さない。見出しの1文がどこまで読んだかを既に言っている。 */
    @Test
    fun 前後の要約は前置きなしで出る() {
        show(midway(), ReunionSlot.Shown(PASSAGE, ReunionKind.Passage))

        composeRule.onNodeWithText(PASSAGE).assertIsDisplayed()
        composeRule.onNodeWithText("前回のあなたはこの問いで止まっていました").assertDoesNotExist()
    }

    @Test
    fun 読了のノートではノートの要約の1文が前置きなしで出る() {
        show(finished(), ReunionSlot.Shown(SUMMARY, ReunionKind.Overview))

        composeRule.onNodeWithText(SUMMARY).assertIsDisplayed()
        composeRule.onNodeWithText("今も有効か確認したい箇所があります").assertDoesNotExist()
    }

    @Test
    fun 作っている間は待ちを出す() {
        show(midway(), ReunionSlot.Waiting)

        composeRule.onNodeWithText("前回読んだところを確かめています…").assertIsDisplayed()
    }

    @Test
    fun 枠が空でも見出しの1文と読んだは出る() {
        show(finished(), ReunionSlot.Hidden)

        composeRule.onNodeWithText("最後まで読んでいます", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("読んだ").assertIsDisplayed()
    }

    /** 「まだ考えたい」は撤去した。**枠に文が出ていてもボタンを出さない**（→ features/reunion_card.md）。 */
    @Test
    fun 枠に文が出ていてもまだ考えたいは出さない() {
        show(finished(), ReunionSlot.Shown(QUESTION, ReunionKind.Question))

        composeRule.onNodeWithText("まだ考えたい", substring = true).assertDoesNotExist()
    }

    /** 送り先があるのは途中まで読んだノートだけ。**AIを使わないので、枠が出ていなくても出す。** */
    @Test
    fun 続きから読むは送り先があれば枠が空でも出て押すと呼び出しへつながる() {
        var taps = 0
        show(midway(resumeBlockIndex = 4), ReunionSlot.Hidden, onResume = { taps++ })

        composeRule.onNodeWithText("続きから読む").performClick()

        assertEquals(1, taps)
    }

    @Test
    fun 送り先が無ければ続きから読むを出さない() {
        show(finished(), ReunionSlot.Shown(SUMMARY, ReunionKind.Overview))

        composeRule.onNodeWithText("続きから読む").assertDoesNotExist()
    }

    private fun show(
        card: ReadingTraceCard,
        slot: ReunionSlot,
        onResume: () -> Unit = {}
    ) {
        composeRule.setContent {
            AppTheme(darkTheme = false) {
                ReadingTraceCardPanel(
                    card = card,
                    slot = slot,
                    nowMillis = NOW,
                    onDismiss = {},
                    onResume = onResume
                )
            }
        }
    }

    private fun midway(resumeBlockIndex: Int? = 3) = ReadingTraceCard(
        visitCount = 3,
        lastVisitAtMillis = NOW,
        lastSectionTitle = "導入",
        lastProgressPercent = 40,
        resumeBlockIndex = resumeBlockIndex
    )

    private fun finished() = ReadingTraceCard(
        visitCount = 3,
        lastVisitAtMillis = NOW,
        lastSectionTitle = "まとめ",
        lastProgressPercent = 100
    )

    private companion object {
        const val NOW = 1_800_000_000_000L
        const val QUESTION = "この方式で本当に速くなるのだろうか。"
        const val STALE = "いまは v2.1 を使っている。"
        const val PASSAGE = "直前は導入の説明を読んでいた。この先は具体例に入る。"
        const val SUMMARY = "Kotlin の Flow の使い方をまとめたノート。"
    }
}
