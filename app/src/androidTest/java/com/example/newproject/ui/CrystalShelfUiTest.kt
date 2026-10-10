package com.example.newproject.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.newproject.model.Crystal
import com.example.newproject.model.CrystalSource
import com.example.newproject.model.DocumentRef
import com.example.newproject.model.NoteField
import com.example.newproject.model.NoteFieldClassification
import com.example.newproject.model.state.CrystalLogState
import com.example.newproject.ui.screen.CrystalListScreen
import com.example.newproject.ui.theme.AppTheme
import java.time.OffsetDateTime
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 結晶の一覧の宝石の棚（→ features/reflect_crystal.md 判断11）。
 * 並べ方と分野の決め方は JVM（`CrystalShelfTest`・`CrystalFieldsTest`）が持つので、ここは押したときの開閉と、
 * 宝石の読み上げに分野が届いていること（走査結果の参照から分野の索引を引く配線）だけを見る。
 */
@RunWith(AndroidJUnit4::class)
class CrystalShelfUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    /** 押す前は1文を出さず、押すと出て、もう一度押すとしまう。別の宝石を押すと中身が替わる。 */
    @Test
    fun 宝石を押すと1文が開き_もう一度押すと閉じ_別の宝石を押すと替わる() {
        setShelf()
        composeRule.onNodeWithText(TWO_FIELDS.sentence).assertDoesNotExist()

        gem(TWO_FIELDS, listOf(NoteField.Technical, NoteField.Reflection)).performClick()
        composeRule.onNodeWithText(TWO_FIELDS.sentence).assertIsDisplayed()
        composeRule.onNodeWithText(crystalDetailCaption(TWO_FIELDS.createdAt, listOf(NoteField.Technical, NoteField.Reflection))).assertIsDisplayed()

        gem(TWO_FIELDS, listOf(NoteField.Technical, NoteField.Reflection)).performClick()
        composeRule.onNodeWithText(TWO_FIELDS.sentence).assertDoesNotExist()

        gem(TWO_FIELDS, listOf(NoteField.Technical, NoteField.Reflection)).performClick()
        gem(UNKNOWN, emptyList()).performClick()
        composeRule.onNodeWithText(TWO_FIELDS.sentence).assertDoesNotExist()
        composeRule.onNodeWithText(UNKNOWN.sentence).assertIsDisplayed()
    }

    /** 開いた根拠のノート名に分野が添えられ、押すとそのノートを開く。走査に無いノートは押せない表示のまま。 */
    @Test
    fun 開いた根拠のノート名に分野が添えられ_押すとそのノートを開く() {
        val opened = mutableListOf<String>()
        setShelf(onOpenSource = { opened += it })

        gem(TWO_FIELDS, listOf(NoteField.Technical, NoteField.Reflection)).performClick()
        composeRule.onNodeWithText(crystalSourcesLine(TWO_FIELDS) + "  ▾").performClick()
        composeRule.onNodeWithText("設計メモ（技術・開発） ›").assertIsDisplayed().performClick()

        assertEquals(listOf("tech/設計メモ.md"), opened)
    }

    private fun gem(crystal: Crystal, fields: List<NoteField>) =
        composeRule.onNodeWithContentDescription(crystalGemDescription(crystal.createdAt, fields))

    private fun setShelf(onOpenSource: (String) -> Unit = {}) {
        val refs = mapOf(
            "tech/設計メモ.md" to DocumentRef("tech-ref"),
            "diary/日記.md" to DocumentRef("diary-ref")
        )
        composeRule.setContent {
            AppTheme(darkTheme = false) {
                CrystalListScreen(
                    state = CrystalLogState.Loaded(listOf(TWO_FIELDS, UNKNOWN)),
                    knownNotes = refs,
                    noteFields = mapOf(
                        DocumentRef("tech-ref") to NoteFieldClassification.Provisional(NoteField.Technical),
                        DocumentRef("diary-ref") to NoteFieldClassification.Provisional(NoteField.Reflection)
                    ),
                    onLoad = {},
                    onOpenSource = onOpenSource,
                    onBack = {}
                )
            }
        }
        composeRule.waitForIdle()
    }

    private companion object {
        fun at(date: String): Long = OffsetDateTime.parse("${date}T12:00:00+09:00").toInstant().toEpochMilli()

        /** 作ったときに読んでいたのは技術のノート。宝石は左が技術、右が内省。 */
        val TWO_FIELDS = Crystal(
            createdAt = at("2026-10-07"),
            sentence = "設計と日記は、決める前に一度書き出して距離を置いている。",
            sources = listOf(
                CrystalSource("tech/設計メモ.md", "設計メモ.md", "設計の断片"),
                CrystalSource("diary/日記.md", "日記.md", "日記の断片")
            ),
            fileName = "two.json"
        )

        /** 根拠がどちらも今の走査に無い（別の端末で作った等）。宝石は灰色で、分野なしと読み上げる。 */
        val UNKNOWN = Crystal(
            createdAt = at("2026-09-21"),
            sentence = "献立と執筆の手順は、下ごしらえに時間の大半を使っている。",
            sources = listOf(CrystalSource("gone/献立.md", "献立.md", "断片"), CrystalSource("gone/執筆.md", "執筆.md", "断片")),
            fileName = "unknown.json"
        )
    }
}
