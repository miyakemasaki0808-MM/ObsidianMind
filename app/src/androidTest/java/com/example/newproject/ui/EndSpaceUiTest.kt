package com.example.newproject.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.newproject.domain.markdown.buildNoteSectionModel
import com.example.newproject.model.DocumentRef
import com.example.newproject.model.RelatedNote
import com.example.newproject.model.state.SideReadingState
import com.example.newproject.ui.markdown.EndSpace
import com.example.newproject.ui.screen.SideLinkStart
import com.example.newproject.ui.screen.SideReadingPane
import com.example.newproject.ui.theme.AppTheme
import kotlinx.coroutines.CoroutineScope
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 末尾に近い見出しを本文の最初の行に置く余白（→ docs/dev/features/note_links.md 判断6）が、
 * 右の送り直しで崩れないこと。
 */
@RunWith(AndroidJUnit4::class)
class EndSpaceUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    // ── 右の面 ──────────────────────────────────────────────────────

    private var sideStart by mutableStateOf<SideLinkStart?>(null)
    private lateinit var sideList: LazyListState
    private lateinit var sideSpace: EndSpace
    private lateinit var scope: CoroutineScope

    @androidx.compose.runtime.Composable
    private fun SidePane(body: String) {
        var handled by rememberSaveable { mutableLongStateOf(0L) }
        // 本番（NoteReaderTab）と同じく、位置と余白を右のノートごとに持つ。
        sideList = rememberSaveable(SOURCE.ref.value, saver = LazyListState.Saver) { LazyListState() }
        sideSpace = remember(SOURCE.ref.value) { EndSpace() }
        scope = rememberCoroutineScope()
        Box(modifier = Modifier.requiredSize(width = 400.dp, height = 600.dp)) {
            SideReadingPane(
                state = SideReadingState.Ready(SOURCE),
                blocks = buildNoteSectionModel(body).blocks,
                imageLoader = null,
                onBack = {},
                onMove = { _, _ -> },
                onRetry = {},
                listState = sideList,
                endSpace = sideSpace,
                linkStart = sideStart,
                handledLinkRequest = handled,
                onLinkRequestHandled = { handled = it },
                onHeadingMissing = {},
                onLink = null
            )
        }
    }

    /** 右を同じノートの先頭へ送り直したら（見出しの無いリンク・見つからない見出し）、前の見出しのための余白を消す。 */
    @Test
    fun 右を同じノートの先頭へ送り直すと末尾の余白を消す() {
        sideStart = SideLinkStart(SOURCE.ref.value, "まとめ", request = 1L, headingOccurrence = 1)
        composeRule.setContent { AppTheme(darkTheme = false) { SidePane(SHORT_TAIL_BODY) } }
        assertAtHeading(sideList)
        composeRule.runOnIdle { check(sideSpace.bottomPx > 0) { "前提: 末尾の余白で見出しを上端に置いている" } }

        composeRule.runOnIdle { sideStart = SideLinkStart(SOURCE.ref.value, null, request = 2L) }
        assertAtTopWithoutSpace(sideList, sideSpace)

        composeRule.runOnIdle { sideStart = SideLinkStart(SOURCE.ref.value, "まとめ", request = 3L, headingOccurrence = 1) }
        assertAtHeading(sideList)
        composeRule.runOnIdle { sideStart = SideLinkStart(SOURCE.ref.value, "無い見出し", request = 4L) }
        assertAtTopWithoutSpace(sideList, sideSpace)
    }

    // ── 確かめ方 ──────────────────────────────────────────────────

    private fun assertAtHeading(list: LazyListState) {
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            assertEquals("見出しが上端に無い", SHORT_TAIL_HEADING, list.firstVisibleItemIndex)
            assertEquals("見出しが上端からずれている", 0, list.firstVisibleItemScrollOffset)
        }
    }

    private fun assertAtTopWithoutSpace(list: LazyListState, space: EndSpace) {
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            assertEquals(0, list.firstVisibleItemIndex)
            assertEquals(0, space.bottomPx)
            assertEquals(0, list.layoutInfo.afterContentPadding)
        }
    }

    private companion object {
        val SOURCE = RelatedNote(title = "出どころのノート", ref = DocumentRef("content://vault/source.md"), isWikilinked = false)

        /** 2つ目の「まとめ」の位置。 */
        const val SHORT_TAIL_HEADING = 20

        /** 2つ目の「まとめ」の後ろに2段落しかない本文。見出しから後ろは画面より短い。 */
        val SHORT_TAIL_BODY = (0 until 23).joinToString("\n\n") { if (it % 20 == 0) "## まとめ" else "段落$it の本文です。" }
    }
}
