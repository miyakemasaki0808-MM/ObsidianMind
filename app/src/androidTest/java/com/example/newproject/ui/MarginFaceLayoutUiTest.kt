package com.example.newproject.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.newproject.domain.ArrangedMemos
import com.example.newproject.domain.MemoPlacement
import com.example.newproject.domain.markdown.buildNoteSectionModel
import com.example.newproject.model.MarginMemo
import com.example.newproject.model.MemoPassage
import com.example.newproject.model.state.MarginMemoState
import com.example.newproject.ui.screen.BodyPosition
import com.example.newproject.ui.screen.MarginFaceInputs
import com.example.newproject.ui.screen.rememberMarginFaceInputs
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 余白の面の並べ方は Main の外で遅れて届く（→ features/margin_quote.md §5.5）。
 * **作っている間か届いた後にメモか解析結果が替わっても、前の並べ方を混ぜない。** 節を移るだけなら作り直さず、届いた並べ方を今の節で引く。
 * 並べ方を作る処理を止めておけるディスパッチャで、作っている途中に替える順序を作る。
 */
@RunWith(AndroidJUnit4::class)
class MarginFaceLayoutUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    /** 渡された処理を溜め、[release] で流すまで実行しない。 */
    private class HeldDispatcher : CoroutineDispatcher() {
        private val held = ConcurrentLinkedQueue<Runnable>()

        val pending: Int get() = held.size

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            held.add(block)
        }

        fun release() {
            while (true) held.poll()?.run() ?: return
        }
    }

    private val quoted = MarginMemo("引用のメモ", 2L, "節A", MemoPassage.Quoted("同じ一節", context = null, truncated = false))
    private val plain = MarginMemo("別のメモ", 3L, "節C")
    private val content = "## 節A\n\n同じ一節\n\n## 節B\n\n同じ一節\n\n## 節C\n\n本文"

    private val held = HeldDispatcher()
    private var model by mutableStateOf(buildNoteSectionModel(content))
    private var memoState by mutableStateOf<MarginMemoState>(MarginMemoState.Ready(memos = listOf(quoted)))
    private lateinit var inputs: MarginFaceInputs
    private lateinit var listState: LazyListState
    private lateinit var scope: CoroutineScope

    private fun setFace() {
        composeRule.setContent {
            listState = rememberLazyListState()
            scope = rememberCoroutineScope()
            val position = remember { BodyPosition(listState) }
            inputs = rememberMarginFaceInputs(model, position, memoState, imageMeasurements = null, locateDispatcher = held)
            // 節を移れるように、本文のブロックと同じ数の項目を並べる。
            LazyColumn(state = listState) {
                items(model.blocks.size) { Box(Modifier.fillMaxWidth().height(2_000.dp)) }
            }
        }
    }

    private fun arranged(): ArrangedMemos = composeRule.runOnIdle { checkNotNull(inputs.arranged) }

    private fun releaseHeld() {
        composeRule.runOnIdle { held.release() }
        composeRule.waitForIdle()
    }

    private fun ArrangedMemos.otherMemos(): List<MarginMemo> = others.flatMap { group -> group.memos.map { it.memo } }.distinct()

    @Test
    fun 並べ方が届いた後にメモが替わったら_新しい並べ方が届くまで前の並べ方を使わない() {
        setFace()
        // 届く前は見出しの照合だけで並ぶ（引用の照らし直しはまだ無い）。
        assertNull(arranged().current.single().placement)
        releaseHeld()
        assertTrue(arranged().current.single().placement is MemoPlacement.Uncertain)

        composeRule.runOnIdle { memoState = MarginMemoState.Ready(memos = listOf(plain)) }
        composeRule.waitForIdle()
        // 引用のメモの並べ方はまだ残っているが、今のメモのものではないので使わない。
        assertEquals(1, composeRule.runOnIdle { held.pending })
        arranged().let { now ->
            assertTrue("前のメモが今の節に残った", now.current.isEmpty())
            assertEquals(listOf(plain), now.otherMemos())
        }

        releaseHeld()
        arranged().let { now ->
            assertTrue(now.current.isEmpty())
            assertEquals(listOf(plain), now.otherMemos())
        }
    }

    @Test
    fun 並べ方が届いた後に本文を解析し直したら_前の解析結果の並べ方を使わない() {
        setFace()
        releaseHeld()
        assertEquals(2, (arranged().current.single().placement as MemoPlacement.Uncertain).candidateCount)

        // 節Bの一節が書き換わり、似た一節は1か所に減る（前後を控えていない引用なので、それでも確定はしない）。
        composeRule.runOnIdle { model = buildNoteSectionModel(content.replaceFirst("## 節B\n\n同じ一節", "## 節B\n\n別の段落")) }
        composeRule.waitForIdle()
        assertNull("前の解析結果の照らし直しを使った", arranged().current.single().placement)

        releaseHeld()
        assertEquals(1, (arranged().current.single().placement as MemoPlacement.Uncertain).candidateCount)
    }

    @Test
    fun 並べ方を作っている間に節を移っても_届いた並べ方を今の節で引き_節を移るだけでは作り直さない() {
        setFace()
        composeRule.runOnIdle { scope.launch { listState.scrollToItem(2) } }
        composeRule.waitForIdle()
        // 節B。届く前は見出しの照合だけなので、節Aのメモはほかの節に並ぶ。
        arranged().let { now ->
            assertTrue(now.current.isEmpty())
            assertEquals(listOf(quoted), now.otherMemos())
        }

        releaseHeld()
        // 特定できない引用は候補の節（AとB）のどちらにも並ぶので、今の節Bにも出る。
        assertTrue(arranged().current.single().placement is MemoPlacement.Uncertain)

        composeRule.runOnIdle { scope.launch { listState.scrollToItem(4) } }
        composeRule.waitForIdle()
        arranged().let { now ->
            assertTrue(now.current.isEmpty())
            assertEquals(2, now.others.size)
            assertEquals(1, now.otherCount)
        }
        assertEquals("節を移っただけで並べ方を作り直した", 0, composeRule.runOnIdle { held.pending })
    }
}
