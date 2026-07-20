package com.example.newproject.domain

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

// Phase 2b: 候補コンテキストキャッシュのメモ化ロジック（Uri非依存の総称キャッシュ）。
// 実キー（Uri）を伴う配線と生成時間・タイムアウトはPixel実機で担保する。
@OptIn(ExperimentalCoroutinesApi::class)
class KeyedMemoCacheTest {

    @Test
    fun `同一キーはロードが一度だけ`() = runTest {
        val cache = KeyedMemoCache<String, String>(maxEntries = 10)
        var loads = 0
        val load: suspend (String) -> String = { k -> loads++; "v-$k" }

        assertEquals("v-a", cache.getOrLoad("a", load))
        assertEquals("v-a", cache.getOrLoad("a", load))
        assertEquals(1, loads)
    }

    @Test
    fun `別キーはそれぞれロードする`() = runTest {
        val cache = KeyedMemoCache<String, String>(maxEntries = 10)
        var loads = 0
        val load: suspend (String) -> String = { k -> loads++; k }

        cache.getOrLoad("a", load)
        cache.getOrLoad("b", load)
        assertEquals(2, loads)
        assertEquals(2, cache.size())
    }

    @Test
    fun `ロード失敗は格納されず再試行される`() = runTest {
        val cache = KeyedMemoCache<String, String>(maxEntries = 10)
        var attempts = 0

        try {
            cache.getOrLoad("a") { attempts++; throw RuntimeException("boom") }
        } catch (_: RuntimeException) {
        }
        val value = cache.getOrLoad("a") { attempts++; "ok" }

        assertEquals("ok", value)
        assertEquals(2, attempts)     // 1回目の失敗はキャッシュされないので2回ロードされる
        assertEquals(1, cache.size()) // 成功時のみ格納
    }

    @Test
    fun `clearで空になる`() = runTest {
        val cache = KeyedMemoCache<String, String>(maxEntries = 10)
        cache.getOrLoad("a") { it }
        cache.clear()
        assertEquals(0, cache.size())
    }

    @Test
    fun `上限超過で最も古いエントリが押し出される`() = runTest {
        val cache = KeyedMemoCache<String, String>(maxEntries = 2)
        val loads = mutableListOf<String>()
        val load: suspend (String) -> String = { k -> loads.add(k); k }

        cache.getOrLoad("a", load)
        cache.getOrLoad("b", load)
        cache.getOrLoad("c", load) // ここで "a" が押し出される
        cache.getOrLoad("a", load) // 再ロードされるはず

        assertEquals(listOf("a", "b", "c", "a"), loads)
        assertEquals(2, cache.size())
    }
}
