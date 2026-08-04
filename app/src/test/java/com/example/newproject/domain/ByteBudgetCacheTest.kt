package com.example.newproject.domain

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertNotNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ByteBudgetCacheTest {

    /** 大きさを外から決められる値。[Bitmap] の代わりにJVMで扱う。 */
    private data class Sized(val name: String, val bytes: Long)

    private fun cache(maxBytes: Long) =
        ByteBudgetCache<String, Sized>(maxBytes) { it.bytes }

    @Test
    fun `2回目はキャッシュから返し読み込みを呼ばない`() = runTest {
        val cache = cache(100)
        var loads = 0
        val load: suspend () -> Sized = { loads++; Sized("a", 10) }

        cache.getOrLoad("a", this, load)
        cache.getOrLoad("a", this, load)

        assertEquals(1, loads)
    }

    @Test
    fun `同じキーの同時要求で読み込みは1回だけ走る`() = runTest {
        // 同じ画像が同じ画面に複数回出る／通常表示と全画面で同じノートを開く経路。
        val cache = cache(100)
        val gate = CompletableDeferred<Unit>()
        var loads = 0
        val load: suspend () -> Sized = { loads++; gate.await(); Sized("a", 10) }

        val first = async { cache.getOrLoad("a", this, load) }
        val second = async { cache.getOrLoad("a", this, load) }
        gate.complete(Unit)

        assertEquals(Sized("a", 10), first.await())
        assertEquals(Sized("a", 10), second.await())
        assertEquals(1, loads)
    }

    @Test
    fun `予算を超えたら最も長く使われていないものから落とす`() = runTest {
        val cache = cache(25)
        cache.getOrLoad("a", this) { Sized("a", 10) }
        cache.getOrLoad("b", this) { Sized("b", 10) }
        cache.getOrLoad("c", this) { Sized("c", 10) }

        assertEquals(2, cache.count())
        assertEquals(20, cache.byteSize())
    }

    @Test
    fun `参照した順で残るものが変わる`() = runTest {
        val cache = cache(25)
        cache.getOrLoad("a", this) { Sized("a", 10) }
        cache.getOrLoad("b", this) { Sized("b", 10) }
        // a を触り直すと、次に落ちるのは b になる。
        cache.getOrLoad("a", this) { error("キャッシュから返るはず") }

        var bLoads = 0
        cache.getOrLoad("c", this) { Sized("c", 10) }
        cache.getOrLoad("a", this) { error("a は残っているはず") }
        cache.getOrLoad("b", this) { bLoads++; Sized("b", 10) }

        assertEquals(1, bLoads)
    }

    @Test
    fun `件数ではなくバイトで数える`() = runTest {
        // 件数上限なら3件とも残るが、バイトなら大きい1件で押し出される。
        val cache = cache(100)
        cache.getOrLoad("small1", this) { Sized("small1", 10) }
        cache.getOrLoad("small2", this) { Sized("small2", 10) }
        cache.getOrLoad("big", this) { Sized("big", 95) }

        assertEquals(95, cache.byteSize())
        assertEquals(1, cache.count())
    }

    @Test
    fun `単体で予算を超える値も入れた直後は残る`() = runTest {
        // 落とすと「入れた直後に無い」が起きて呼び出し側が無限に読み直す。
        val cache = cache(10)
        val value = cache.getOrLoad("huge", this) { Sized("huge", 999) }

        assertEquals(Sized("huge", 999), value)
        assertEquals(1, cache.count())
    }

    @Test
    fun `失敗は格納せず次の要求でやり直す`() = runTest {
        val cache = cache(100)
        var attempts = 0

        var caught: Throwable? = null
        try {
            cache.getOrLoad("a", this) { attempts++; throw IllegalStateException("失敗") }
        } catch (e: IllegalStateException) {
            caught = e
        }
        val value = cache.getOrLoad("a", this) { attempts++; Sized("a", 10) }

        assertNotNull(caught)
        assertEquals(Sized("a", 10), value)
        assertEquals(2, attempts)
    }

    @Test
    fun `要求元がキャンセルされても予算に計上される`() = runTest {
        // 画像を素早くスクロールすると、復号の完了前に要求元だけが消える。
        // 記帳を呼び出し側で行うと、この経路で予算計上も追い出しも永久に飛ぶ。
        val cache = cache(100)
        val gate = CompletableDeferred<Unit>()
        val waiter = async { cache.getOrLoad("a", this@runTest) { gate.await(); Sized("a", 10) } }
        advanceUntilIdle() // 要求が登録され、読み込みが走り出すところまで進める
        waiter.cancel()
        gate.complete(Unit)
        advanceUntilIdle()
        // 復号は別スコープで完走するので、その結果が予算に載っていること。
        cache.getOrLoad("a", this) { error("キャッシュから返るはず") }

        assertEquals(10, cache.byteSize())
        assertEquals(1, cache.count())
    }

    @Test
    fun `要求元がキャンセルされた失敗は残り続けない`() = runTest {
        // inFlight に完了済みの失敗が残ると、以後ずっとその失敗が返る。
        val cache = cache(100)
        val gate = CompletableDeferred<Unit>()
        var attempts = 0
        val waiter = async {
            cache.getOrLoad("a", this@runTest) {
                attempts++
                gate.await()
                throw IllegalStateException("失敗")
            }
        }
        advanceUntilIdle()
        waiter.cancel()
        gate.complete(Unit)
        advanceUntilIdle()
        val value = cache.getOrLoad("a", this) { attempts++; Sized("a", 10) }

        assertEquals(Sized("a", 10), value)
        assertEquals(2, attempts)
    }

    @Test
    fun `同じ値を返すインスタンスは共有される`() = runTest {
        val cache = cache(100)
        val first = cache.getOrLoad("a", this) { Sized("a", 10) }
        val second = cache.getOrLoad("a", this) { error("キャッシュから返るはず") }

        assertSame(first, second)
    }
}
