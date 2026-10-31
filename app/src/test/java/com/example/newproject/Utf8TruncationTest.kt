package com.example.newproject

import com.example.newproject.model.truncateToUtf8Bytes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 保存の前に UTF-8 のバイト上限で丸める（`truncateToUtf8Bytes`）。**文字（コードポイント）の途中では切らない。**
 *
 * 絵文字のような2つの char でできた文字を割ると、片割れが保存の UTF-8 化で `?` に替わり、読み直した値がメモリ上の値と
 * 食い違う。訪問は読み直した末尾と比べて差し替えるので、食い違うと同じ読書の訪問が増える（→ features/desk_memory.md §5）。
 */
class Utf8TruncationTest {

    @Test
    fun `上限が絵文字の途中に来たら、その絵文字の手前で切る`() {
        assertEquals("あ".repeat(170), truncateToUtf8Bytes("あ".repeat(170) + "😀", 512))
        assertEquals("a".repeat(511), truncateToUtf8Bytes("a".repeat(511) + "😀", 512))
    }

    @Test
    fun `上限ちょうどの絵文字入りの値と、上限より短い値は変えない`() {
        val atLimit = "a".repeat(508) + "😀"

        assertEquals(atLimit, truncateToUtf8Bytes(atLimit, 512))
        assertEquals("短い😀", truncateToUtf8Bytes("短い😀", 512))
    }

    /** 丸めた値は、UTF-8 へ書いて読み直しても同じで、上限に収まる。 */
    @Test
    fun `丸めた値はUTF8で往復しても同じで上限に収まる`() {
        val inputs = listOf(
            "あ".repeat(170) + "😀",
            "a".repeat(511) + "😀",
            "😀".repeat(200),
            "a" + "😀".repeat(200),
            "あ".repeat(400)
        )

        inputs.forEach { input ->
            val cut = truncateToUtf8Bytes(input, 512)
            val bytes = cut.toByteArray(Charsets.UTF_8)
            assertEquals(input, cut, String(bytes, Charsets.UTF_8))
            assertTrue(input, bytes.size <= 512)
            assertTrue("孤立したサロゲートが残った: $input", cut.codePoints().noneMatch { it in Char.MIN_SURROGATE.code..Char.MAX_SURROGATE.code })
        }
    }
}
