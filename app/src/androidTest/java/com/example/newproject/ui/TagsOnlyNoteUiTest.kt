package com.example.newproject.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.newproject.domain.markdown.buildNoteSectionModel
import com.example.newproject.ui.markdown.MarkdownNoteContent
import com.example.newproject.ui.component.ReadingProgressReporter
import com.example.newproject.ui.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * frontmatter だけでタグを持つノート（→ docs/dev/features/note_typesetting.md §5.3）。本文のブロックが無くても、
 * 通常表示と全画面が使う本文の一覧に札が出て、一覧には架空のブロックの項目を足さない。
 */
@RunWith(AndroidJUnit4::class)
class TagsOnlyNoteUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun 本文が空でタグだけのノートでも札が出て_一覧に項目を足さない() {
        val model = buildNoteSectionModel("---\ntags:\n  - work\n---\n")
        assertTrue("前提: 本文のブロックが無い", model.blocks.isEmpty())
        assertEquals(listOf("work"), model.tags)
        var itemCount = -1
        composeRule.setContent {
            AppTheme(darkTheme = false) {
                val listState = rememberLazyListState()
                Box(modifier = Modifier.requiredSize(360.dp, 600.dp)) {
                    MarkdownNoteContent(content = "", listState = listState, precomputedBlocks = model.blocks, tags = model.tags)
                }
                itemCount = listState.layoutInfo.totalItemsCount
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("#work").assertExists()
        assertEquals("架空のブロックの項目がある", 0, itemCount)
    }

    /**
     * 本文のあるノートから、同じ一覧の状態のままタグだけのノートへ切り替える（通常表示でノートを替えるときと同じ）。
     * 札が出て、前のノートの配置を読んで架空のブロックの進捗を報告しない。
     */
    @Test
    fun 本文のあるノートからタグだけのノートへ替えても_前のノートの配置で進捗を報告しない() {
        val withBody = buildNoteSectionModel((0 until 30).joinToString("\n\n") { "段落$it の本文です。" })
        val tagsOnly = buildNoteSectionModel("---\ntags:\n  - work\n---\n")
        var model by mutableStateOf(withBody)
        val listState = LazyListState()
        val reports = mutableListOf<Pair<Int, Int>>()
        composeRule.setContent {
            AppTheme(darkTheme = false) {
                Box(modifier = Modifier.requiredSize(360.dp, 600.dp)) {
                    MarkdownNoteContent(content = "", listState = listState, precomputedBlocks = model.blocks, tags = model.tags)
                }
                ReadingProgressReporter(model, listState, null) { block, _, total, _ -> reports += block to total }
            }
        }
        composeRule.waitForIdle()
        assertTrue("前提: 本文のあるノートで進捗を報告した", reports.isNotEmpty())

        reports.clear()
        model = tagsOnly
        composeRule.waitForIdle()

        composeRule.onNodeWithText("#work").assertExists()
        assertEquals("一覧に前のノートの項目が残っている", 0, listState.layoutInfo.totalItemsCount)
        assertTrue("タグだけのノートで進捗を報告した: $reports", reports.isEmpty())
    }
}
