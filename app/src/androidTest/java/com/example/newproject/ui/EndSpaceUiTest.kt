package com.example.newproject.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.newproject.domain.markdown.MarkdownBlock
import com.example.newproject.domain.markdown.buildNoteSectionModel
import com.example.newproject.model.DocumentRef
import com.example.newproject.model.RelatedNote
import com.example.newproject.model.state.SideReadingState
import com.example.newproject.ui.markdown.EndSpace
import com.example.newproject.ui.markdown.EndSpaceSnapshot
import com.example.newproject.ui.markdown.MarkdownNoteContent
import com.example.newproject.ui.markdown.NoteImageContent
import com.example.newproject.ui.markdown.NoteImageLoader
import com.example.newproject.ui.markdown.NoteImageMeasurement
import com.example.newproject.ui.markdown.rememberEndSpace
import com.example.newproject.ui.screen.BodyPosition
import com.example.newproject.ui.screen.PendingNoteStart
import com.example.newproject.ui.screen.SideLinkStart
import com.example.newproject.ui.screen.SideReadingPane
import com.example.newproject.ui.screen.rememberCarriedEndSpace
import com.example.newproject.ui.screen.rememberNoteStart
import com.example.newproject.ui.theme.AppTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 末尾に近い見出しを本文の最初の行に置く余白（→ docs/dev/features/note_links.md 判断6）が、
 * 画面の作り直し・全画面との往復・後から決まる画像の寸法・右の送り直しで崩れないこと。
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
    private fun SidePane(body: String, loader: NoteImageLoader? = null) {
        var handled by rememberSaveable { mutableLongStateOf(0L) }
        // 本番（NoteReaderTab）と同じく、位置と余白を右のノートごとに画面の保存値へ置く。
        sideList = rememberSaveable(SOURCE.ref.value, saver = LazyListState.Saver) { LazyListState() }
        sideSpace = rememberEndSpace(sideList, SOURCE.ref.value)
        scope = rememberCoroutineScope()
        Box(modifier = Modifier.requiredSize(width = 400.dp, height = 600.dp)) {
            SideReadingPane(
                state = SideReadingState.Ready(SOURCE),
                blocks = buildNoteSectionModel(body).blocks,
                imageLoader = loader,
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

    /** 右で見出しを置いた後に利用者が少し戻って読んでいたら、画面を作り直しても、余白で成り立っていたその位置を保つ。 */
    @Test
    fun 右で読んでいた位置は画面を作り直しても余白ごと戻る() {
        sideStart = SideLinkStart(SOURCE.ref.value, "まとめ", request = 1L, headingOccurrence = 1)
        val restoration = StateRestorationTester(composeRule)
        restoration.setContent { AppTheme(darkTheme = false) { SidePane(SHORT_TAIL_BODY) } }
        assertAtHeading(sideList)

        composeRule.runOnIdle { scope.launch { sideList.scrollBy(-80f) } }
        composeRule.waitForIdle()
        val before = composeRule.runOnIdle { sideList.firstVisibleItemIndex to sideList.firstVisibleItemScrollOffset }
        check(before != (SHORT_TAIL_HEADING to 0)) { "前提: 利用者が見出しから離れている" }

        restoration.emulateSavedInstanceStateRestore()

        composeRule.runOnIdle {
            assertEquals(before, sideList.firstVisibleItemIndex to sideList.firstVisibleItemScrollOffset)
        }
    }

    /** 見出しの後ろの画像が測る前は画面の高さを仮に取る。測って本文が縮んでも、右の見出しは上端のまま。 */
    @Test
    fun 右で置いた後に画像の寸法が決まって縮んでも見出しは上端のまま() {
        val loader = GateImageLoader()
        sideStart = SideLinkStart(SOURCE.ref.value, "まとめ", request = 1L, headingOccurrence = 1)
        composeRule.setContent { AppTheme(darkTheme = false) { SidePane(IMAGE_TAIL_BODY, loader) } }
        assertAtHeading(sideList)

        loader.settle(width = 800, height = 200)

        assertAtHeading(sideList)
    }

    // ── 今のノート ──────────────────────────────────────────────────

    private var openedUri by mutableStateOf<String?>(null)
    private var noteBody by mutableStateOf<String?>(null)
    private var start: ((PendingNoteStart) -> Unit)? = null
    private lateinit var position: BodyPosition

    /** 今のノートの位置と余白は、本番どおり本文の画面より外（MainActivity）で持つ。[showBody] が偽なら本文の画面を組まない（全画面の間）。 */
    @androidx.compose.runtime.Composable
    private fun MainBody(loader: NoteImageLoader? = null, showBody: Boolean = true, height: Dp = 600.dp) {
        val listState = rememberLazyListState()
        // 本番（MainActivity）と同じく、余白を画面の保存値に置いて位置の口へ渡す。
        val space = rememberEndSpace(listState)
        position = remember(listState) { BodyPosition(listState, space) }
        scope = rememberCoroutineScope()
        val model = remember(noteBody) { noteBody?.let(::buildNoteSectionModel) }
        start = rememberNoteStart(position, openedUri, noteLoading = false, model, imageMeasurements = null, onHeadingMissing = {})
        if (!showBody) return
        Box(modifier = Modifier.requiredSize(width = 400.dp, height = height)) {
            MarkdownNoteContent(
                content = "",
                listState = listState,
                precomputedBlocks = model?.blocks.orEmpty(),
                imageLoader = loader,
                endSpace = position.endSpace
            )
        }
    }

    private fun openMainAtHeading(body: String) {
        composeRule.runOnIdle { checkNotNull(start)(PendingNoteStart(SOURCE.ref.value, block = 0, heading = "まとめ", headingOccurrence = 1)) }
        composeRule.runOnIdle { openedUri = SOURCE.ref.value }
        composeRule.runOnIdle { noteBody = body }
    }

    /** 今のノートで見出しを置いた後に画面を作り直しても、見出しは上端のまま。 */
    @Test
    fun 今のノートで置いた見出しは画面を作り直しても上端のまま() {
        val restoration = StateRestorationTester(composeRule)
        restoration.setContent { AppTheme(darkTheme = false) { MainBody() } }
        openMainAtHeading(SHORT_TAIL_BODY)
        assertAtHeading(position.listState)

        restoration.emulateSavedInstanceStateRestore()

        assertAtHeading(position.listState)
    }

    /** 今のノートで置いた後に画像の寸法が決まって縮んでも、見出しは上端のまま。 */
    @Test
    fun 今のノートで置いた後に画像の寸法が決まって縮んでも見出しは上端のまま() {
        val loader = GateImageLoader()
        composeRule.setContent { AppTheme(darkTheme = false) { MainBody(loader) } }
        openMainAtHeading(IMAGE_TAIL_BODY)
        assertAtHeading(position.listState)

        loader.settle(width = 800, height = 200)

        assertAtHeading(position.listState)
    }

    /** 画像を測る前に利用者が自分で送ったら、測り終えても見出しへ引き戻さない。 */
    @Test
    fun 画像を測る前に利用者が送ったら_測り終えても見出しへ戻さない() {
        val loader = GateImageLoader()
        composeRule.setContent { AppTheme(darkTheme = false) { MainBody(loader) } }
        openMainAtHeading(IMAGE_TAIL_BODY)
        assertAtHeading(position.listState)

        composeRule.runOnIdle { scope.launch { position.listState.scrollBy(-150f) } }
        composeRule.waitForIdle()
        loader.settle(width = 800, height = 200)
        composeRule.waitForIdle()

        composeRule.runOnIdle {
            val list = position.listState
            assertFalse(
                "利用者が離れた後に見出しへ引き戻した",
                list.firstVisibleItemIndex == SHORT_TAIL_HEADING && list.firstVisibleItemScrollOffset == 0
            )
        }
    }

    /**
     * 置いた後に窓が何フレームもかけて広がっても（全画面に入ってシステムバーが隠れる間）、見出しは上端のまま。
     * 広がるたびに末尾で止まるので、窓が変わるたびに足りない分を足し直す。
     */
    @Test
    fun 置いた後に窓が少しずつ広がっても見出しは上端のまま() {
        var tall by mutableStateOf(false)
        composeRule.setContent {
            AppTheme(darkTheme = false) {
                val height by animateDpAsState(if (tall) 900.dp else 600.dp, animationSpec = tween(durationMillis = 400), label = "窓の高さ")
                MainBody(height = height)
            }
        }
        openMainAtHeading(SHORT_TAIL_BODY)
        assertAtHeading(position.listState)

        composeRule.runOnIdle { tall = true }

        assertAtHeading(position.listState)
    }

    /**
     * 全画面から戻すとき、置いたままの印があれば、返された位置が見出しから外れていても（全画面の中で末尾で止まっていた）置き直す。
     * 返された位置のままだと、一覧はまだ先へ送れるので、送りが届くのを待ち続けて見出しへ戻らない。
     */
    @Test
    fun 置いたままの印で戻すと_返された位置が見出しから外れていても置き直す() {
        composeRule.setContent { AppTheme(darkTheme = false) { MainBody() } }
        openMainAtHeading(SHORT_TAIL_BODY)
        assertAtHeading(position.listState)
        val px = composeRule.runOnIdle { position.endSpace.bottomPx }

        composeRule.runOnIdle {
            position.takeOver(
                block = SHORT_TAIL_HEADING - 2,
                offset = 10,
                space = EndSpaceSnapshot(bottomPx = px, block = SHORT_TAIL_HEADING, replace = true)
            )
        }

        assertAtHeading(position.listState)
    }

    // ── 全画面との往復 ──────────────────────────────────────────────

    private var fullscreen by mutableStateOf(false)
    private lateinit var fullscreenList: LazyListState
    private lateinit var fullscreenSpace: EndSpace

    /**
     * 通常→全画面→通常の往復でも見出しは上端のまま。全画面は窓が高いので、運んだ余白だけでは足りず、全画面で置き直す。
     * 戻るときは全画面の位置と余白を通常へ返す。
     */
    @Test
    fun 全画面との往復でも見出しは上端のまま() {
        composeRule.setContent {
            AppTheme(darkTheme = false) {
                MainBody(showBody = !fullscreen)
                if (fullscreen) {
                    // 本番（FullscreenNoteScreen）と同じく、通常の位置から始め、余白を運ぶ。
                    val tab = position.listState
                    fullscreenList = rememberLazyListState(tab.firstVisibleItemIndex, tab.firstVisibleItemScrollOffset)
                    fullscreenSpace = rememberCarriedEndSpace(position, fullscreenList)
                    Box(modifier = Modifier.requiredSize(width = 400.dp, height = 800.dp)) {
                        MarkdownNoteContent(
                            content = "",
                            listState = fullscreenList,
                            precomputedBlocks = buildNoteSectionModel(SHORT_TAIL_BODY).blocks,
                            endSpace = fullscreenSpace
                        )
                    }
                }
            }
        }
        openMainAtHeading(SHORT_TAIL_BODY)
        assertAtHeading(position.listState)

        composeRule.runOnIdle { fullscreen = true }
        composeRule.waitForIdle()
        assertAtHeading(fullscreenList)

        composeRule.runOnIdle {
            position.takeOver(fullscreenList, fullscreenSpace)
            fullscreen = false
        }
        assertAtHeading(position.listState)
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

    /** 寸法を [settle] まで返さない画像。中身は読まない（枠の高さだけを見る）。 */
    private class GateImageLoader : NoteImageLoader {
        private val gate = CompletableDeferred<NoteImageMeasurement>()

        fun settle(width: Int, height: Int) {
            gate.complete(NoteImageMeasurement.Measured(width, height))
        }

        override suspend fun measure(image: MarkdownBlock.Image): NoteImageMeasurement = gate.await()

        override suspend fun load(image: MarkdownBlock.Image, targetWidthPx: Int): NoteImageContent = awaitCancellation()
    }

    private companion object {
        val SOURCE = RelatedNote(title = "出どころのノート", ref = DocumentRef("content://vault/source.md"), isWikilinked = false)

        /** 2つ目の「まとめ」の位置。 */
        const val SHORT_TAIL_HEADING = 20

        /** 2つ目の「まとめ」の後ろに2段落しかない本文。見出しから後ろは画面より短い。 */
        val SHORT_TAIL_BODY = (0 until 23).joinToString("\n\n") { if (it % 20 == 0) "## まとめ" else "段落$it の本文です。" }

        /** 2つ目の「まとめ」の後ろに横長の画像と1段落。画像を測るまでは見出しから後ろが画面より長い。 */
        val IMAGE_TAIL_BODY = (0 until 23).joinToString("\n\n") {
            when {
                it % 20 == 0 -> "## まとめ"
                it == 21 -> "![](wide.png)"
                else -> "段落$it の本文です。"
            }
        }
    }
}
