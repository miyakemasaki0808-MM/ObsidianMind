package com.example.newproject.domain

import java.io.InputStream

/**
 * 読み取りバイト数に上限を持つストリーム。上限に達したら**終端として振る舞う**。
 *
 * ## なぜ例外にしないか
 *
 * `BitmapFactory.decodeStream` は途中で例外が出ると復号を諦めるだけで、
 * 何が起きたかを呼び出し側へ伝えない。終端として扱えば「読めなかった」結果が
 * 素直に返り、[truncated] を見て**理由を「大きすぎる」へ直せる**。
 *
 * ## なぜファイルサイズの照会で代用しないか
 *
 * SAF のプロバイダは `COLUMN_SIZE` を返さないことがある。返さない相手では
 * 照会が null になり、上限がそのまま素通りする。**実際に読んだ量で数えるほうが、
 * 相手の実装に依存しない。**
 */
internal class BoundedInputStream(
    private val source: InputStream,
    private val maxBytes: Long
) : InputStream() {

    private var readBytes = 0L

    /** 上限に達して打ち切ったか。**終端との区別はここでしか付かない。** */
    internal var truncated = false
        private set

    override fun read(): Int {
        if (exhausted()) return -1
        val value = source.read()
        if (value >= 0) readBytes++
        return value
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (exhausted()) return -1
        val remaining = (maxBytes - readBytes).coerceAtMost(len.toLong()).toInt()
        val count = source.read(b, off, remaining)
        if (count > 0) readBytes += count
        return count
    }

    override fun skip(n: Long): Long {
        val remaining = (maxBytes - readBytes).coerceAtLeast(0)
        val skipped = source.skip(n.coerceAtMost(remaining))
        readBytes += skipped
        return skipped
    }

    override fun available(): Int = source.available()

    private fun exhausted(): Boolean {
        if (readBytes < maxBytes) return false
        truncated = true
        return true
    }
}
