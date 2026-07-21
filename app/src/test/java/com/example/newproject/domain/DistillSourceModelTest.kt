package com.example.newproject.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.system.measureTimeMillis

class DistillSourceModelTest {

    @Test
    fun `frontmatter heading code fence and table are excluded`() {
        val content = """
            ---
            title: 秘密
            ---
            # 見出し
            本文です。次の文です。
            ```kotlin
            println("候補にしない。")
            ```
            | a | b |
            |---|---|
            | 候補外。 | x |
            最後です。
        """.trimIndent()

        val model = buildDistillSourceModel(content)

        assertEquals(listOf("本文です。", "次の文です。", "最後です。"), model.sentences.map { it.text })
        assertTrue(model.sentences.all { it.heading == "見出し" })
    }

    @Test
    fun `table without outer pipes and longer fence stay excluded`() {
        val content = """
            A | B
            --- | :---:
            候補外。 | value
            ````kotlin
            コード内。
            ```
            まだコード内。
            ````
            本文です。
        """.trimIndent()

        val model = buildDistillSourceModel(content)

        assertEquals(listOf("本文です。"), model.sentences.map { it.text })
    }

    @Test
    fun `setext heading and empty cell table are excluded`() {
        val content = """
            Setext見出し
            =====
            | | 内容 |
            | --- | --- |
            | | 候補外。 |
            本文です。
        """.trimIndent()

        val model = buildDistillSourceModel(content)

        assertEquals(listOf("本文です。"), model.sentences.map { it.text })
        assertEquals("Setext見出し", model.sentences.single().heading)
    }

    @Test
    fun `BOM CRLF and emoji keep exact UTF16 offsets`() {
        val content = "\uFEFF# H\r\n😀の文です。\r\n次です。\r\n"
        val model = buildDistillSourceModel(content)

        assertEquals(listOf("😀の文です。", "次です。"), model.sentences.map { it.text })
        model.sentences.forEach { sentence ->
            assertEquals(sentence.text, content.substring(sentence.range.start, sentence.range.endExclusive))
        }
    }

    @Test
    fun `list and quote markers stay outside candidate ranges`() {
        val content = "- リストです。\n2. 二番目です。\n> 引用です。"
        val model = buildDistillSourceModel(content)

        assertEquals(listOf("リストです。", "二番目です。", "引用です。"), model.sentences.map { it.text })
        assertTrue(model.sentences.all { content.substring(it.range.start, it.range.endExclusive) == it.text })
    }

    @Test
    fun `inline code and links stay inside sentence and punctuation inside them does not split`() {
        val content = "Use `a.b` now. See [site](https://example.com/a.b). [[Note.Name]]を読む。"
        val model = buildDistillSourceModel(content)

        assertEquals(
            listOf("Use `a.b` now.", "See [site](https://example.com/a.b).", "[[Note.Name]]を読む。"),
            model.sentences.map { it.text }
        )
    }

    @Test
    fun `existing strong sentence is excluded and counted`() {
        val content = "通常文です。**既に太字です。** 次です。"
        val model = buildDistillSourceModel(content)

        assertEquals(listOf("通常文です。", "次です。"), model.sentences.map { it.text })
        assertTrue(model.existingBoldCharacterCount > 0)
    }

    @Test
    fun `existing strong spanning soft line break is excluded and counted once`() {
        val content = "通常文です。**既に太字の前半\n太字の後半です。** 次です。"
        val model = buildDistillSourceModel(content)

        assertEquals(listOf("通常文です。", "次です。"), model.sentences.map { it.text })
        assertEquals("既に太字の前半太字の後半です。".length, model.existingBoldCharacterCount)
    }

    @Test
    fun `headingless content still creates chunks and long regions split`() {
        val content = "一つ目です。二つ目です。三つ目です。"
        val model = buildDistillSourceModel(content, chunkCharacterLimit = 8)

        assertEquals(3, model.sentences.size)
        assertTrue(model.chunks.size >= 2)
        assertTrue(model.sentences.last().isNoteLast)
    }

    @Test
    fun `decimal and abbreviation do not create false sentence boundaries`() {
        val content = "Version 1.5 is used. e.g. this one. Done."
        val model = buildDistillSourceModel(content)

        assertEquals(listOf("Version 1.5 is used.", "e.g. this one.", "Done."), model.sentences.map { it.text })
    }

    @Test
    fun `unclosed frontmatter is conservatively excluded`() {
        val content = "---\ntags: x\n本文です。"
        val model = buildDistillSourceModel(content)

        assertTrue(model.sentences.isEmpty())
    }

    @Test
    fun `maximum sized source completes bounded first stage without copying root content`() {
        val sentence = "x".repeat(148) + ".\n"
        val content = sentence.repeat(DistillLimits.MAX_FILE_BYTES / sentence.length)
            .padEnd(DistillLimits.MAX_FILE_BYTES, 'x')
        lateinit var model: DistillSourceModel
        val heapBefore = usedHeapBytes()

        val elapsedMillis = measureTimeMillis {
            model = buildDistillSourceModel(content)
            assertTrue(selectDistillCandidates(model, "title").size <= DistillLimits.MAX_AI_CANDIDATES)
        }
        val heapGrowth = (usedHeapBytes() - heapBefore).coerceAtLeast(0L)

        assertEquals(DistillLimits.MAX_FILE_BYTES, content.toByteArray(Charsets.UTF_8).size)
        assertSame(content, model.content)
        assertTrue("processing took ${elapsedMillis}ms", elapsedMillis < 10_000)
        assertTrue("heap grew by $heapGrowth bytes", heapGrowth < 64L * 1024L * 1024L)
    }

    private fun usedHeapBytes(): Long {
        val runtime = Runtime.getRuntime()
        return runtime.totalMemory() - runtime.freeMemory()
    }
}
