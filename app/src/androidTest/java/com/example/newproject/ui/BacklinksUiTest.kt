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
import androidx.compose.ui.test.junit4.StateRestorationTester
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
import com.example.newproject.model.NoteLoadId
import com.example.newproject.model.NoteUiState
import com.example.newproject.model.RelatedNote
import com.example.newproject.model.state.BacklinkRow
import com.example.newproject.model.state.BacklinksState
import com.example.newproject.model.state.KnowledgeIndexProgress
import com.example.newproject.model.state.MarginMemoDraft
import com.example.newproject.model.state.MarginMemoState
import com.example.newproject.model.state.RelatedNotesState
import com.example.newproject.model.state.SideReadingState
import com.example.newproject.ui.markdown.EndSpace
import com.example.newproject.ui.markdown.MarkdownNoteContent
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

    // ── 節の開閉とノートの切り替え ─────────────────────────────────────

    /**
     * ノートの画面が組まれていない間（全画面）に A→B→A と移っても、戻ったときに畳まれている。画面の保存値から戻る順序を含める
     * （本番はノートの画面が別の経路の間、その保存値を退避して戻す）。番号が同じまま戻ったら開いたまま。
     */
    @Test
    fun ノートの画面が組まれていない間にノートが替わったら_戻ったときに節は畳まれている() {
        var noteLoadId by mutableStateOf(NoteLoadId(issuer = 1L, count = 1L))
        var shown by mutableStateOf(true)
        composeRule.setContent {
            AppTheme(darkTheme = false) {
                val holder = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
                if (shown) holder.SaveableStateProvider("note") { ExpansionProbe(noteLoadId) }
            }
        }
        composeRule.onNodeWithText("畳んでいる").performClick()
        composeRule.onNodeWithText("開いている").assertIsDisplayed()

        composeRule.runOnIdle { shown = false }
        composeRule.runOnIdle { shown = true }
        composeRule.onNodeWithText("開いている").assertIsDisplayed()

        composeRule.runOnIdle { shown = false }
        composeRule.runOnIdle { noteLoadId = noteLoadId.copy(count = 2L) }
        composeRule.runOnIdle { noteLoadId = noteLoadId.copy(count = 3L) }
        composeRule.runOnIdle { shown = true }
        composeRule.onNodeWithText("畳んでいる").assertIsDisplayed()
    }

    /**
     * プロセスの作り直しでは、画面の保存値が Bundle を通って戻り、Store は番号を数え直す（発行元の印が替わる）。
     * 前のプロセスの A で開いた値から戻り、新しいプロセスの B が同じ数の番号でも畳まれている。
     * 対照として、同じ Store のまま画面だけを作り直したら開いたまま。
     */
    @Test
    fun プロセスを作り直して同じ数の番号のノートを開いても_前のプロセスで開いた節は畳まれている() {
        val restoration = StateRestorationTester(composeRule)
        var noteLoadId by mutableStateOf(NoteLoadId(issuer = 1L, count = 1L))
        restoration.setContent { AppTheme(darkTheme = false) { ExpansionProbe(noteLoadId) } }
        composeRule.onNodeWithText("畳んでいる").performClick()
        composeRule.onNodeWithText("開いている").assertIsDisplayed()

        restoration.emulateSavedInstanceStateRestore()
        composeRule.onNodeWithText("開いている").assertIsDisplayed()

        composeRule.runOnIdle { noteLoadId = NoteLoadId(issuer = 2L, count = 1L) }
        restoration.emulateSavedInstanceStateRestore()
        composeRule.onNodeWithText("畳んでいる").assertIsDisplayed()
    }

    @Composable
    private fun ExpansionProbe(noteLoadId: NoteLoadId) {
        val expansion = com.example.newproject.ui.screen.rememberBacklinksExpansion(noteLoadId)
        androidx.compose.material3.TextButton(onClick = expansion.toggle) {
            Text(if (expansion.expanded) "開いている" else "畳んでいる")
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
                        endSpace = remember { EndSpace() },
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

    private var openedUri by mutableStateOf<String?>(null)
    private var noteLoading by mutableStateOf(false)
    private var model by mutableStateOf<NoteSectionModel?>(null)
    private var start: ((PendingNoteStart) -> Unit)? = null

    private fun showMainBody() {
        composeRule.setContent {
            AppTheme(darkTheme = false) {
                val listState = rememberLazyListState()
                val position = remember(listState) { BodyPosition(listState) }
                start = rememberNoteStart(position, openedUri, noteLoading, model, imageMeasurements = null, onHeadingMissing = {})
                Body(listState, model)
            }
        }
    }

    /** 今のノートとして開く本文（関連タブから）。開いた直後は解析が無く、届いてから2つ目の「まとめ」へ送る。 */
    @Test
    fun 今のノートとして開くと解析が届いてから同じ見出しの2つ目の節へ送る() {
        showMainBody()

        composeRule.runOnIdle {
            checkNotNull(start)(PendingNoteStart(SOURCE.ref.value, block = 0, heading = "まとめ", headingOccurrence = 1))
            noteLoading = true
        }
        composeRule.runOnIdle {
            noteLoading = false
            openedUri = SOURCE.ref.value
        }
        composeRule.runOnIdle { model = buildNoteSectionModel(REPEATED_BODY) }

        composeRule.onNodeWithText("段落21 の本文です。").assertIsDisplayed()
    }

    /** 読み込みに失敗した依頼は捨てる。後で同じノートを先頭から開く経路で開いても、古い見出しへ送らない。 */
    @Test
    fun 読み込みに失敗した始まりは_同じノートをほかの経路で開いても効かない() {
        showMainBody()

        composeRule.runOnIdle {
            checkNotNull(start)(PendingNoteStart(SOURCE.ref.value, block = 0, heading = "まとめ", headingOccurrence = 1))
            noteLoading = true
        }
        composeRule.runOnIdle { noteLoading = false }
        composeRule.runOnIdle { noteLoading = true }
        composeRule.runOnIdle {
            noteLoading = false
            openedUri = SOURCE.ref.value
        }
        composeRule.runOnIdle { model = buildNoteSectionModel(REPEATED_BODY) }

        composeRule.onNodeWithText("段落1 の本文です。").assertIsDisplayed()
    }

    // ── 末尾に近い見出しも上端に置く ──────────────────────────────────────

    /**
     * 右で開く面。2つ目の「まとめ」から後ろが画面より短くても、見出しを上端に置く（前の節の終わりを上に出さない）。
     * 一覧は末尾より先へ送れないので、末尾の余白（EndSpace）を足して送り直す。
     */
    @Test
    fun 右で開くと末尾に近い2つ目の見出しも上端に置く() {
        var handled by mutableLongStateOf(0L)
        lateinit var listState: LazyListState
        composeRule.setContent {
            AppTheme(darkTheme = false) {
                listState = rememberSaveable(SOURCE.ref.value, saver = LazyListState.Saver) { LazyListState() }
                Box(modifier = Modifier.requiredSize(width = 400.dp, height = 600.dp)) {
                    SideReadingPane(
                        state = SideReadingState.Ready(SOURCE),
                        blocks = buildNoteSectionModel(SHORT_TAIL_BODY).blocks,
                        imageLoader = null,
                        onBack = {},
                        onMove = { _, _ -> },
                        onRetry = {},
                        listState = listState,
                        endSpace = remember { EndSpace() },
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
        composeRule.runOnIdle {
            assertEquals(SHORT_TAIL_HEADING, listState.firstVisibleItemIndex)
            assertEquals(0, listState.firstVisibleItemScrollOffset)
        }
    }

    /**
     * 今のノートとして開く本文。末尾に近い2つ目の「まとめ」も上端に置き、ほかの位置へ送ったら末尾の余白を消す
     * （先頭から開き直したノートの終わりに余白を残さない）。
     */
    @Test
    fun 今のノートとして開くと末尾に近い2つ目の見出しも上端に置き_ほかの送りで余白を消す() {
        lateinit var position: BodyPosition
        composeRule.setContent {
            AppTheme(darkTheme = false) {
                val listState = rememberLazyListState()
                position = remember(listState) { BodyPosition(listState) }
                start = rememberNoteStart(position, openedUri, noteLoading, model, imageMeasurements = null, onHeadingMissing = {})
                Box(modifier = Modifier.requiredSize(width = 400.dp, height = 600.dp)) {
                    MarkdownNoteContent(
                        content = "",
                        listState = listState,
                        precomputedBlocks = model?.blocks.orEmpty(),
                        endSpace = position.endSpace
                    )
                }
            }
        }

        composeRule.runOnIdle {
            checkNotNull(start)(PendingNoteStart(SOURCE.ref.value, block = 0, heading = "まとめ", headingOccurrence = 1))
            noteLoading = true
        }
        composeRule.runOnIdle {
            noteLoading = false
            openedUri = SOURCE.ref.value
        }
        composeRule.runOnIdle { model = buildNoteSectionModel(SHORT_TAIL_BODY) }

        composeRule.onNodeWithText("段落21 の本文です。").assertIsDisplayed()
        composeRule.runOnIdle {
            assertEquals(SHORT_TAIL_HEADING, position.listState.firstVisibleItemIndex)
            assertEquals(0, position.listState.firstVisibleItemScrollOffset)
        }

        composeRule.runOnIdle { position.moveTo(0) }
        composeRule.runOnIdle {
            assertEquals(0, position.endSpace.bottomPx)
            assertEquals(0, position.listState.layoutInfo.afterContentPadding)
        }
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
            sentenceLink = null,
            otherCount = 2,
            sectionHeading = "まとめ",
            sectionHeadingOccurrence = 1,
            hasMemo = false
        )

        /** 0 と 20 に同じ見出し「まとめ」を置いた本文。番号はブロックの位置と同じ。 */
        val REPEATED_BODY = (0 until 40).joinToString("\n\n") { if (it % 20 == 0) "## まとめ" else "段落$it の本文です。" }

        /** 2つ目の「まとめ」の位置。 */
        const val SHORT_TAIL_HEADING = 20

        /** 2つ目の「まとめ」の後ろに2段落しかない本文。見出しから後ろは画面より短い。 */
        val SHORT_TAIL_BODY = (0 until 23).joinToString("\n\n") { if (it % 20 == 0) "## まとめ" else "段落$it の本文です。" }
    }
}
