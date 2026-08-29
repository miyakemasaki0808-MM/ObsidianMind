package com.example.newproject.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.newproject.model.NoteUiState
import com.example.newproject.model.state.DistillCandidateItem
import com.example.newproject.model.state.DistillRangePreset
import com.example.newproject.model.state.DistillState
import com.example.newproject.model.state.NoteState
import com.example.newproject.ui.screen.AiTab
import com.example.newproject.ui.screen.DistillRangeSheetContent
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
                    overlapDeselectedCount = 0,
                    onSelectPreset = {},
                    onReset = {}
                )
            }
        }

        // 親文が原文のまま出る。太字になる部分はその内側にある。
        composeRule.onNodeWithText(PARENT_TEXT).assertIsDisplayed()
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
                    overlapDeselectedCount = 0,
                    onSelectPreset = {},
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
                    overlapDeselectedCount = 1,
                    onSelectPreset = {},
                    onReset = {}
                )
            }
        }

        // シート内の1行。読み上げも同じ1行から出る（live region）。
        composeRule.onNodeWithText("! 重なるため、ほかの1箇所の選択を外しました。").assertIsDisplayed()
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
        currentPreset = DistillRangePreset.Clause
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
