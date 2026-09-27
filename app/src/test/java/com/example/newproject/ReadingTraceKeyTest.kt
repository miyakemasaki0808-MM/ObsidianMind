package com.example.newproject

import com.example.newproject.data.ReadingTraceStore
import com.example.newproject.data.readingTraceKeyOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 痕跡の置き場の索引が、**痕跡以外のものを載せない**こと（→ `docs/dev/features/reflect_crystal.md` 判断7）。
 *
 * 索引は孤児掃除・退避・読み戻しの下見がすべて通るので、ここで外れれば全経路から外れる。
 */
class ReadingTraceKeyTest {

    private val key = ReadingTraceStore.keyFor("ideas/habit.md")

    @Test
    fun `痕跡のファイルはキーになる。プロバイダの改名も同じキー`() {
        assertEquals(key, readingTraceKeyOf("$key.json", isDirectory = false))
        assertEquals(key, readingTraceKeyOf("$key (1).json", isDirectory = false))
    }

    @Test
    fun `結晶のフォルダは載せない`() {
        assertNull(readingTraceKeyOf("crystals", isDirectory = true))
    }

    @Test
    fun `フォルダは名前が長くても載せない`() {
        assertNull(readingTraceKeyOf("$key.json", isDirectory = true))
    }

    @Test
    fun `64文字に満たない名前のファイルは載せない`() {
        assertNull(readingTraceKeyOf("1790000000000.json", isDirectory = false))
        assertNull(readingTraceKeyOf("vigilith_traces_20260927.json", isDirectory = false))
    }
}
