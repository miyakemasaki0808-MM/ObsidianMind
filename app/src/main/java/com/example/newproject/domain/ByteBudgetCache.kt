package com.example.newproject.domain

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * バイト予算つきLRU＋同一キーの重複実行の抑止（single-flight）。
 *
 * ## [KeyedMemoCache] と共通化しない理由
 *
 * 形は似ているが、揃えられない差が2つある。
 *
 * | | [KeyedMemoCache] | 本クラス |
 * |---|---|---|
 * | 追い出しの単位 | **件数** | **バイト** |
 * | 同一キーの同時要求 | 両方が [load] を呼ぶ | **1回だけ呼ぶ** |
 *
 * 画像は1件の重さが2桁違うので件数では上限の意味がなく、
 * 同じ画像が同じ画面に複数回出る・通常表示と全画面で同じノートを開くといった
 * 経路があるので重複実行の抑止が要る。逆に [KeyedMemoCache] の利用側
 * （関連ノート候補の本文）は要素の大きさが揃っていて重複キーも出ないため、
 * バイト計上と `Deferred` の管理はコストにしかならない。
 * **共通化の判断は「形が似ているか」ではなく「制約が同じか」で決める。**
 *
 * ## 追い出しでは値に触らない
 *
 * 追い出しは参照を落とすだけで、値の破棄（`Bitmap.recycle()` 等）は**しない**。
 * 描画中の値を破棄すると落ちるため、解放はGCに任せる。
 * この方針があるので、本クラスは値の型に何も要求しない。
 */
internal class ByteBudgetCache<K : Any, V : Any>(
    private val maxBytes: Long,
    private val sizeOf: (V) -> Long
) {
    private val mutex = Mutex()
    private val entries = LinkedHashMap<K, V>(16, 0.75f, /* accessOrder = */ true)
    private val inFlight = mutableMapOf<K, CompletableDeferred<Result<V>>>()
    private var bytes = 0L

    /** 現在の合計バイト数。 */
    internal suspend fun byteSize(): Long = mutex.withLock { bytes }

    /** 保持している件数。 */
    internal suspend fun count(): Int = mutex.withLock { entries.size }

    /**
     * キャッシュから返すか、無ければ [load] して格納する。
     *
     * **同じキーの要求が重なったら [load] は1回しか呼ばれない**（後続は結果を待つ）。
     * [load] が失敗した場合は格納せず、待っていた全員へ同じ例外を投げる
     * （失敗を確定キャッシュにしない — 次の要求ではやり直す）。
     *
     * [scope] は先行者がキャンセルされても後続の待ちを巻き添えにしないために要る。
     * `async` をこのスコープへ載せることで、読み込みの寿命を要求元のコルーチンから切る。
     */
    internal suspend fun getOrLoad(key: K, scope: CoroutineScope, load: suspend () -> V): V {
        val created = CompletableDeferred<Result<V>>()
        val deferred = mutex.withLock {
            entries[key]?.let { return it }
            inFlight.getOrPut(key) { created }
        }
        if (deferred === created) {
            // **記帳は読み込み側で行う。** 要求元でやると、待っている間に
            // 要求元がキャンセルされた瞬間（＝画像を素早くスクロールした瞬間）に
            // `await()` が例外になり、取り下げも予算計上も飛ぶ。結果は
            // 実行中のまま残り続けるので、以後そのキーは**予算の外で永久に生き**、
            // 失敗なら**永久に同じ失敗を返す**。要求元の寿命に依存させてはいけない。
            //
            // `scope.async` はロックの外で始める（中で始めると、読み込みが
            // 一度も中断しない場合に同じ Mutex を取りにいって止まりうる）。
            scope.async {
                // キャンセルも含めて必ず完了させる。握りつぶさず、
                // 例外は下の getOrThrow が要求元へ投げ直す。
                val result = try {
                    Result.success(load())
                } catch (t: Throwable) {
                    Result.failure(t)
                }
                mutex.withLock {
                    inFlight.remove(key)
                    result.getOrNull()?.let { put(key, it) }
                }
                created.complete(result)
            }
        }
        return deferred.await().getOrThrow()
    }

    /** 予算を超えたぶんを、最も長く使われていないものから落とす。 */
    private fun put(key: K, value: V) {
        val previous = entries.put(key, value)
        bytes += sizeOf(value)
        if (previous != null) bytes -= sizeOf(previous)
        val iterator = entries.entries.iterator()
        while (bytes > maxBytes && iterator.hasNext()) {
            val eldest = iterator.next()
            // いま入れたものだけは、単体で予算を超えていても残す
            // （落とすと「入れた直後に無い」が起きて呼び出し側が無限に読み直す）。
            if (eldest.key == key) continue
            bytes -= sizeOf(eldest.value)
            iterator.remove()
        }
    }
}
