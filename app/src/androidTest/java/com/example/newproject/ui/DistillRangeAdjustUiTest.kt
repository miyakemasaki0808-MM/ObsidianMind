package com.example.newproject.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.compose.ui.geometry.Offset
import com.example.newproject.model.NoteUiState
import com.example.newproject.model.state.DistillCandidateItem
import com.example.newproject.model.state.DistillRangeEdge
import com.example.newproject.model.state.DistillRangeEdgeMove
import com.example.newproject.model.state.DistillRangePreset
import com.example.newproject.model.state.DistillState
import com.example.newproject.model.state.NoteState
import com.example.newproject.ui.screen.AiTab
import com.example.newproject.ui.screen.DistillRangeSheetContent
import com.example.newproject.ui.screen.label
import com.example.newproject.ui.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 太字範囲の調整で**増えるのはUI操作のほう**なので、描画と入力の側を固定する。
 *
 * ## ここへ何を書くか
 *
 * 3段の導出・重なり解消・確定範囲の寿命はすべてJVM側
 * （`DistillRangeAdjustTest` / `DistillControllerTest`）が押さえている。
 * **純関数からは観測できないもの**だけをここへ置く。
 *
 * - 行タップとチェックボックスのタップが**別の操作へ届く**こと
 * - 確定範囲が**太字として描かれ**、いまの段が押した形で出ること
 * - 重なり解消の告知が**シート内の1行とカードの印の両方**に出ること
 * - 自由範囲のつまみと微調整が**入力として届く**こと（寄せ先は `DistillRangeSnapTest` が持つ）
 *
 * ## `ModalBottomSheet` ごと開かない理由
 *
 * 開閉アニメーションを待つ必要があり、検査したいものと無関係に落ちうる
 * （→ [QuizActionSectionTest]）。シートの中身は [DistillRangeSheetContent] として
 * 切り出してあるので直接描ける。
 */
@RunWith(AndroidJUnit4::class)
class DistillRangeAdjustUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun 行タップは範囲調整へチェックボックスは取捨へ届く() {
        val opened = mutableListOf<String>()
        val toggled = mutableListOf<String>()

        composeRule.setContent {
            AppTheme(darkTheme = false) {
                AiTab(
                    uiState = uiStateWith(candidates(items = listOf(candidateItem()))),
                    onOpenRemark = {},
                    onStartDistill = {},
                    onDownloadDistillModel = {},
                    onToggleDistillCandidate = { toggled += it },
                    onOpenDistillRangeSheet = { opened += it },
                    onCloseDistillRangeSheet = {},
                    onSelectDistillRange = { _, _ -> },
                    onDragDistillRangeEdge = { _, _, _, _ -> },
                    onNudgeDistillRangeEdge = { _, _ -> },
                    onResetDistillRange = {},
                    onSaveDistill = {},
                    onRetryDistill = {},
                    onDismissDistill = {},
                    onKeepCurrentRecovery = {},
                    onRestoreOriginal = {},
                    onExportOriginal = {}
                )
            }
        }

        composeRule.onNodeWithText(BOLD_TEXT).performScrollTo().performClick()
        assertEquals(listOf("S001"), opened)
        assertEquals("チェックは動かない", emptyList<String>(), toggled)

        // チェックボックスは自分でタップを受けるので、行の調整導線へは届かない。
        composeRule.onAllNodes(isToggleable()).onFirst().performScrollTo().performClick()
        assertEquals(listOf("S001"), toggled)
        assertEquals("シートは開かない", listOf("S001"), opened)
    }

    @Test
    fun シートは確定範囲を太字で見せ未調整なら戻せない() {
        composeRule.setContent {
            AppTheme(darkTheme = false) {
                DistillRangeSheetContent(
                    item = candidateItem(),
                    projectedBoldRatio = 0.12,
                    isWithinBoldLimit = true,
                    isDeselectedByOverlap = false,
                    otherDeselectedCount = 0,
                    onSelectPreset = {},
                    onDragEdge = { _, _, _ -> },
                    onNudgeEdge = {},
                    onReset = {}
                )
            }
        }

        // 親文が原文のまま出る。**確定範囲が実際に太字＋下線で描かれていることまで見る** —
        // 存在の確認だけだと、強調を丸ごと外す変異が素通りする。
        composeRule.onNodeWithText(PARENT_TEXT).assertIsDisplayed()
        val emphasized = emphasizedSpansOf(PARENT_TEXT)
        assertEquals(1, emphasized.size)
        assertEquals(0, emphasized.single().first)
        assertEquals(BOLD_TEXT.length, emphasized.single().second)
        // 存在する段だけが出て、いまの段には印が付く。
        composeRule.onNodeWithText("✓ 意味節").assertIsDisplayed()
        composeRule.onNodeWithText("文全体").assertIsDisplayed()
        composeRule.onNodeWithText("語句").assertDoesNotExist()
        composeRule.onNodeWithText("最初の範囲に戻す").assertIsNotEnabled()
    }

    @Test
    fun 調整済みなら最初の範囲へ戻せる() {
        var resets = 0

        composeRule.setContent {
            AppTheme(darkTheme = false) {
                DistillRangeSheetContent(
                    item = candidateItem().copy(
                        text = PARENT_TEXT,
                        boldStartInParent = 0,
                        boldEndInParent = PARENT_TEXT.length,
                        currentPreset = DistillRangePreset.Sentence,
                        isRangeAdjusted = true
                    ),
                    projectedBoldRatio = 0.31,
                    isWithinBoldLimit = false,
                    isDeselectedByOverlap = false,
                    otherDeselectedCount = 0,
                    onSelectPreset = {},
                    onDragEdge = { _, _, _ -> },
                    onNudgeEdge = {},
                    onReset = { resets++ }
                )
            }
        }

        composeRule.onNodeWithText("✓ 文全体").assertIsDisplayed()
        composeRule.onNodeWithText("最初の範囲に戻す").assertIsEnabled().performClick()
        assertEquals(1, resets)
    }

    @Test
    fun 重なり解消はシートの1行と外れたカードの印の両方に出る() {
        composeRule.setContent {
            AppTheme(darkTheme = false) {
                DistillRangeSheetContent(
                    item = candidateItem(),
                    projectedBoldRatio = 0.2,
                    isWithinBoldLimit = true,
                    isDeselectedByOverlap = false,
                    otherDeselectedCount = 1,
                    onSelectPreset = {},
                    onDragEdge = { _, _, _ -> },
                    onNudgeEdge = {},
                    onReset = {}
                )
            }
        }

        // シート内の1行。読み上げも同じ1行から出る（live region）。
        composeRule.onNodeWithText("! 重なるため、ほかの1箇所の選択を外しました。").assertIsDisplayed()
    }

    @Test
    fun 外された候補のシートは自分が外れたことを言う() {
        composeRule.setContent {
            AppTheme(darkTheme = false) {
                DistillRangeSheetContent(
                    item = candidateItem().copy(isSelected = false),
                    projectedBoldRatio = 0.2,
                    isWithinBoldLimit = true,
                    isDeselectedByOverlap = true,
                    otherDeselectedCount = 0,
                    onSelectPreset = {},
                    onDragEdge = { _, _, _ -> },
                    onNudgeEdge = {},
                    onReset = {}
                )
            }
        }

        // 目の前の候補を「ほか」と呼ばない。
        composeRule.onNodeWithText("! この箇所は範囲が重なるため、選択が外れています。").assertIsDisplayed()
        composeRule.onNodeWithText("! 重なるため、ほかの1箇所の選択を外しました。").assertDoesNotExist()
    }

    @Test
    fun 外れた候補のカードには理由の印が残る() {
        composeRule.setContent {
            AppTheme(darkTheme = false) {
                AiTab(
                    uiState = uiStateWith(
                        candidates(
                            items = listOf(
                                candidateItem().copy(isSelected = false),
                                candidateItem().copy(id = "S002", text = "別の箇所です")
                            ),
                            overlapDeselectedIds = listOf("S001")
                        )
                    ),
                    onOpenRemark = {},
                    onStartDistill = {},
                    onDownloadDistillModel = {},
                    onToggleDistillCandidate = {},
                    onOpenDistillRangeSheet = {},
                    onCloseDistillRangeSheet = {},
                    onSelectDistillRange = { _, _ -> },
                    onDragDistillRangeEdge = { _, _, _, _ -> },
                    onNudgeDistillRangeEdge = { _, _ -> },
                    onResetDistillRange = {},
                    onSaveDistill = {},
                    onRetryDistill = {},
                    onDismissDistill = {},
                    onKeepCurrentRecovery = {},
                    onRestoreOriginal = {},
                    onExportOriginal = {}
                )
            }
        }

        // **外れる候補はシートの裏にいる。** カード側に理由が残らないと、
        // シートを閉じた後にチェックが外れた理由へ辿り着けない。
        composeRule.onNodeWithText("! 範囲が重なるため選択を外しました")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun 微調整は動かせる向きだけが押せて押した向きが届く() {
        val moves = mutableListOf<DistillRangeEdgeMove>()

        composeRule.setContent {
            AppTheme(darkTheme = false) {
                DistillRangeSheetContent(
                    item = candidateItem(),
                    projectedBoldRatio = 0.12,
                    isWithinBoldLimit = true,
                    isDeselectedByOverlap = false,
                    otherDeselectedCount = 0,
                    onSelectPreset = {},
                    onDragEdge = { _, _, _ -> },
                    onNudgeEdge = { moves += it },
                    onReset = {}
                )
            }
        }

        // 矢印そのものは読み上げにならないので、向きは読み上げ名で引く。
        composeRule.onNodeWithContentDescription(DistillRangeEdgeMove.ExpandStart.label())
            .performScrollTo()
            .assertIsNotEnabled()
        composeRule.onNodeWithContentDescription(DistillRangeEdgeMove.ShrinkEnd.label())
            .performScrollTo()
            .assertIsEnabled()
            .performClick()

        assertEquals(listOf(DistillRangeEdgeMove.ShrinkEnd), moves)
    }

    @Test
    fun つまみを引くと端の移動として届く() {
        // **寄せ先は見ない。** どこへ止まるかは `DistillRangeSnapTest` と Controller が持つ。
        // ここが見るのは、引いた操作が端の移動として Controller まで届くことだけ。
        val drags = mutableListOf<Pair<DistillRangeEdge, Int>>()
        val short = "短い親文です"

        composeRule.setContent {
            AppTheme(darkTheme = false) {
                DistillRangeSheetContent(
                    item = candidateItem().copy(
                        text = short,
                        parentText = short,
                        boldStartInParent = 0,
                        boldEndInParent = short.length
                    ),
                    projectedBoldRatio = 0.12,
                    isWithinBoldLimit = true,
                    isDeselectedByOverlap = false,
                    otherDeselectedCount = 0,
                    onSelectPreset = {},
                    onDragEdge = { edge, offset, _ -> drags += edge to offset },
                    onNudgeEdge = {},
                    onReset = {}
                )
            }
        }

        // **つまみの近くから掴む。** 本文のどこでも掴めるようにすると、
        // シートを縦に送ろうとした指が範囲を動かしてしまう。始点のつまみは親文の左端にある。
        composeRule.onNodeWithText(short).performScrollTo().performTouchInput {
            down(Offset(2f, height * 0.6f))
            moveTo(Offset(width * 0.1f, height * 0.6f))
            moveTo(Offset(width * 0.2f, height * 0.6f))
            up()
        }

        assertEquals("引きが届いていません", true, drags.isNotEmpty())
        assertEquals(DistillRangeEdge.Start, drags.last().first)
    }

    /** 描かれた文字列のうち、太字＋下線の両方が掛かった範囲。 */
    private fun emphasizedSpansOf(text: String): List<Pair<Int, Int>> =
        composeRule.onNodeWithText(text)
            .fetchSemanticsNode()
            .config[SemanticsProperties.Text]
            .first()
            .spanStyles
            .filter {
                it.item.fontWeight == FontWeight.Bold &&
                    it.item.textDecoration == TextDecoration.Underline
            }
            .map { it.start to it.end }

    private fun candidates(
        items: List<DistillCandidateItem>,
        overlapDeselectedIds: List<String> = emptyList()
    ) = DistillState.Candidates(
        sourceTitle = "対象ノート",
        items = items,
        projectedBoldRatio = 0.12,
        isWithinBoldLimit = true,
        overlapDeselectedIds = overlapDeselectedIds
    )

    private fun candidateItem() = DistillCandidateItem(
        id = "S001",
        text = BOLD_TEXT,
        heading = null,
        positionLabel = "1 / 2",
        context = PARENT_TEXT,
        parentText = PARENT_TEXT,
        boldStartInParent = 0,
        boldEndInParent = BOLD_TEXT.length,
        availablePresets = listOf(DistillRangePreset.Clause, DistillRangePreset.Sentence),
        currentPreset = DistillRangePreset.Clause,
        availableEdgeMoves = setOf(DistillRangeEdgeMove.ExpandEnd, DistillRangeEdgeMove.ShrinkEnd)
    )

    private fun uiStateWith(distillState: DistillState) = NoteUiState(
        noteState = NoteState.Success(
            title = "対象ノート",
            content = PARENT_TEXT,
            targetUri = "content://note",
            originalHash = "hash"
        ),
        distillState = distillState
    )

    private companion object {
        const val BOLD_TEXT = "ここが太字になる意味節です"
        const val PARENT_TEXT = "ここが太字になる意味節です、こちらは残る後半です。"
    }
}
