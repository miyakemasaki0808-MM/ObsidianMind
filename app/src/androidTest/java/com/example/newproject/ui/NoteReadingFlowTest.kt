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
import com.example.newproject.model.NoteUiState
import com.example.newproject.model.state.NoteState
import com.example.newproject.ui.screen.FullscreenNoteScreen
import com.example.newproject.ui.screen.NoteReaderTab
import com.example.newproject.ui.theme.AppTheme
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
 * 判断の基準は「JVMで書けないか」を先に問うこと（→ design/instrumentation_testing 判断1）。
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

    // --- 補助 -----------------------------------------------------------------

    @Composable
    private fun ReaderTab(
        state: NoteUiState,
        model: NoteSectionModel?,
        listState: LazyListState,
        onReadingProgress: (Int, Float, Int, String?) -> Unit = { _, _, _, _ -> }
    ) {
        NoteReaderTab(
            uiState = state,
            sectionModel = model,
            imageLoader = null,
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

        /** 段落だけを並べた長文。ブロック番号を本文へ入れて可視位置を特定できるようにする。 */
        val LONG_BODY = (0 until 40).joinToString("\n\n") { "${markerAt(it)} の本文です。" }

        fun markerAt(index: Int) = "段落$index"
    }
}
