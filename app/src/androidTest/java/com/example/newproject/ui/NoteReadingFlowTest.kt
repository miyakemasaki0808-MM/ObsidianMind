package com.example.newproject.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.newproject.domain.markdown.MarkdownBlock
import com.example.newproject.domain.markdown.NoteSectionModel
import com.example.newproject.domain.markdown.buildNoteSectionModel
import com.example.newproject.model.NoteImageFailure
import com.example.newproject.model.NoteUiState
import com.example.newproject.model.ReunionKind
import com.example.newproject.model.MarginMemo
import com.example.newproject.model.DocumentRef
import com.example.newproject.model.RelatedNote
import com.example.newproject.model.SectionRef
import com.example.newproject.model.state.RelatedNotesState
import com.example.newproject.model.state.SideReadingState
import com.example.newproject.model.state.SectionChatState
import com.example.newproject.model.state.SectionSummary
import com.example.newproject.model.state.MarginMemoDraft
import com.example.newproject.model.state.MarginMemoState
import com.example.newproject.model.state.NoteState
import com.example.newproject.model.state.ReadingTraceCard
import com.example.newproject.ui.markdown.NoteImageContent
import com.example.newproject.ui.markdown.NoteImageLoader
import com.example.newproject.ui.markdown.NoteImageMeasurement
import com.example.newproject.ui.markdown.NoteImageMeasurements
import com.example.newproject.ui.screen.FullscreenNoteScreen
import com.example.newproject.ui.screen.NoteReaderTab
import com.example.newproject.ui.theme.AppTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 読書画面のうち、**実端末でしか確かめられないもの**だけを対象にする。
 *
 * ## ここへ何を書くか
 *
 * 判断の基準は「JVMで書けないか」を先に問うこと（→ system/instrumentation_testing 判断1）。
 * 純関数の値の伝播や分岐はJVM側が覆っているので、ここへ持ち込まない。
 * **残すのは Compose の実測（レイアウト・可視判定・再コンポーズ）が要るものだけ。**
 *
 * ## Fake も ViewModel も要らない理由
 *
 * [NoteReaderTab] と [FullscreenNoteScreen] は `NoteUiState` と `LazyListState` を
 * **素の引数として受け取る**（UIに業務ロジックを置かない規約の副産物）。
 * したがって ViewModel を組み立てず、状態を直接渡せる。
 * `androidTest` からは `internal` 宣言が見えるので、これがそのまま成立する。
 *
 * ## 本文のブロック番号と LazyColumn の index は一致する
 *
 * `ReadingProgressReporter` が `visibleItemsInfo.last().index` をそのまま
 * ブロック番号として報告しているため。したがって本文を「段落0」「段落1」…と
 * 並べておけば、**可視位置を本文の文言で特定できる**。
 */
@RunWith(AndroidJUnit4::class)
class NoteReadingFlowTest {

    @get:Rule
    val composeRule = createComposeRule()

    /**
     * 解析結果が届くまで本文を描かない。
     *
     * **描いてしまうと、描画側のフォールバックが Main で解析をやり直す。**
     * `MarkdownNoteContent` は `precomputedBlocks ?: parseMarkdownBlocks(content)` を
     * 持つので、待っている間に本文を描くと最大1MBの解析が Main へ戻ってきて、
     * 別スレッドへ逃がした意味が消えるどころか退避ぶんだけ遅くなる
     * （→ architecture.md 2026-07-31 の決定3）。
     *
     * **JVMでは書けない** — フォールバックが働くかどうかは実際に描画してみないと出ない。
     */
    @Test
    fun 解析結果が届くまで本文を描かない() {
        composeRule.setContent {
            AppTheme(darkTheme = false) {
                ReaderTab(loadedNote(BODY), model = null, listState = rememberLazyListState())
            }
        }

        // タイトルと枠は先に出る（待っている間も画面は空にしない）。
        composeRule.onNodeWithText(TITLE).assertIsDisplayed()
        // 本文は1行も出ていない。
        composeRule.onNodeWithText(FIRST_PARAGRAPH, substring = true).assertDoesNotExist()
    }

    /** 解析が終われば本文が出る（上のガードが「常に描かない」になっていないこと）。 */
    @Test
    fun 解析結果が届いたら本文を描く() {
        composeRule.setContent {
            AppTheme(darkTheme = false) {
                ReaderTab(loadedNote(BODY), buildNoteSectionModel(BODY), rememberLazyListState())
            }
        }

        composeRule.onNodeWithText(FIRST_PARAGRAPH, substring = true).assertIsDisplayed()
    }

    /**
     * 全画面は、タブ側で読んでいた位置を引き継ぐ。
     *
     * **JVMでは書けない** — 引き継ぎは `LazyListState` の実測値
     * （`firstVisibleItemIndex` / `firstVisibleItemScrollOffset`）に依存し、
     * 実際にレイアウトしないと値が入らない。
     *
     * 判定は**先頭可視ブロックの本文**で行う。全画面はシステムバーを隠して
     * 表示域が変わるため、末尾可視ブロックは1つずれ得るが、引き継がれるのは
     * 先頭位置なのでそこを見る。
     */
    @Test
    fun 全画面はタブ側のスクロール位置を引き継ぐ() {
        val model = buildNoteSectionModel(LONG_BODY)
        lateinit var tabListState: LazyListState
        var fullscreen by mutableStateOf(false)

        composeRule.setContent {
            AppTheme(darkTheme = false) {
                // if の外に置いて、全画面へ切り替えても同じ状態を保つ。
                val listState = rememberLazyListState()
                remember { tabListState = listState }
                if (fullscreen) {
                    FullscreenNoteScreen(
                        uiState = loadedNote(LONG_BODY),
                        sectionModel = model,
                        imageLoader = null,
                        imageMeasurements = null,
                        tabListState = listState,
                        onExit = {},
                        onOpenMarginMemo = {},
                        onDismissMarginMemo = {},
                        memoDraft = MarginMemoDraft(),
                        onEditMarginMemo = { _, _ -> },
                        onSubmitMarginMemo = {},
                        onDeleteMarginMemo = {},
                        memoFocusIntent = false,
                        onMemoFocusIntentChange = {},
                        onReadingProgress = { _, _, _, _ -> }
                    )
                } else {
                    ReaderTab(loadedNote(LONG_BODY), model, listState)
                }
            }
        }
        composeRule.waitForIdle()

        composeRule.runOnIdle { runBlocking { tabListState.scrollToItem(TARGET_BLOCK) } }
        composeRule.waitForIdle()

        val visibleBlock = tabListState.firstVisibleItemIndex
        assertTrue("スクロールできていない（前提が崩れている）", visibleBlock > 0)
        composeRule.onNodeWithText(markerAt(visibleBlock), substring = true).assertIsDisplayed()

        composeRule.runOnIdle { fullscreen = true }
        composeRule.waitForIdle()

        // 全画面でも同じブロックが見えている＝位置が引き継がれた。
        composeRule.onNodeWithText(markerAt(visibleBlock), substring = true).assertIsDisplayed()
    }

    /**
     * 全画面に置く入口は ✎ だけで、押すと「この部分」のシートが全画面の上に出て書ける。
     * **シートに要約の行を出さない** — 要約は全画面に入る前のおさらい（→ features/note_fullscreen.md）。
     */
    @Test
    fun 全画面の書く入口はシートを出しそこで書けて要約の行は出ない() {
        val model = buildNoteSectionModel(LONG_BODY)
        var sheetVisible by mutableStateOf(false)
        var openCalls = 0
        composeRule.setContent {
            AppTheme(darkTheme = false) {
                FullscreenNoteScreen(
                    uiState = loadedNote(LONG_BODY).copy(
                        marginMemoState = MarginMemoState.Ready(memos = emptyList()),
                        isMarginMemoSheetVisible = sheetVisible
                    ),
                    sectionModel = model,
                    imageLoader = null,
                    imageMeasurements = null,
                    tabListState = rememberLazyListState(),
                    onExit = {},
                    onOpenMarginMemo = {
                        openCalls++
                        sheetVisible = true
                    },
                    onDismissMarginMemo = { sheetVisible = false },
                    memoDraft = MarginMemoDraft(),
                    onEditMarginMemo = { _, _ -> },
                    onSubmitMarginMemo = {},
                    onDeleteMarginMemo = {},
                    memoFocusIntent = false,
                    onMemoFocusIntentChange = {},
                    onReadingProgress = { _, _, _, _ -> }
                )
            }
        }
        composeRule.onNode(hasSetTextAction()).assertDoesNotExist()

        composeRule.onNodeWithContentDescription("このノートのメモ").performClick()
        composeRule.waitForIdle()

        assertEquals(1, openCalls)
        composeRule.onNode(hasSetTextAction()).assertIsDisplayed()
        composeRule.onNodeWithText("この節を要約").assertDoesNotExist()
    }

    /**
     * 読書進捗は、実際に見えているブロックを総数つきで報告する。
     *
     * **JVMでは書けない** — 何が可視かは実測でしか決まらない。
     * ここで固定するのは値そのものではなく**報告の整合性**
     * （index が総数の範囲に収まる／総数がモデルと一致する）。
     */
    @Test
    fun 読書進捗は総ブロック数と整合する範囲で報告される() {
        val model = buildNoteSectionModel(LONG_BODY)
        val reports = mutableListOf<Pair<Int, Int>>()

        composeRule.setContent {
            AppTheme(darkTheme = false) {
                ReaderTab(
                    state = loadedNote(LONG_BODY),
                    model = model,
                    listState = rememberLazyListState(),
                    onReadingProgress = { index, _, total, _ -> reports += index to total }
                )
            }
        }
        composeRule.waitForIdle()

        assertTrue("進捗が1件も報告されていない", reports.isNotEmpty())
        reports.forEach { (index, total) ->
            assertEquals("総ブロック数がモデルと一致しない", model.blocks.size, total)
            assertTrue("報告された index が総数の範囲外: $index / $total", index in 0 until total)
        }
    }


    // --- 寸法未確定の画像より後ろを報告しない --------------------------------

    /**
     * **測定を待っている間は、画像より後ろの進捗を報告しない。**
     *
     * 画像は寸法が取れるまで画面1枚ぶんで確保するが、**それは元画像の高さの上限ではない。**
     * 縦長画像なら実際は画面2〜3枚ぶんになり得るので、確保が足りない間に
     * スクロールすると**まだ読んでいない後続ブロックが可視になる。**
     * 最深到達点は後から下がらないため、この誤りはサイドカーへ永続化される。
     *
     * **JVMでは書けない** — 何が可視かは実測でしか決まらない。
     */
    @Test
    fun 寸法未確定の画像より後ろは進捗を報告しない() {
        val loader = PendingImageLoader()
        val model = buildNoteSectionModel(IMAGE_BODY)
        val measurements = NoteImageMeasurements()
        val reports = mutableListOf<Int>()
        lateinit var listState: LazyListState

        composeRule.setContent {
            AppTheme(darkTheme = false) {
                listState = rememberLazyListState()
                ReaderTab(
                    state = loadedNote(IMAGE_BODY),
                    model = model,
                    listState = listState,
                    loader = loader,
                    measurements = measurements,
                    onReadingProgress = { index, _, _, _ -> reports += index }
                )
            }
        }
        composeRule.waitForIdle()

        // 測定を止めたまま、画像より後ろへスクロールする。
        composeRule.runOnIdle { runBlocking { listState.scrollToItem(model.blocks.size - 1) } }
        composeRule.waitForIdle()

        assertTrue(
            "寸法未確定の画像より後ろが報告された: $reports（画像は index $IMAGE_BLOCK_INDEX）",
            reports.none { it > IMAGE_BLOCK_INDEX }
        )

        // 測定が終われば報告は再開する（常に止めたままにはしない）。
        // **先に画像へ戻す** — 画面外にある間は Composable ごと破棄されており、
        // 測定コルーチンも動いていないので、settle() だけでは記録されない。
        composeRule.runOnIdle { runBlocking { listState.scrollToItem(0) } }
        composeRule.waitForIdle()
        composeRule.runOnIdle { loader.settle(width = 800, height = 600) }
        composeRule.waitForIdle()

        composeRule.runOnIdle { runBlocking { listState.scrollToItem(model.blocks.size - 1) } }
        composeRule.waitForIdle()

        assertTrue("測定後も報告が再開しない: $reports", reports.any { it > IMAGE_BLOCK_INDEX })
    }

    /**
     * **続きから読むで画像を飛び越しても、その後の進捗は報告される**（→ features/reunion_card.md 判断6）。
     *
     * 飛び越した画像は描画されないので、描画の中の測定では測られない。未測定の画像より後ろは報告しない契約なので、
     * 送るときに飛び越した画像を測らないと、再開した後に読んだところが痕跡へ残らない。
     */
    @Test
    fun 続きから読むで画像を飛び越しても測り終えれば進捗を報告する() {
        val loader = FixedImageLoader(NoteImageMeasurement.Measured(width = 800, height = 2_400))
        val reports = showResumableNote(loader)

        composeRule.onNodeWithText("続きから読む").performClick()
        composeRule.waitForIdle()

        assertTrue("送った先の進捗が報告されない: $reports", reports.any { it >= RESUME_TARGET })
        assertTrue("飛び越した画像を測っていない", loader.measureCount >= 1)
    }

    /** **測り終えるまでは、飛んだ先も報告しない。** 止める検査そのものは緩めていない。 */
    @Test
    fun 続きから読むで飛び越した画像を測り終えるまでは飛んだ先を報告しない() {
        val loader = PendingImageLoader()
        val reports = showResumableNote(loader)

        composeRule.onNodeWithText("続きから読む").performClick()
        composeRule.waitForIdle()

        assertTrue("飛び越した画像を測りに行っていない", loader.measureCount >= 1)
        assertTrue(
            "測り終える前に画像より後ろが報告された: $reports（画像は index $RESUME_IMAGE_INDEX）",
            reports.none { it > RESUME_IMAGE_INDEX }
        )
    }

    /**
     * **測定待ちの間に全画面へ移っても、全画面が測り終えて報告を再開する**（修正レビューで固定した受理条件）。
     *
     * 通常画面のスコープで測っていたころは、画面が破棄された時点で測定がキャンセルされ、
     * 飛び越した画像は全画面でも画面外なので二度と測られなかった。
     * **実 NavHost ではなく、同じ位置を共有する2つの画面を切り替えて通常画面を破棄する。**
     */
    @Test
    fun 続きから読むの測定待ちで全画面へ移っても全画面で測り終えて進捗を報告する() {
        val loader = PendingImageLoader()
        val (reports, fullscreen) = showResumableNoteWithFullscreen(loader)

        composeRule.onNodeWithText("続きから読む").performClick()
        composeRule.waitForIdle()
        composeRule.runOnIdle { fullscreen.value = true }
        composeRule.waitForIdle()
        assertTrue("測り終える前に報告された: $reports", reports.none { it > RESUME_IMAGE_INDEX })

        composeRule.runOnIdle { loader.settle(width = 800, height = 2_400) }
        composeRule.waitForIdle()

        assertTrue("全画面で報告が再開しない: $reports", reports.any { it >= RESUME_TARGET })
    }

    /** 全画面から通常画面へ戻った場合も、同じ依頼を拾って回復する。カードの再押下も画像へ戻る操作も要らない。 */
    @Test
    fun 続きから読むの測定待ちで全画面から戻っても通常画面で測り終えて進捗を報告する() {
        val loader = PendingImageLoader()
        val (reports, fullscreen) = showResumableNoteWithFullscreen(loader)

        composeRule.onNodeWithText("続きから読む").performClick()
        composeRule.waitForIdle()
        composeRule.runOnIdle { fullscreen.value = true }
        composeRule.waitForIdle()
        composeRule.runOnIdle { fullscreen.value = false }
        composeRule.waitForIdle()

        composeRule.runOnIdle { loader.settle(width = 800, height = 2_400) }
        composeRule.waitForIdle()

        assertTrue("通常画面で報告が再開しない: $reports", reports.any { it >= RESUME_TARGET })
    }

    /** 通常画面と全画面を、同じ一覧の位置と同じ測定の入れ物で切り替えられるように出す。 */
    private fun showResumableNoteWithFullscreen(
        loader: NoteImageLoader
    ): Pair<MutableList<Int>, androidx.compose.runtime.MutableState<Boolean>> {
        val model = buildNoteSectionModel(RESUME_IMAGE_BODY)
        val measurements = NoteImageMeasurements()
        val reports = mutableListOf<Int>()
        val fullscreen = mutableStateOf(false)
        val state = resumableState()
        composeRule.setContent {
            AppTheme(darkTheme = false) {
                val listState = rememberLazyListState()
                if (fullscreen.value) {
                    FullscreenNoteScreen(
                        uiState = state,
                        sectionModel = model,
                        imageLoader = loader,
                        imageMeasurements = measurements,
                        tabListState = listState,
                        onExit = {},
                        onOpenMarginMemo = {},
                        onDismissMarginMemo = {},
                        memoDraft = MarginMemoDraft(),
                        onEditMarginMemo = { _, _ -> },
                        onSubmitMarginMemo = {},
                        onDeleteMarginMemo = {},
                        memoFocusIntent = false,
                        onMemoFocusIntentChange = {},
                        onReadingProgress = { index, _, _, _ -> reports += index }
                    )
                } else {
                    ReaderTab(
                        state = state,
                        model = model,
                        listState = listState,
                        loader = loader,
                        measurements = measurements,
                        onReadingProgress = { index, _, _, _ -> reports += index }
                    )
                }
            }
        }
        composeRule.waitForIdle()
        return reports to fullscreen
    }

    private fun resumableState() = loadedNote(RESUME_IMAGE_BODY).copy(
        readingTraceCard = ReadingTraceCard(
            visitCount = 2,
            lastVisitAtMillis = 0L,
            lastSectionTitle = null,
            lastProgressPercent = 90,
            resumeBlockIndex = RESUME_TARGET
        )
    )

    /** 画像を第[RESUME_IMAGE_INDEX]ブロックに持つ長い本文を、送り先つきの再会カードと一緒に出す。 */
    private fun showResumableNote(loader: NoteImageLoader): MutableList<Int> {
        val model = buildNoteSectionModel(RESUME_IMAGE_BODY)
        val measurements = NoteImageMeasurements()
        val reports = mutableListOf<Int>()
        val state = resumableState()
        composeRule.setContent {
            AppTheme(darkTheme = false) {
                ReaderTab(
                    state = state,
                    model = model,
                    listState = rememberLazyListState(),
                    loader = loader,
                    measurements = measurements,
                    onReadingProgress = { index, _, _, _ -> reports += index }
                )
            }
        }
        composeRule.waitForIdle()
        assertTrue("最初の画面に画像が入っている（飛び越しにならない）: $reports", reports.none { it >= RESUME_IMAGE_INDEX })
        return reports
    }

    /**
     * **全画面へ入っても測り直さない。**
     *
     * 全画面は位置を引き継ぐのに新しいコンポジションなので、寸法を共有しないと
     * **入った瞬間に未計測へ戻る**。その状態で引き継いだオフセットが仮の高さを超えると、
     * 後続ブロックが可視になって到達率が水増しされる。
     */
    @Test
    fun 全画面へ入っても画像の寸法を測り直さない() {
        val loader = PendingImageLoader()
        val model = buildNoteSectionModel(IMAGE_BODY)
        val measurements = NoteImageMeasurements()
        var fullscreen by mutableStateOf(false)

        composeRule.setContent {
            AppTheme(darkTheme = false) {
                val listState = rememberLazyListState()
                if (fullscreen) {
                    FullscreenNoteScreen(
                        uiState = loadedNote(IMAGE_BODY),
                        sectionModel = model,
                        imageLoader = loader,
                        imageMeasurements = measurements,
                        tabListState = listState,
                        onExit = {},
                        onOpenMarginMemo = {},
                        onDismissMarginMemo = {},
                        memoDraft = MarginMemoDraft(),
                        onEditMarginMemo = { _, _ -> },
                        onSubmitMarginMemo = {},
                        onDeleteMarginMemo = {},
                        memoFocusIntent = false,
                        onMemoFocusIntentChange = {},
                        onReadingProgress = { _, _, _, _ -> }
                    )
                } else {
                    ReaderTab(
                        state = loadedNote(IMAGE_BODY),
                        model = model,
                        listState = listState,
                        loader = loader,
                        measurements = measurements
                    )
                }
            }
        }
        composeRule.runOnIdle { loader.settle(width = 800, height = 600) }
        composeRule.waitForIdle()
        val readOnce = loader.readCount

        composeRule.runOnIdle { fullscreen = true }
        composeRule.waitForIdle()

        // **見るのは「読み直したか」であって「呼んだか」ではない。** 呼び出しは
        // 世代が変わっていないことの確認に要る（→ 上書き後の縦横比のテスト）。
        assertEquals(
            "全画面でヘッダを読み直している（寸法が共有されていない）",
            readOnce,
            loader.readCount
        )
        assertTrue(
            "全画面で測り直している（寸法が共有されていない）",
            measurements.measuredReferences().isNotEmpty()
        )
    }

    /**
     * **上書きで縦横比が変わったら、確保する高さも新しい比率になる。**
     *
     * 共有の入れ物は参照文字列だけを鍵にするので、旧測定が残っていると
     * 新しいBitmapを**古い比率の枠へ**収めてしまう。Gateway単体のテストでは
     * この面を通らない（表示側のキャッシュを経由しないため）。
     */
    @Test
    fun 上書きで縦横比が変わったら確保高も新しい比率になる() {
        val model = buildNoteSectionModel(IMAGE_BODY)
        val measurements = NoteImageMeasurements()
        val block = model.blocks.filterIsInstance<MarkdownBlock.Image>().first()
        // 上書き前の測定（4:3）を共有済みにする。
        measurements.record(block, NoteImageMeasurement.Measured(width = 400, height = 300))
        // 上書き後は縦長（2:3）。
        val loader = FixedImageLoader(NoteImageMeasurement.Measured(width = 400, height = 600))

        composeRule.setContent {
            AppTheme(darkTheme = false) {
                ReaderTab(
                    state = loadedNote(IMAGE_BODY),
                    model = model,
                    listState = rememberLazyListState(),
                    loader = loader,
                    measurements = measurements
                )
            }
        }
        composeRule.waitForIdle()

        val bounds = composeRule
            .onNodeWithContentDescription("photo.png", substring = true)
            .getUnclippedBoundsInRoot()
        val ratio = (bounds.bottom - bounds.top).value / (bounds.right - bounds.left).value

        assertTrue(
            "旧測定の比率のまま描いている（縦横比 $ratio、期待は約1.5）",
            ratio in 1.3f..1.7f
        )
    }

    /**
     * **確かめ終えるまでBitmapを読み始めない。**
     *
     * 共有された旧寸法で先に復号すると、新しいBitmapが古い比率の枠へ収まった状態が
     * **見えている時間**になる。最終状態だけを見るテストでは通ってしまうので、
     * 測定をゲートで止めて完了前後の両方を観測する。
     */
    @Test
    fun 再確認が終わるまでBitmapを読み始めない() {
        val model = buildNoteSectionModel(IMAGE_BODY)
        val measurements = NoteImageMeasurements()
        val block = model.blocks.filterIsInstance<MarkdownBlock.Image>().first()
        // 上書き前の測定を共有済みにする（全画面へ入り直した状況）。
        measurements.record(block, NoteImageMeasurement.Measured(width = 400, height = 300))
        val loader = PendingImageLoader()

        composeRule.setContent {
            AppTheme(darkTheme = false) {
                ReaderTab(
                    state = loadedNote(IMAGE_BODY),
                    model = model,
                    listState = rememberLazyListState(),
                    loader = loader,
                    measurements = measurements
                )
            }
        }
        composeRule.waitForIdle()

        assertEquals("再確認の完了前に読み込みを始めている", 0, loader.loadCount)

        composeRule.runOnIdle { loader.settle(width = 400, height = 600) }
        composeRule.waitForIdle()

        assertTrue("再確認が終わっても読み込みが始まらない", loader.loadCount > 0)
    }

    /**
     * **失敗した測定でも、新しいコンポジションでは確かめ直す。**
     *
     * 「高さが確定した」ことと「もう試さない」ことを畳むと、TTL後に画像を足しても
     * プロバイダが復旧しても、失敗表示がノート切替まで残る。
     */
    @Test
    fun 失敗した測定でも新しいコンポジションで確かめ直す() {
        val model = buildNoteSectionModel(IMAGE_BODY)
        val measurements = NoteImageMeasurements()
        val block = model.blocks.filterIsInstance<MarkdownBlock.Image>().first()
        // 前回は見つからなかった、という共有状態から始める。
        measurements.record(block, NoteImageMeasurement.Failed(NoteImageFailure.NotFound))
        val loader = FixedImageLoader(NoteImageMeasurement.Measured(width = 400, height = 300))

        composeRule.setContent {
            AppTheme(darkTheme = false) {
                ReaderTab(
                    state = loadedNote(IMAGE_BODY),
                    model = model,
                    listState = rememberLazyListState(),
                    loader = loader,
                    measurements = measurements
                )
            }
        }
        composeRule.waitForIdle()

        assertTrue("失敗を共有していると測り直さない", loader.measureCount > 0)
        composeRule
            .onNodeWithContentDescription("photo.png", substring = true)
            .assertIsDisplayed()
    }

    /**
     * 測定を保留したまま止められるローダ。**未計測の状態を作るために要る。**
     *
     * **本番の口と同じ契約にする** — 呼ばれること自体は安く、世代が変わったときだけ
     * ヘッダを読み直す（`NoteImageGateway` は世代を鍵にした寸法キャッシュを持つ）。
     * [measureCount]（呼ばれた回数）と [readCount]（実際に読んだ回数）を分けないと、
     * 「呼ばない」を守っているのか「読まない」を守っているのかが混ざる。
     */
    private class PendingImageLoader : NoteImageLoader {
        private val gate = CompletableDeferred<NoteImageMeasurement>()

        @Volatile
        var measureCount = 0
            private set

        @Volatile
        var readCount = 0
            private set

        @Volatile
        private var settled: NoteImageMeasurement? = null

        fun settle(width: Int, height: Int) {
            gate.complete(NoteImageMeasurement.Measured(width, height))
        }

        override suspend fun measure(image: MarkdownBlock.Image): NoteImageMeasurement {
            measureCount++
            settled?.let { return it }
            val measured = gate.await()
            readCount++
            settled = measured
            return measured
        }

        override suspend fun load(image: MarkdownBlock.Image, targetWidthPx: Int): NoteImageContent {
            loadCount++
            return NoteImageContent.Failed(NoteImageFailure.Broken)
        }

        @Volatile
        var loadCount = 0
            private set
    }

    /** 測定結果を即座に返すローダ。**上書き後の世代を演じる。** */
    private class FixedImageLoader(
        private val measurement: NoteImageMeasurement
    ) : NoteImageLoader {

        @Volatile
        var measureCount = 0
            private set

        override suspend fun measure(image: MarkdownBlock.Image): NoteImageMeasurement {
            measureCount++
            return measurement
        }

        override suspend fun load(image: MarkdownBlock.Image, targetWidthPx: Int): NoteImageContent =
            NoteImageContent.Loaded(
                Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).asImageBitmap()
            )
    }

    // --- 補助 -----------------------------------------------------------------

    /**
     * **低い横長の画面でも、再会カードがノート名と本文を隠さない**（→ features/rediscover.md 判断6）。
     *
     * 縦に積むと、見出し・操作ボタン・カードが先に高さを取り、本文は残りだけになる。
     * 左右2列なら本文は高さをまるごと使う。**カードは左列でスクロールして届く**ことも見る。
     * 枠は多くの端末の縦幅に収まる大きさで、低い横長の条件（高さ480dp未満・幅＞高さ）を作る。
     */
    @Test
    fun 低い横長の画面では再会カードがあってもノート名と本文が見える() {
        val state = loadedNote(BODY).copy(
            readingTraceCard = ReadingTraceCard(
                visitCount = 3,
                lastVisitAtMillis = 0L,
                lastSectionTitle = "見出し",
                lastProgressPercent = 50,
                // **本文の文言（[FIRST_PARAGRAPH]・[TITLE]）をカードの文へ入れない。** 部分一致の検索が
                // カードにも当たり、本文が見えているかを判定できなくなる。
                aiSummary = "直前は導入を読んでいた。この先は説明が続き、例が3つ並ぶ。",
                aiSummaryKind = ReunionKind.Passage,
                hasMemos = true,
                resumeBlockIndex = 0
            )
        )
        composeRule.setContent {
            AppTheme(darkTheme = false) {
                Box(modifier = Modifier.requiredSize(width = 400.dp, height = 300.dp)) {
                    ReaderTab(state, buildNoteSectionModel(BODY), rememberLazyListState())
                }
            }
        }

        composeRule.onNodeWithText(TITLE).assertIsDisplayed()
        composeRule.onNodeWithText(FIRST_PARAGRAPH, substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("読んだ").performScrollTo().assertIsDisplayed()
        // スクロールしても本文は隠れない（左列だけが動く）。
        composeRule.onNodeWithText(FIRST_PARAGRAPH, substring = true).assertIsDisplayed()
    }

    /**
     * **メモのある見出しの脇に、件数つきの印が出る**（→ features/margin_pane.md §5.6）。色だけにせず件数を読み上げ、
     * 押すとメモの面を出す（縦積みの窓ではシート）。
     */
    @Test
    fun メモのある見出しの脇に件数つきの印が出て_押すとメモの面を出す() {
        var opened = 0
        val state = loadedNote(BODY).copy(
            marginMemoState = MarginMemoState.Ready(memos = listOf(MarginMemo("見出しのメモ", 1L, "見出し")))
        )
        composeRule.setContent {
            AppTheme(darkTheme = false) {
                ReaderTab(state, buildNoteSectionModel(BODY), rememberLazyListState(), onOpenMarginMemo = { opened++ })
            }
        }

        composeRule.onNodeWithContentDescription("この節のメモ 1件").performClick()
        composeRule.runOnIdle { assertEquals(1, opened) }
    }

    /**
     * **本文を上端まで送れない短い2節のノートでも、印の1回の操作で行き先の節のメモが見える**（→ features/margin_pane.md §5.6）。
     * 面の節は本文についていくが、短い節は上端まで来ないので、それを当てにすると「ほかの節」に畳まれたまま残る。
     *
     * **シートは閉じた状態から始める。** 入口が本番と同じく面を開く状態更新を通さないと、閉じた面から開けない不具合を踏めない
     * （最初から開いた面で組むと、半分の面に本文が削られて印にも届かなかった）。
     */
    @Test
    fun 短い2節のノートでも_閉じたシートから印で行き先の節のメモが見える() {
        setReaderWithSheet(loadedNote(SHORT_TWO_SECTIONS).withMemos(MarginMemo("節Bのメモ", 1L, "節B")), SHORT_TWO_SECTIONS)
        composeRule.onNodeWithText("節Bのメモ").assertDoesNotExist()

        composeRule.onNodeWithContentDescription("この節のメモ 1件").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("節Bのメモ").assertIsDisplayed()
    }

    @Test
    fun 短い2節のノートでも_ペインで印から行き先の節のメモが見える() {
        val state = loadedNote(SHORT_TWO_SECTIONS).withMemos(MarginMemo("節Bのメモ", 1L, "節B"))
        composeRule.setContent {
            AppTheme(darkTheme = false) {
                Box(modifier = Modifier.requiredSize(width = 960.dp, height = 720.dp)) {
                    ReaderTab(
                        state,
                        buildNoteSectionModel(SHORT_TWO_SECTIONS),
                        rememberLazyListState(),
                        marginPaneOpen = true,
                        expandedWidth = true
                    )
                }
            }
        }
        composeRule.onNodeWithText("節Bのメモ").assertDoesNotExist()

        composeRule.onNodeWithContentDescription("この節のメモ 1件").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("節Bのメモ").assertIsDisplayed()
    }

    /** **末尾の短い節**の印でも、閉じたシートから1回で行き先のメモが見える。本文はそこまで送れない。 */
    @Test
    fun 長いノートの末尾の短い節でも_閉じたシートから印で行き先の節のメモが見える() {
        val listState = LazyListState()
        setReaderWithSheet(
            loadedNote(LONG_WITH_SHORT_TAIL).withMemos(MarginMemo("付記のメモ", 1L, "付記")),
            LONG_WITH_SHORT_TAIL,
            listState
        )
        composeRule.runOnIdle { runBlocking { listState.scrollToItem(buildNoteSectionModel(LONG_WITH_SHORT_TAIL).blocks.lastIndex) } }
        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription("この節のメモ 1件").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("付記のメモ").assertIsDisplayed()
    }

    /** 再会カードの「前回のメモを見る」も、**閉じたシートから**1回でメモの並びまで届く。 */
    @Test
    fun 再会カードの前回のメモを見るで_閉じたシートからメモの並びが見える() {
        val state = loadedNote(SHORT_TWO_SECTIONS)
            .withMemos(MarginMemo("節Bのメモ", 1L, "節B"))
            .copy(
                readingTraceCard = ReadingTraceCard(
                    visitCount = 2,
                    lastVisitAtMillis = 0L,
                    lastSectionTitle = "節A",
                    lastProgressPercent = 50,
                    aiSummary = null,
                    aiSummaryKind = null,
                    hasMemos = true,
                    resumeBlockIndex = null
                )
            )
        setReaderWithSheet(state, SHORT_TWO_SECTIONS)

        composeRule.onNodeWithText("前回のメモを見る").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("節Bのメモ").assertIsDisplayed()
    }

    /**
     * **Fold を開いて画面を作り直しても、シートで書いていた入力のフォーカスをペインの入力欄が引き継ぐ**（→ features/margin_pane.md §5.4）。
     * 開いた直後は、しまう途中のシートとペインが一瞬だけ両方組まれる。どちらもフォーカスを取りにいくと、
     * しまわれるシートが持ち去ってペインに残らなかった（閉じる方向は面が1つなので引き継げていた）。
     * 窓を広げたうえで、画面の作り直しと復元を通す。
     */
    @Test
    fun Foldを開いて画面を作り直しても_ペインの入力欄がフォーカスを引き継ぐ() {
        val restoration = StateRestorationTester(composeRule)
        var opened by mutableStateOf(false)
        var state by mutableStateOf(loadedNote(SHORT_TWO_SECTIONS).withMemos().copy(isMarginMemoSheetVisible = true))
        restoration.setContent {
            AppTheme(darkTheme = false) {
                Box(modifier = Modifier.requiredSize(width = if (opened) 960.dp else 400.dp, height = 720.dp)) {
                    ReaderTab(
                        state,
                        buildNoteSectionModel(SHORT_TWO_SECTIONS),
                        rememberLazyListState(),
                        onOpenMarginMemo = { state = state.copy(isMarginMemoSheetVisible = true) },
                        onDismissMarginMemo = { state = state.copy(isMarginMemoSheetVisible = false) },
                        marginPaneOpen = true,
                        expandedWidth = opened
                    )
                }
            }
        }
        composeRule.onNode(hasSetTextAction()).performClick()
        composeRule.onNode(hasSetTextAction()).assertIsFocused()

        composeRule.runOnIdle { opened = true }
        restoration.emulateSavedInstanceStateRestore()
        composeRule.waitForIdle()

        composeRule.runOnIdle { assertEquals("シートが残った", false, state.isMarginMemoSheetVisible) }
        composeRule.onNode(hasSetTextAction() and isFocused()).assertExists()
    }

    /**
     * **面が出ていなければ、まだ頼んでいない節の要約ボタンは要約を始めるだけで、面を出さない**（→ features/margin_pane.md §5.4）。
     * 頼んだ節で押すと、シートを出して見せに行く。**ペインを出せる窓で設定が閉じていても、ペインは出さない。**
     */
    @Test
    fun 要約ボタンは_頼んでいない節では面を出さず_頼んだ節ではシートを出す() {
        var openCalls = 0
        val requests = mutableListOf<SectionRef>()
        var state by mutableStateOf(loadedNote(SHORT_TWO_SECTIONS).withMemos())
        composeRule.setContent {
            AppTheme(darkTheme = false) {
                Box(modifier = Modifier.requiredSize(width = 960.dp, height = 720.dp)) {
                    ReaderTab(
                        state,
                        buildNoteSectionModel(SHORT_TWO_SECTIONS),
                        rememberLazyListState(),
                        onOpenMarginMemo = { openCalls++ },
                        onRequestSectionSummary = { requests += it },
                        marginPaneOpen = false,
                        expandedWidth = true
                    )
                }
            }
        }

        composeRule.onNodeWithContentDescription("この節を要約").performClick()
        composeRule.waitForIdle()
        assertEquals(listOf(SectionRef("節A")), requests)
        assertEquals("頼んでいない節で面を出した", 0, openCalls)
        composeRule.onNode(hasSetTextAction()).assertDoesNotExist()

        state = state.copy(
            sectionChat = SectionChatState(
                listOf(SectionSummary(SectionRef("節A"), requestId = 1, sectionTitle = "節A", sectionContext = "", summary = "要約"))
            )
        )
        composeRule.onNodeWithContentDescription("この節の要約あり。タップで開く").performClick()
        composeRule.waitForIdle()
        assertEquals("頼んだ節でシートを出さなかった", 1, openCalls)
        composeRule.onNode(hasSetTextAction()).assertDoesNotExist()
    }

    /**
     * **余白ペインが出ているかを外殻へ知らせる**（→ features/margin_pane.md §5.1）。外殻はこれでタブのレールを畳む。
     * ペインの設定を閉じると、出ていないことを知らせてレールを戻させる。
     */
    @Test
    fun 余白ペインが出ているかを外殻へ知らせ_閉じると戻す() {
        val reports = mutableListOf<Boolean>()
        var paneOpen by mutableStateOf(true)
        composeRule.setContent {
            AppTheme(darkTheme = false) {
                Box(modifier = Modifier.requiredSize(width = 960.dp, height = 720.dp)) {
                    ReaderTab(
                        loadedNote(SHORT_TWO_SECTIONS).withMemos(),
                        buildNoteSectionModel(SHORT_TWO_SECTIONS),
                        rememberLazyListState(),
                        marginPaneOpen = paneOpen,
                        expandedWidth = true,
                        onMarginPaneVisibleChange = { reports += it }
                    )
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNode(hasSetTextAction()).assertExists()
        assertEquals("ペインが出ているのに知らせていない: $reports", true, reports.lastOrNull())

        paneOpen = false
        composeRule.waitForIdle()
        composeRule.onNode(hasSetTextAction()).assertDoesNotExist()
        assertEquals("ペインをしまったのに知らせていない: $reports", false, reports.lastOrNull())
    }

    // ── 並べ読み（→ features/margin_pane.md §5.9）───────────────────────────

    /**
     * ペインの「このノートの関連」から候補を右で開き、「← 余白へ戻る」で**選んだ一覧の場所へ戻る**。
     * 右で眺めている間は余白の面（入力欄）を出さない。
     */
    @Test
    fun ペインの関連から右で開き_余白へ戻ると関連の一覧へ戻る() {
        var state by mutableStateOf(
            loadedNote(SHORT_TWO_SECTIONS).withMemos()
                .copy(relatedNotesState = RelatedNotesState.Success(listOf(SIDE_NOTE, OTHER_NOTE), emptyList()))
        )
        composeRule.setContent {
            AppTheme(darkTheme = false) {
                Box(modifier = Modifier.requiredSize(width = 960.dp, height = 720.dp)) {
                    ReaderTab(
                        state,
                        buildNoteSectionModel(SHORT_TWO_SECTIONS),
                        rememberLazyListState(),
                        marginPaneOpen = true,
                        expandedWidth = true,
                        sideBlocks = buildNoteSectionModel(SIDE_BODY).blocks.takeIf { state.sideReading is SideReadingState.Ready },
                        onOpenSideReading = { note -> state = state.copy(sideReading = SideReadingState.Ready(note)) },
                        onCloseSideReading = { state = state.copy(sideReading = SideReadingState.Idle) }
                    )
                }
            }
        }
        composeRule.onNodeWithText("▸ このノートの関連 2件").performScrollTo().performClick()
        composeRule.onNodeWithText(SIDE_NOTE.title).performScrollTo().performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText(SIDE_PARAGRAPH).assertIsDisplayed()
        composeRule.onNode(hasSetTextAction()).assertDoesNotExist()

        composeRule.onNodeWithContentDescription("余白へ戻る").performClick()
        composeRule.waitForIdle()

        composeRule.onNode(hasSetTextAction()).assertExists()
        composeRule.onNodeWithText("▾ このノートの関連 2件").assertIsDisplayed()
    }

    /**
     * **右で読んでいる間に同じノートで画面を作り直しても、余白へ戻ると選んだ一覧へ戻る**（→ features/margin_pane.md §11）。
     * 右の本文は状態に残るので、戻る先の一覧の並びと開閉も同じだけ残らないと、戻った面で一覧が畳まれる。
     */
    @Test
    fun 右で読んでいる間に同じノートで作り直しても_余白へ戻ると関連の一覧へ戻る() {
        returnToRelatedAfterRestore(reloadBeforeRestore = false)
    }

    /**
     * 関連ノートを読み直している間（読み込み中）に作り直しても、候補は消えない。読み直しで届いた候補は
     * 並びを入れ替えず下へ足す。
     */
    @Test
    fun 関連ノートの読み直し中に作り直しても_余白へ戻ると関連の一覧と並びが残る() {
        returnToRelatedAfterRestore(reloadBeforeRestore = true)

        composeRule.runOnIdle {
            related = RelatedNotesState.Success(relatedNotes = listOf(OTHER_NOTE, SIDE_NOTE), aiNotes = listOf(THIRD_NOTE))
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("▾ このノートの関連 3件").assertIsDisplayed()
        // 送ると位置が動くので、送らずに並びの上下だけを比べる。
        val tops = listOf(SIDE_NOTE, OTHER_NOTE, THIRD_NOTE).map {
            composeRule.onNodeWithText(it.title).getUnclippedBoundsInRoot().top
        }
        assertEquals("並びが入れ替わった", tops.sorted(), tops)
    }

    /** **別のノートで作り直したら、関連の一覧を持ち越さない。** 保存値は別のノートを開いた状態で復元されることがある。 */
    @Test
    fun 別のノートで作り直すと_関連の一覧を持ち越さない() {
        val restoration = StateRestorationTester(composeRule)
        // **状態にしない。** 作り直す前に替えても、組み直しを起こさずに復元させるため。
        var current = loadedNote(SHORT_TWO_SECTIONS, targetUri = NOTE_A).withMemos()
            .copy(relatedNotesState = RelatedNotesState.Success(listOf(SIDE_NOTE, OTHER_NOTE), emptyList()))
        restoration.setContent {
            AppTheme(darkTheme = false) {
                Box(modifier = Modifier.requiredSize(width = 960.dp, height = 720.dp)) {
                    ReaderTab(
                        current,
                        buildNoteSectionModel(SHORT_TWO_SECTIONS),
                        rememberLazyListState(),
                        marginPaneOpen = true,
                        expandedWidth = true
                    )
                }
            }
        }
        composeRule.onNodeWithText("▸ このノートの関連 2件").performScrollTo().performClick()
        composeRule.onNodeWithText("▾ このノートの関連 2件").assertExists()

        current = loadedNote(SHORT_TWO_SECTIONS, targetUri = NOTE_B).withMemos()
            .copy(relatedNotesState = RelatedNotesState.Loading)
        restoration.emulateSavedInstanceStateRestore()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("このノートの関連", substring = true).assertDoesNotExist()
    }

    /** 関連ノートの状態。読み直し中の作り直しを作るために、テストの外から差し替える。 */
    private var related: RelatedNotesState by mutableStateOf(RelatedNotesState.Idle)

    /**
     * ペインで関連の一覧を開き、候補を右で読み、[reloadBeforeRestore] なら関連ノートを読み込み中にしてから、
     * 同じノートで画面を作り直す。余白へ戻ると、開いていた一覧が見える。
     * **1つのテストで1回だけ呼ぶ** — 画面を設定できるのはテストごとに1回で、2回目は例外になる。
     */
    private fun returnToRelatedAfterRestore(reloadBeforeRestore: Boolean) {
        val restoration = StateRestorationTester(composeRule)
        related = RelatedNotesState.Success(listOf(SIDE_NOTE, OTHER_NOTE), emptyList())
        var side by mutableStateOf<SideReadingState>(SideReadingState.Idle)
        restoration.setContent {
            AppTheme(darkTheme = false) {
                Box(modifier = Modifier.requiredSize(width = 960.dp, height = 720.dp)) {
                    ReaderTab(
                        loadedNote(SHORT_TWO_SECTIONS, targetUri = NOTE_A).withMemos()
                            .copy(relatedNotesState = related, sideReading = side),
                        buildNoteSectionModel(SHORT_TWO_SECTIONS),
                        rememberLazyListState(),
                        marginPaneOpen = true,
                        expandedWidth = true,
                        sideBlocks = buildNoteSectionModel(SIDE_BODY).blocks.takeIf { side is SideReadingState.Ready },
                        onOpenSideReading = { note -> side = SideReadingState.Ready(note) },
                        onCloseSideReading = { side = SideReadingState.Idle }
                    )
                }
            }
        }
        composeRule.onNodeWithText("▸ このノートの関連 2件").performScrollTo().performClick()
        composeRule.onNodeWithText(SIDE_NOTE.title).performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText(SIDE_PARAGRAPH).assertIsDisplayed()

        if (reloadBeforeRestore) composeRule.runOnIdle { related = RelatedNotesState.Loading }
        restoration.emulateSavedInstanceStateRestore()
        composeRule.waitForIdle()
        composeRule.onNodeWithText(SIDE_PARAGRAPH).assertIsDisplayed()

        composeRule.onNodeWithContentDescription("余白へ戻る").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("▾ このノートの関連 2件").assertIsDisplayed()
        composeRule.onNodeWithText(OTHER_NOTE.title).assertExists()
    }

    /** 「このノートへ移る」は、**右で読んでいたブロック**を添えて開き、左の一覧をその位置に置く。 */
    @Test
    fun このノートへ移ると_右で読んでいたブロックから左で開く() {
        val listState = LazyListState()
        var opened: RelatedNote? = null
        val state = loadedNote(LONG_BODY).withMemos().copy(sideReading = SideReadingState.Ready(SIDE_NOTE))
        composeRule.setContent {
            AppTheme(darkTheme = false) {
                Box(modifier = Modifier.requiredSize(width = 960.dp, height = 720.dp)) {
                    ReaderTab(
                        state,
                        buildNoteSectionModel(LONG_BODY),
                        listState,
                        marginPaneOpen = true,
                        expandedWidth = true,
                        sideBlocks = buildNoteSectionModel(LONG_SIDE_BODY).blocks,
                        onOpenNote = { opened = it }
                    )
                }
            }
        }
        composeRule.onNode(hasScrollToIndexAction() and hasAnyDescendant(hasText(sideMarkerAt(0), substring = true)))
            .performScrollToIndex(SIDE_TARGET_BLOCK)
        composeRule.onNodeWithText("このノートへ移る").performClick()
        composeRule.waitForIdle()

        composeRule.runOnIdle {
            assertEquals(SIDE_NOTE, opened)
            assertEquals(SIDE_TARGET_BLOCK, listState.firstVisibleItemIndex)
        }
    }

    /** **余白ペインでない並べ方になったら終える**（Fold を閉じた・✎ でしまった）。窓を縮めて縦積みにする。 */
    @Test
    fun 余白ペインでない並べ方になると_並べ読みを終える() {
        var wide by mutableStateOf(true)
        var closed = 0
        val state = loadedNote(SHORT_TWO_SECTIONS).withMemos().copy(sideReading = SideReadingState.Ready(SIDE_NOTE))
        composeRule.setContent {
            AppTheme(darkTheme = false) {
                Box(modifier = Modifier.requiredSize(width = if (wide) 960.dp else 400.dp, height = 720.dp)) {
                    ReaderTab(
                        state,
                        buildNoteSectionModel(SHORT_TWO_SECTIONS),
                        rememberLazyListState(),
                        marginPaneOpen = true,
                        expandedWidth = wide,
                        sideBlocks = buildNoteSectionModel(SIDE_BODY).blocks,
                        onCloseSideReading = { closed++ }
                    )
                }
            }
        }
        composeRule.onNodeWithText(SIDE_PARAGRAPH).assertIsDisplayed()
        composeRule.runOnIdle { assertEquals("ペインのまま終えた", 0, closed) }

        composeRule.runOnIdle { wide = false }
        composeRule.waitForIdle()

        composeRule.runOnIdle { assertEquals(1, closed) }
    }

    /**
     * 縦積みの窓で、**閉じたシートから始める**読書画面。入口がシートを出す依頼をすると、本番と同じく状態を開いた側へ替える。
     * 高さは印が本文に見える程度に取る（シートが出る前の本文で押すため）。
     */
    private fun setReaderWithSheet(initial: NoteUiState, body: String, listState: LazyListState? = null) {
        var state by mutableStateOf(initial)
        composeRule.setContent {
            AppTheme(darkTheme = false) {
                Box(modifier = Modifier.requiredSize(width = 400.dp, height = 900.dp)) {
                    ReaderTab(
                        state,
                        buildNoteSectionModel(body),
                        listState ?: rememberLazyListState(),
                        onOpenMarginMemo = { state = state.copy(isMarginMemoSheetVisible = true) },
                        onDismissMarginMemo = { state = state.copy(isMarginMemoSheetVisible = false) }
                    )
                }
            }
        }
    }

    private fun NoteUiState.withMemos(vararg memos: MarginMemo) =
        copy(marginMemoState = MarginMemoState.Ready(memos = memos.toList()))

    @Composable
    private fun ReaderTab(
        state: NoteUiState,
        model: NoteSectionModel?,
        listState: LazyListState,
        loader: NoteImageLoader? = null,
        measurements: NoteImageMeasurements? = null,
        onReadingProgress: (Int, Float, Int, String?) -> Unit = { _, _, _, _ -> },
        onOpenMarginMemo: () -> Unit = {},
        onDismissMarginMemo: () -> Unit = {},
        onRequestSectionSummary: (SectionRef) -> Unit = {},
        marginPaneOpen: Boolean = false,
        expandedWidth: Boolean = false,
        onMarginPaneVisibleChange: (Boolean) -> Unit = {},
        sideBlocks: List<MarkdownBlock>? = null,
        onOpenSideReading: (RelatedNote) -> Unit = {},
        onCloseSideReading: () -> Unit = {},
        onOpenNote: (RelatedNote) -> Unit = {}
    ) {
        var memoFocusIntent by rememberSaveable { mutableStateOf(false) }
        NoteReaderTab(
            uiState = state,
            sectionModel = model,
            imageLoader = loader,
            imageMeasurements = measurements,
            noteListState = listState,
            onSelectVault = {},
            onRandomNote = {},
            onRequestSectionSummary = onRequestSectionSummary,
            onRetrySectionSummary = {},
            onCancelSectionSummary = {},
            onOpenBooklet = {},
            onEnterFullscreen = {},
            onOpenMarginMemo = onOpenMarginMemo,
            onLoadMarginMemoForPane = {},
            // 読書の流れだけを見るテストは、どの端末でも縦に積む並べ方に固定する（既定）。
            marginPaneOpen = marginPaneOpen,
            onSetMarginPaneOpen = {},
            expandedWidth = expandedWidth,
            onMarginPaneVisibleChange = onMarginPaneVisibleChange,
            memoDraft = MarginMemoDraft(),
            onEditMarginMemo = { _, _ -> },
            onSubmitMarginMemo = {},
            onDeleteMarginMemo = {},
            onDismissMarginMemo = onDismissMarginMemo,
            memoFocusIntent = memoFocusIntent,
            onMemoFocusIntentChange = { memoFocusIntent = it },
            onReadingProgress = onReadingProgress,
            onDismissReadingTrace = {},
            sideReadingBlocks = sideBlocks,
            onOpenSideReading = onOpenSideReading,
            onCloseSideReading = onCloseSideReading,
            onOpenNote = onOpenNote
        )
    }

    private fun loadedNote(content: String, targetUri: String = "") = NoteUiState(
        vaultSelected = true,
        noteState = NoteState.Success(title = TITLE, content = content, targetUri = targetUri)
    )

    private companion object {
        const val TITLE = "テスト用ノート"
        const val NOTE_A = "content://vault/a.md"
        const val NOTE_B = "content://vault/b.md"
        const val FIRST_PARAGRAPH = "最初の段落"

        val BODY = """
            # 見出し

            $FIRST_PARAGRAPH です。
        """.trimIndent()

        const val TARGET_BLOCK = 12

        /** 長い4節の後に短い「付記」。付記の見出しは上端まで送れない。 */
        val LONG_WITH_SHORT_TAIL = buildString {
            listOf("第一", "第二", "第三", "第四").forEach { section ->
                append("## $section\n\n")
                (1..8).forEach { append("${section}の段落$it。読みながら思ったことを、本文の横に短く残す。\n\n") }
            }
            append("## 付記\n\n短い付記。\n")
        }

        /** 本文領域に収まる短い2節。どちらの見出しも上端まで送れない。 */
        val SHORT_TWO_SECTIONS = """
            # 節A

            短い段落。

            # 節B

            短い段落。
        """.trimIndent()

        /** 画像を1枚挟んだ本文。画像の後ろにも十分なブロックを置く。 */
        const val IMAGE_BLOCK_INDEX = 2
        val IMAGE_BODY = buildString {
            appendLine("段落0 の本文です。")
            appendLine()
            appendLine("段落1 の本文です。")
            appendLine()
            appendLine("![](assets/photo.png)")
            (3 until 30).forEach {
                appendLine()
                appendLine("${markerAt(it)} の本文です。")
            }
        }

        /** 続きから読むの送り先と、その手前で飛び越す画像の位置。 */
        const val RESUME_TARGET = 88
        const val RESUME_IMAGE_INDEX = 20

        /** 100ブロック。第[RESUME_IMAGE_INDEX]ブロックだけが画像で、最初の画面には入らない。 */
        val RESUME_IMAGE_BODY = (0 until 100).joinToString("\n\n") { index ->
            if (index == RESUME_IMAGE_INDEX) "![](assets/tall.png)" else "${markerAt(index)} の本文です。"
        }

        /** 段落だけを並べた長文。ブロック番号を本文へ入れて可視位置を特定できるようにする。 */
        val LONG_BODY = (0 until 40).joinToString("\n\n") { "${markerAt(it)} の本文です。" }

        fun markerAt(index: Int) = "段落$index"

        /** 並べ読みで右に開く関連ノート。 */
        val SIDE_NOTE = RelatedNote(title = "右のノート", ref = DocumentRef("content://vault/side.md"), isWikilinked = false)
        val OTHER_NOTE = RelatedNote(title = "もう1つの関連", ref = DocumentRef("content://vault/other.md"), isWikilinked = true)
        val THIRD_NOTE = RelatedNote(title = "後から届いた関連", ref = DocumentRef("content://vault/third.md"), isWikilinked = false)
        const val SIDE_PARAGRAPH = "右で眺める本文。"
        val SIDE_BODY = "# 右の見出し\n\n$SIDE_PARAGRAPH"

        /** 右で送る先。左の [LONG_BODY] にも十分な後続がある位置。 */
        const val SIDE_TARGET_BLOCK = 12
        val LONG_SIDE_BODY = (0 until 40).joinToString("\n\n") { "${sideMarkerAt(it)} の本文です。" }

        fun sideMarkerAt(index: Int) = "右の段落$index"
    }
}
