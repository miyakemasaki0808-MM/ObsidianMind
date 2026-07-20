package com.example.newproject.domain

/**
 * キー付きメモ化キャッシュ（LRU上限つき）。
 *
 * - [getOrLoad] はキャッシュミス時のみ [load] を呼び、**成功したときだけ**結果を格納する。
 *   [load] が例外を投げた場合（キャンセル含む）は何も格納せず例外を伝播する
 *   ＝途中結果を確定キャッシュにしない。
 * - [maxEntries] を超えると最も長く使われていないエントリから押し出す（access-order LRU）。
 *
 * android.net.Uri など実キーに依存しない総称実装にしてJVMユニットテストで検証する。
 */
internal class KeyedMemoCache<K, V>(private val maxEntries: Int) {
    private val lock = Any()
    private val map = object : LinkedHashMap<K, V>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>): Boolean = size > maxEntries
    }

    suspend fun getOrLoad(key: K, load: suspend (K) -> V): V {
        synchronized(lock) { map[key] }?.let { return it }
        val value = load(key) // 例外（キャンセル含む）は伝播し、下の格納には到達しない
        synchronized(lock) { map[key] = value }
        return value
    }

    fun size(): Int = synchronized(lock) { map.size }

    fun clear() = synchronized(lock) { map.clear() }
}
