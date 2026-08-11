package com.example.newproject.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.newproject.domain.markdown.NoteSectionModel
import com.example.newproject.domain.markdown.buildNoteSectionModel
import com.example.newproject.model.NoteImageFailure
import com.example.newproject.domain.markdown.MarkdownBlock
import com.example.newproject.ui.markdown.NoteImageContent
import com.example.newproject.ui.markdown.NoteImageMeasurements
import com.example.newproject.ui.markdown.NoteImageMeasurement
import com.example.newproject.ui.markdown.NoteImageLoader
import com.example.newproject.model.NoteUiState
import com.example.newproject.model.state.NoteState
import com.example.newproject.ui.screen.FullscreenNoteScreen
import com.example.newproject.ui.screen.NoteReaderTab
import com.example.newproject.ui.theme.AppTheme
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
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
                        onOpenSummary = {},
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
                        onOpenSummary = {},
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
        val measuredOnce = loader.measureCount

        composeRule.runOnIdle { fullscreen = true }
        composeRule.waitForIdle()

        assertEquals(
            "全画面で測り直している（寸法が共有されていない）",
            measuredOnce,
            loader.measureCount
        )
        assertTrue(
            "全画面で測り直している（寸法が共有されていない）",
            measurements.measuredReferences().isNotEmpty()
        )
    }

    /** 測定を保留したまま止められるローダ。**未計測の状態を作るために要る。** */
    private class PendingImageLoader : NoteImageLoader {
        private val gate = CompletableDeferred<NoteImageMeasurement>()

        @Volatile
        var measureCount = 0
            private set

        fun settle(width: Int, height: Int) {
            gate.complete(NoteImageMeasurement.Measured(width, height))
        }

        override suspend fun measure(image: MarkdownBlock.Image): NoteImageMeasurement {
            measureCount++
            return gate.await()
        }

        override suspend fun load(image: MarkdownBlock.Image, targetWidthPx: Int): NoteImageContent =
            NoteImageContent.Failed(NoteImageFailure.Broken)
    }

    // --- 補助 -----------------------------------------------------------------

    @Composable
    private fun ReaderTab(
        state: NoteUiState,
        model: NoteSectionModel?,
        listState: LazyListState,
        loader: NoteImageLoader? = null,
        measurements: NoteImageMeasurements? = null,
        onReadingProgress: (Int, Float, Int, String?) -> Unit = { _, _, _, _ -> }
    ) {
        NoteReaderTab(
            uiState = state,
            sectionModel = model,
            imageLoader = loader,
            imageMeasurements = measurements,
            noteListState = listState,
            onSelectVault = {},
            onRandomNote = {},
            onSuggestionTap = {},
            onDismissSectionChat = {},
            onEndSectionChat = {},
            onGenerateQuiz = { _, _ -> },
            onOpenQuizResult = {},
            onEnterFullscreen = {},
            onReadingProgress = onReadingProgress,
            onDismissReadingTrace = {},
            onOpenReflection = {},
            onVigilithActionChanged = {}
        )
    }

    private fun loadedNote(content: String) = NoteUiState(
        vaultSelected = true,
        noteState = NoteState.Success(title = TITLE, content = content)
    )

    private companion object {
        const val TITLE = "テスト用ノート"
        const val FIRST_PARAGRAPH = "最初の段落"

        val BODY = """
            # 見出し

            $FIRST_PARAGRAPH です。
        """.trimIndent()

        const val TARGET_BLOCK = 12

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

        /** 段落だけを並べた長文。ブロック番号を本文へ入れて可視位置を特定できるようにする。 */
        val LONG_BODY = (0 until 40).joinToString("\n\n") { "${markerAt(it)} の本文です。" }

        fun markerAt(index: Int) = "段落$index"
    }
}
