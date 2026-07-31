package com.example.newproject

import com.example.newproject.controller.NoteSectionController
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 表示用Markdownの解析が Main の外で1回だけ走り、ノート切替の後着で
 * 旧ノートのブロックが残らないことを固定する。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NoteSectionControllerTest {

    private fun TestScope.controller() =
        NoteSectionController(this, StandardTestDispatcher(testScheduler))

    @Test
    fun `解析が終わるまでモデルは出ない`() = runTest {
        val controller = controller()

        controller.parse("# 見出し\n\n本文")

        // 解析はディスパッチャ越しなので、呼んだ直後にはまだ結果が無い。
        // ここが同期実行に戻ると即座に非nullになり、このアサーションが落ちる。
        assertNull(controller.model.value)

        advanceUntilIdle()
        assertNotNull(controller.model.value)
    }

    @Test
    fun `解析結果は見出しどおりのセクションを持つ`() = runTest {
        val controller = controller()

        controller.parse(
            """
            # 第1章
            本文A

            ## 節1
            本文B
            """.trimIndent()
        )
        advanceUntilIdle()

        val model = requireNotNull(controller.model.value)
        assertEquals(listOf("第1章", "節1"), model.sections.map { it.title })
    }

    @Test
    fun `ノート切替でモデルが消える`() = runTest {
        val controller = controller()
        controller.parse("# 旧ノート\n\n本文")
        advanceUntilIdle()
        assertNotNull(controller.model.value)

        controller.cancelAndClear()

        assertNull(controller.model.value)
    }

    @Test
    fun `切替後に旧ノートの解析結果が後着しない`() = runTest {
        val controller = controller()

        // 解析を走らせたまま切り替える。cancel だけでは素の同期関数を止められないため、
        // 完走した結果が requestId ガードで捨てられることを確かめる。
        controller.parse("# 旧ノート\n\n本文")
        controller.cancelAndClear()
        advanceUntilIdle()

        assertNull(controller.model.value)
    }

    @Test
    fun `新しい解析中も直前のモデルを保持する`() = runTest {
        val controller = controller()
        controller.parse("# 蒸留前\n\n本文")
        advanceUntilIdle()

        // 蒸留の保存後に本文を差し替える経路。ここで null に戻すと本文が
        // 数百ミリ秒消えるため、新しい結果が届くまで直前のモデルを保つ。
        controller.parse("# 蒸留後\n\n**本文**")
        assertEquals(listOf("蒸留前"), controller.model.value?.sections?.map { it.title })

        advanceUntilIdle()
        assertEquals(listOf("蒸留後"), controller.model.value?.sections?.map { it.title })
    }

    @Test
    fun `連続した解析要求は最後のものだけが反映される`() = runTest {
        val controller = controller()

        controller.parse("# 1本目\n\n本文")
        controller.parse("# 2本目\n\n本文")
        controller.parse("# 3本目\n\n本文")
        advanceUntilIdle()

        assertEquals(listOf("3本目"), controller.model.value?.sections?.map { it.title })
    }
}
