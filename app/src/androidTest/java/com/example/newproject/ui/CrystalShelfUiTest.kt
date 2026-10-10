package com.example.newproject.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
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
 * 宝石の読み上げに分野が届いていること（走査結果の参照から分野の索引を引く配線）、
 * 画面の下端で開いた中身を画面の中へ送ること（一覧が画面の外の項目を組まないのは実際の一覧でしか起きない）を見る。
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

    // ── 画面の下端（→ reflect_crystal.md 判断11）────────────────────────────

    /**
     * **画面の下端の宝石を押すと、追加の払いなしで1文が見える。** 宝石の段の下端を画面の外に置き、見えている宝石の上側を押す。
     * 中身は画面の外にでき、一覧に組まれない。送る処理を中身の項目に持たせていたときは、1文が画面の外のままだった。
     */
    @Test
    fun 画面の下端の宝石を押すと_追加の払いなしで1文が見える() {
        setMany()
        pressAtBottomEdge(MANY[9])
        composeRule.onNodeWithText(MANY[9].sentence).assertIsDisplayed()
    }

    /** 同じ境界で別の宝石へ替えても1文が見え、中身は1件だけ。もう一度押すと閉じる。 */
    @Test
    fun 画面の下端で別の宝石へ替えても1文が見え_中身は1件だけ_もう一度押すと閉じる() {
        setMany()
        pressAtBottomEdge(MANY[9])
        pressAtBottomEdge(MANY[21])

        composeRule.onNodeWithText(MANY[21].sentence).assertIsDisplayed()
        composeRule.onNodeWithText(MANY[9].sentence).assertDoesNotExist()

        manyGem(MANY[21]).performClick()
        composeRule.onNodeWithText(MANY[21].sentence).assertDoesNotExist()
    }

    /**
     * **開いた中身から手で離れても、自動で送り直さない。** 一覧を画面1つ分だけ上へ戻すと中身は画面の外へ出て、そのまま。
     * 手で元へ戻すと、宝石は離れる前と同じ位置にある（中身の項目が組み直されただけで送り直していない）。
     */
    @Test
    fun 開いた中身から手で離れて戻っても_自動で送り直さない() {
        setMany()
        pressAtBottomEdge(MANY[29])
        composeRule.onNodeWithText(MANY[29].sentence).assertIsDisplayed()
        val before = manyGem(MANY[29]).fetchSemanticsNode().positionInRoot.y
        val page = list().fetchSemanticsNode().size.height.toFloat()

        scrollBy(-page)
        composeRule.onNodeWithText(MANY[29].sentence).assertIsNotDisplayed()

        scrollBy(page)
        assertEquals(before, manyGem(MANY[29]).fetchSemanticsNode().positionInRoot.y, 1f)
    }

    /** 窓の幅が変わって1段の数が変わっても、開いた結晶は替わらない。 */
    @Test
    fun 窓の幅で1段の数が変わっても_開いた結晶は替わらない() {
        var width by mutableStateOf(400.dp)
        setMany { width }
        pressAtBottomEdge(MANY[9])

        width = 720.dp
        composeRule.waitForIdle()

        composeRule.onNodeWithText(MANY[9].sentence).assertExists()
        listOf(MANY[8], MANY[10]).forEach { composeRule.onNodeWithText(it.sentence).assertDoesNotExist() }
    }

    /**
     * [crystal] の宝石の段の下端を画面の外に置き、宝石の上側（見えている部分）を実際のタッチで押す。
     * 宝石の枠の下3割が画面の外に出るので、その直下にできる中身は一覧に組まれない。
     */
    private fun pressAtBottomEdge(crystal: Crystal) {
        list().performScrollToNode(hasContentDescription(manyDescription(crystal)))
        composeRule.waitForIdle()
        val listBottom = list().fetchSemanticsNode().let { it.positionInRoot.y + it.size.height }
        val cell = manyGem(crystal).fetchSemanticsNode()
        val cellBottom = cell.positionInRoot.y + cell.size.height
        scrollBy(cellBottom - listBottom - cell.size.height * 0.3f)
        manyGem(crystal).performTouchInput { click(Offset(centerX, height * 0.3f)) }
        composeRule.waitForIdle()
    }

    private fun list() = composeRule.onNode(hasScrollToIndexAction())

    private fun scrollBy(pixels: Float) {
        list().performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, pixels) }
        composeRule.waitForIdle()
    }

    private fun manyDescription(crystal: Crystal) = crystalGemDescription(crystal.createdAt, listOf(NoteField.Technical))

    private fun manyGem(crystal: Crystal) = composeRule.onNodeWithContentDescription(manyDescription(crystal))

    /** 61件（10月の31件と9月の30件）を、高さを絞った箱に並べる。根拠はどれも技術のノート。 */
    private fun setMany(width: () -> Dp = { 400.dp }) {
        composeRule.setContent {
            AppTheme(darkTheme = false) {
                Box(modifier = Modifier.requiredSize(width = width(), height = 560.dp)) {
                    CrystalListScreen(
                        state = CrystalLogState.Loaded(MANY),
                        knownNotes = mapOf("tech/設計メモ.md" to DocumentRef("tech-ref")),
                        noteFields = mapOf(DocumentRef("tech-ref") to NoteFieldClassification.Provisional(NoteField.Technical)),
                        onLoad = {},
                        onOpenSource = {},
                        onBack = {}
                    )
                }
            }
        }
        composeRule.waitForIdle()
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
        /** 新しい順。10月31日から10月1日、9月30日から9月1日。 */
        val MANY: List<Crystal> = (31 downTo 1).map { "2026-10-%02d".format(it) }.plus((30 downTo 1).map { "2026-09-%02d".format(it) })
            .map { date ->
                Crystal(
                    createdAt = at(date),
                    sentence = "$date の結晶の1文。",
                    sources = listOf(CrystalSource("tech/設計メモ.md", "設計メモ.md", "断片")),
                    fileName = "$date.json"
                )
            }

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
