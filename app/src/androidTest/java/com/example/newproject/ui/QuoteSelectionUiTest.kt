package com.example.newproject.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.AnnotatedString
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.newproject.domain.markdown.NoteSectionModel
import com.example.newproject.domain.markdown.buildNoteSectionModel
import com.example.newproject.domain.normalizeForMatch
import com.example.newproject.ui.markdown.LocalQuoteMenuProbe
import com.example.newproject.ui.markdown.MarkdownNoteContent
import com.example.newproject.ui.markdown.QuoteSelection
import com.example.newproject.ui.markdown.QuoteTextToolbar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 今のノートの本文の選択メニューの「引用して書く」（→ features/margin_quote.md §5.1・§5.2・§11）。
 * 浮かぶメニューは Compose の試験の外の窓に出るので、本文の選択の容器が作ったメニューを受け取り、その項目を押す（→ [LocalQuoteMenuProbe]）。
 */
@RunWith(AndroidJUnit4::class)
class QuoteSelectionUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    /** 端末のクリップボードの代わり。渡された文字を数える。 */
    private class RecordingClipboard : ClipboardManager {
        val copied = mutableListOf<String>()
        override fun setText(annotatedString: AnnotatedString) {
            copied += annotatedString.text
        }
        override fun getText(): AnnotatedString? = copied.lastOrNull()?.let(::AnnotatedString)
    }

    private val clipboard = RecordingClipboard()
    private var toolbar: QuoteTextToolbar? = null
    private var received: QuoteSelection? = null

    private fun setBody(model: NoteSectionModel) {
        composeRule.setContent {
            CompositionLocalProvider(LocalClipboardManager provides clipboard, LocalQuoteMenuProbe provides { toolbar = it }) {
                MarkdownNoteContent(
                    content = "",
                    modifier = Modifier.fillMaxSize(),
                    precomputedBlocks = model.blocks,
                    quotable = true,
                    onQuote = { received = it }
                )
            }
        }
    }

    private fun press(item: Int) {
        composeRule.waitForIdle()
        composeRule.runOnIdle { assertTrue("メニューの項目を押せなかった", checkNotNull(toolbar).onItem(item)) }
        composeRule.waitForIdle()
    }

    /** **同じ文が2度ある本文で後ろを選ぶと、始まりは後ろのブロックに決まる。** 選んだ文字は端末のクリップボードへ渡さない（→ 判断8）。 */
    @Test
    fun 引用して書くは選んだ文字と始まりのブロックを受け取り_端末のクリップボードへ渡さない() {
        setBody(buildNoteSectionModel("## 節A\n\n同じ文です。\n\n## 節B\n\n同じ文です。"))

        composeRule.onAllNodesWithText("同じ文です。")[1].performTouchInput { longClick() }
        press(QuoteTextToolbar.ITEM_QUOTE)

        val quoted = checkNotNull(received) { "選択メニューの引用が届いていない" }
        assertTrue("選んだ文字が届いていない: ${quoted.text}", quoted.text.isNotBlank() && "同じ文です。".contains(quoted.text))
        assertEquals(3, quoted.source)
        assertTrue("端末のクリップボードへ渡した: ${clipboard.copied}", clipboard.copied.isEmpty())
    }

    @Test
    fun コピーは今どおり端末のクリップボードへ渡す() {
        setBody(buildNoteSectionModel("前の段落\n\n選んで写す段落"))

        composeRule.onNodeWithText("選んで写す段落").performTouchInput { longClick() }
        press(QuoteTextToolbar.ITEM_COPY)

        assertEquals(1, clipboard.copied.size)
        assertTrue("選択メニューの引用を呼んだ", received == null)
    }

    /**
     * **選択のコピーは、ブロックの間にも部品（リストの記号と項目・引用の行・表のセル）の間にも何も挟まない。**
     * 照らし直しの文字はこの形に合わせてある（→ `visibleText`）。形がずれると、選んだ引用が本文に見つからない。
     */
    @Test
    fun 選択のコピーはブロックと部品の間に何も挟まず_照らし直しの文字と同じ形になる() {
        val model = buildNoteSectionModel(
            "## 見出し\n\n最初の段落。\n\n- 一つ目\n- 二つ目\n\n1. 番号の項目\n\n> 引用の一行目\n> 引用の二行目\n\n| 列A | 列B |\n|---|---|\n| a1 | a2 |\n\n```\nコードの行\n```"
        )
        setBody(model)

        composeRule.onNodeWithText("最初の段落。").performTouchInput { longClick() }
        press(QuoteTextToolbar.ITEM_SELECT_ALL)
        press(QuoteTextToolbar.ITEM_QUOTE)

        val quoted = checkNotNull(received)
        assertEquals(model.passages.texts.joinToString(""), normalizeForMatch(quoted.text))
        // 一覧のいちばん上から選んでいるので、始まりは見えている。
        assertEquals(0, quoted.source)
    }
}
