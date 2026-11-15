package com.example.newproject.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.newproject.domain.markdown.MarkdownBlock
import com.example.newproject.domain.markdown.NoteSectionModel
import com.example.newproject.domain.markdown.buildNoteSectionModel
import com.example.newproject.model.DocumentRef
import com.example.newproject.model.NoteUiState
import com.example.newproject.model.RelatedNote
import com.example.newproject.model.state.BacklinkRow
import com.example.newproject.model.state.BacklinksState
import com.example.newproject.model.state.KnowledgeIndexProgress
import com.example.newproject.model.state.MarginMemoDraft
import com.example.newproject.model.state.MarginMemoState
import com.example.newproject.model.state.RelatedNotesState
import com.example.newproject.model.state.SideReadingState
import com.example.newproject.ui.screen.BacklinksSection
import com.example.newproject.ui.screen.BodyPosition
import com.example.newproject.ui.screen.MarginMemoSheetContent
import com.example.newproject.ui.screen.PaneBacklinksInputs
import com.example.newproject.ui.screen.PaneRelatedInputs
import com.example.newproject.ui.screen.PendingNoteStart
import com.example.newproject.ui.screen.RelatedTab
import com.example.newproject.ui.screen.SideLinkStart
import com.example.newproject.ui.screen.SideReadingPane
import com.example.newproject.ui.screen.rememberNoteStart
import com.example.newproject.ui.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * **このノートへのリンクが余白ペインと関連タブに描かれ、押した先がリンクのある節の見出しから始まることを固定する**
 * （→ features/backlinks.md §10 の instrumentation）。
 *
 * 何を出すか（行・進み具合・数えられない）と、同じ見出しの何番目へ着くかは JVM 側（`BacklinksSectionTest`・`NoteLinkActionsTest`）が持つ。
 * ここで見るのは、枠がその判定を描くことと、右で開く面と今のノートとして開く本文の両方が番目を渡して見出しへ送ること。
 */
@RunWith(AndroidJUnit4::class)
class BacklinksUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    // ── 余白ペイン ────────────────────────────────────────────────────

    @Test
    fun ペインでは畳んで件数だけを出し_開くと1文とほかの箇所が並び_押すと右で開く() {
        val opened = mutableListOf<BacklinkRow>()
        showPane(BacklinksSection.Rows(listOf(ROW)), onOpen = { opened += it })

        composeRule.onNodeWithText("▸ このノートへのリンク 1件").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(ROW.sentence).assertDoesNotExist()

        composeRule.onNodeWithText("▸ このノートへのリンク 1件").performClick()
        composeRule.onNodeWithText(ROW.sentence).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("ほか 2 箇所").assertIsDisplayed()
        composeRule.onNodeWithText(SOURCE.title).performClick()

        composeRule.runOnIdle { assertEquals(listOf(ROW), opened) }
    }

    @Test
    fun ペインでは索引を作っている間は件数の代わりに進み具合を出す() {
        showPane(BacklinksSection.Building(120, 500))

        composeRule.onNodeWithText("このノートへのリンク：索引を作っています（120 / 500）").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun ペインでは数えられなかったら件数を出さず_開くとそう書く() {
        showPane(BacklinksSection.Unavailable)

        composeRule.onNodeWithText("▸ このノートへのリンク").performScrollTo().performClick()

        composeRule.onNodeWithText("このノートへのリンクを数えられませんでした").performScrollTo().assertIsDisplayed()
    }

    private fun showPane(section: BacklinksSection, onOpen: (BacklinkRow) -> Unit = {}) {
        composeRule.setContent {
            AppTheme(darkTheme = false) {
                var expanded by remember { mutableStateOf(false) }
                MarginMemoSheetContent(
                    state = MarginMemoState.Ready(memos = emptyList()),
                    draft = MarginMemoDraft(),
                    section = null,
                    hasHeadings = false,
                    onJumpToSection = {},
                    arranged = null,
                    onEdit = {},
                    onSubmit = {},
                    onDelete = {},
                    related = PaneRelatedInputs(
                        candidates = listOf(OTHER),
                        expanded = false,
                        onToggle = {},
                        onOpen = {},
                        backlinks = PaneBacklinksInputs(
                            section = section,
                            expanded = expanded,
                            onToggle = { expanded = !expanded },
                            onOpen = onOpen
                        )
                    )
                )
            }
        }
    }

    // ── 関連タブ ──────────────────────────────────────────────────────

    @Test
    fun 関連タブでは畳まずに並び_押すとその行を開く() {
        val opened = mutableListOf<BacklinkRow>()
        showRelatedTab(BacklinksState.Ready(listOf(ROW)), KnowledgeIndexProgress.Ready, onOpenBacklink = { opened += it })

        composeRule.onNodeWithText("このノートへのリンク 1件").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(ROW.sentence).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(SOURCE.title).performScrollTo().performClick()

        composeRule.runOnIdle { assertEquals(listOf(ROW), opened) }
    }

    @Test
    fun 関連タブでは0件なら枠を出さず_作っている間は進み具合を出す() {
        var progress by mutableStateOf<KnowledgeIndexProgress>(KnowledgeIndexProgress.Ready)
        composeRule.setContent {
            AppTheme(darkTheme = false) {
                RelatedTab(
                    uiState = relatedState(BacklinksState.Ready(emptyList()), progress),
                    onOpenNote = {},
                    onOpenBacklink = {}
                )
            }
        }
        composeRule.onNodeWithText("このノートへのリンク", substring = true).assertDoesNotExist()

        progress = KnowledgeIndexProgress.Building(3, 10)

        composeRule.onNodeWithText("索引を作っています（3 / 10）").performScrollTo().assertIsDisplayed()
    }

    private fun showRelatedTab(state: BacklinksState, progress: KnowledgeIndexProgress, onOpenBacklink: (BacklinkRow) -> Unit) {
        composeRule.setContent {
            AppTheme(darkTheme = false) {
                RelatedTab(uiState = relatedState(state, progress), onOpenNote = {}, onOpenBacklink = onOpenBacklink)
            }
        }
    }

    private fun relatedState(state: BacklinksState, progress: KnowledgeIndexProgress) = NoteUiState(
        relatedNotesState = RelatedNotesState.Success(relatedNotes = listOf(OTHER), aiNotes = emptyList()),
        backlinks = state,
        knowledgeIndexProgress = progress
    )

    // ── 同じ見出しの2つ目の節から開く ──────────────────────────────────────

    /** 右で開く面。リンクのある節の見出しが2つ目の「まとめ」なら、2つ目から始める。 */
    @Test
    fun 右で開くと同じ見出しの2つ目の節から始まる() {
        var handled by mutableLongStateOf(0L)
        composeRule.setContent {
            AppTheme(darkTheme = false) {
                val listState = rememberSaveable(SOURCE.ref.value, saver = LazyListState.Saver) { LazyListState() }
                Box(modifier = Modifier.requiredSize(width = 400.dp, height = 600.dp)) {
                    SideReadingPane(
                        state = SideReadingState.Ready(SOURCE),
                        blocks = buildNoteSectionModel(REPEATED_BODY).blocks,
                        imageLoader = null,
                        onBack = {},
                        onMove = { _, _ -> },
                        onRetry = {},
                        listState = listState,
                        linkStart = SideLinkStart(SOURCE.ref.value, "まとめ", request = 1L, headingOccurrence = 1),
                        handledLinkRequest = handled,
                        onLinkRequestHandled = { handled = it },
                        onHeadingMissing = {},
                        onLink = null
                    )
                }
            }
        }

        composeRule.onNodeWithText("段落21 の本文です。").assertIsDisplayed()
    }

    /** 今のノートとして開く本文（関連タブから）。開いた直後は解析が無く、届いてから2つ目の「まとめ」へ送る。 */
    @Test
    fun 今のノートとして開くと解析が届いてから同じ見出しの2つ目の節へ送る() {
        var openedUri by mutableStateOf<String?>(null)
        var model by mutableStateOf<NoteSectionModel?>(null)
        var start: ((PendingNoteStart) -> Unit)? = null
        composeRule.setContent {
            AppTheme(darkTheme = false) {
                val listState = rememberLazyListState()
                val position = remember(listState) { BodyPosition(listState) }
                start = rememberNoteStart(position, openedUri, model, imageMeasurements = null, onHeadingMissing = {})
                Body(listState, model)
            }
        }

        composeRule.runOnIdle {
            checkNotNull(start)(PendingNoteStart(SOURCE.ref.value, block = 0, heading = "まとめ", headingOccurrence = 1))
            openedUri = SOURCE.ref.value
        }
        composeRule.runOnIdle { model = buildNoteSectionModel(REPEATED_BODY) }

        composeRule.onNodeWithText("段落21 の本文です。").assertIsDisplayed()
    }

    @Composable
    private fun Body(listState: LazyListState, model: NoteSectionModel?) {
        val blocks = model?.blocks.orEmpty()
        LazyColumn(state = listState, modifier = Modifier.requiredSize(width = 400.dp, height = 600.dp)) {
            items(blocks.size) { index ->
                val text = when (val block = blocks[index]) {
                    is MarkdownBlock.Heading -> block.text
                    is MarkdownBlock.Paragraph -> block.text
                    else -> ""
                }
                Text(text)
            }
        }
    }

    private companion object {
        val SOURCE = RelatedNote(title = "出どころのノート", ref = DocumentRef("content://vault/source.md"), isWikilinked = false)
        val OTHER = RelatedNote(title = "関連のノート", ref = DocumentRef("content://vault/other.md"), isWikilinked = false)
        val ROW = BacklinkRow(
            note = SOURCE,
            sentence = "二つ目のまとめで習慣を書いた。",
            otherCount = 2,
            sectionHeading = "まとめ",
            sectionHeadingOccurrence = 1
        )

        /** 0 と 20 に同じ見出し「まとめ」を置いた本文。番号はブロックの位置と同じ。 */
        val REPEATED_BODY = (0 until 40).joinToString("\n\n") { if (it % 20 == 0) "## まとめ" else "段落$it の本文です。" }
    }
}
