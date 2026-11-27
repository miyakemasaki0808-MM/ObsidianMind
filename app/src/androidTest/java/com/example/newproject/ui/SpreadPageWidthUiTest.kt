package com.example.newproject.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.newproject.domain.markdown.buildNoteSectionModel
import com.example.newproject.ui.screen.BodyPosition
import com.example.newproject.ui.screen.NoteSpread
import com.example.newproject.ui.screen.spreadPageWidthPx
import com.example.newproject.ui.screen.spreadTextWidthPx
import com.example.newproject.ui.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 見開きの左右のページの本文は、計測と同じ幅で組まれる（→ docs/dev/features/note_typesetting.md §7・note_spread.md 判断8）。
 * 密度 2.4375・本文領域 800dp・ページ 392dp・溝 16dp では、ページを丸めた 956px を左右に取ると親の幅 1950px を1画素超え、
 * 親の `Row` が右のページだけ 955px に縮める。左右どちらのページの段落も、計測の幅を最大幅として組まれていることを見る。
 */
@RunWith(AndroidJUnit4::class)
class SpreadPageWidthUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun 端数の密度でも_左右のページの段落は計測と同じ幅で組まれる() {
        val density = Density(2.4375f, 1f)
        val model = buildNoteSectionModel(BODY)
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides density) {
                AppTheme(darkTheme = false) {
                    Box(modifier = Modifier.requiredSize(width = 800.dp, height = 600.dp)) {
                        NoteSpread(
                            noteKey = NOTE,
                            title = "見開き",
                            sectionModel = model,
                            position = remember { BodyPosition(LazyListState()) },
                            paperColor = Color.White,
                            imageLoader = null,
                            imageMeasurements = null,
                            headingAccessory = null,
                            onLink = null,
                            onReadingProgress = { _, _, _, _ -> },
                            pageWidth = 392.dp,
                            gutter = 16.dp,
                            leftPageTop = null
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()

        val container = with(density) { 800.dp.roundToPx() }
        val expected = spreadTextWidthPx(spreadPageWidthPx(392.dp, 16.dp, container, density), density)
        val nodes = composeRule.onAllNodesWithText("段落", substring = true).fetchSemanticsNodes()
        assertTrue("段落が描かれていない", nodes.isNotEmpty())
        val center = container / 2f
        assertTrue("右のページに段落が無い", nodes.any { it.boundsInRoot.left > center })
        assertTrue("左のページに段落が無い", nodes.any { it.boundsInRoot.right < center })
        nodes.forEach { node ->
            val layouts = mutableListOf<TextLayoutResult>()
            node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
            val maxWidth = layouts.single().layoutInput.constraints.maxWidth
            assertEquals("${node.boundsInRoot} の段落の組みの幅", expected, maxWidth)
        }
    }

    private companion object {
        const val NOTE = "content://vault/spread-width.md"
        val BODY = (0 until 40).joinToString("\n\n") { "段落%02d の本文です。読みながら思ったことを、本文の横に短く残す。".format(it) }
    }
}
