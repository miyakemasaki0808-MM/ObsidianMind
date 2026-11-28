package com.example.newproject.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.newproject.model.SectionRef
import com.example.newproject.ui.screen.MEMO_INPUT_HINT
import com.example.newproject.ui.screen.WriteTargetLine
import com.example.newproject.ui.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 余白メモの入力欄の上の行（→ docs/dev/system/ui_copy.md §2・features/margin_pane.md）。
 * 文字の倍率 2.0・安全の1行が2行になる幅（200dp。余白のペインと狭い窓の入力欄の幅より狭い）で、案内が欠けず、
 * 書き込み先の知らせと入れ替えても行の高さが変わらない。予約は2行分なので、3行が要るほど狭い幅は見ない。
 */
@RunWith(AndroidJUnit4::class)
class WriteTargetLineUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun 倍率2の狭い幅でも安全の1行が欠けず_知らせと入れ替えても高さが変わらない() {
        var notice by mutableStateOf<SectionRef?>(null)
        var height = -1
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                AppTheme(darkTheme = false) {
                    Box(modifier = Modifier.width(200.dp).onGloballyPositioned { height = it.size.height }) {
                        WriteTargetLine(notice = notice, hasHeadings = true, onJumpToSection = {})
                    }
                }
            }
        }
        composeRule.waitForIdle()
        val layouts = mutableListOf<TextLayoutResult>()
        composeRule.onNodeWithText(MEMO_INPUT_HINT).fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        assertTrue("前提: 安全の1行が2行に折り返す幅", layouts.single().lineCount == 2)
        assertFalse("安全の1行が欠けた", layouts.single().hasVisualOverflow)
        val hintHeight = height

        notice = SectionRef("うまくいった週")
        composeRule.waitForIdle()
        assertEquals("知らせと入れ替えると高さが変わった", hintHeight, height)
    }
}
