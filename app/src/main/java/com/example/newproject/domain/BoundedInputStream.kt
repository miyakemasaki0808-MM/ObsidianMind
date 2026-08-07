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
 *
 * ## 上限ちょうどは「超過」ではない
 *
 * 呼び出し側は `size > MAX_INPUT_BYTES` だけを拒否するので、**上限ちょうどは許可**である。
 * したがって [maxBytes] に達しただけでは打ち切ったと言えない。
 * **1バイトだけ先読みして、続きがあるかを確かめてから** [truncated] を立てる
 * （先読みしないと、上限ちょうどの正常な画像が `TooLarge` として弾かれる）。
 * 先読みした1バイトは捨てる — 呼び出し側へ上限を超えて渡すことは無いため。
 */
internal class BoundedInputStream(
    private val source: InputStream,
    private val maxBytes: Long
) : InputStream() {

    private var readBytes = 0L

    /** 上限到達後の先読みを済ませたか。**元ストリームを2度以上消費しない。** */
    private var overflowChecked = false

    /** 上限を**超えて**打ち切ったか。**終端との区別はここでしか付かない。** */
    internal var truncated = false
        private set

    override fun read(): Int {
        if (exhausted()) return -1
        val value = source.read()
        if (value >= 0) readBytes++
        return value
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        // `len == 0` は終端でも 0 を返すのが InputStream の契約。
        // -1 を返すと、長さ0の問い合わせだけで打ち切り扱いになる。
        if (len == 0) return 0
        if (exhausted()) return -1
        val remaining = (maxBytes - readBytes).coerceAtMost(len.toLong()).toInt()
        val count = source.read(b, off, remaining)
        if (count > 0) readBytes += count
        return count
    }

    override fun skip(n: Long): Long {
        if (n <= 0) return 0
        val remaining = (maxBytes - readBytes).coerceAtLeast(0)
        if (remaining == 0L) return 0
        val skipped = source.skip(n.coerceAtMost(remaining))
        if (skipped > 0) readBytes += skipped
        return skipped
    }

    /** 上限内の残量へ丸める。元の値をそのまま返すと、読めない量を申告してしまう。 */
    override fun available(): Int {
        val remaining = (maxBytes - readBytes).coerceAtLeast(0)
        return source.available().toLong().coerceAtMost(remaining).toInt()
    }

    /**
     * 上限に達しており、かつ**もう返せるバイトが無い**か。
     *
     * 到達しただけでは決められないので、初回だけ元ストリームを1バイト先読みして
     * 「上限ちょうどで終わり」と「まだ続きがある」を分ける。
     */
    private fun exhausted(): Boolean {
        if (readBytes < maxBytes) return false
        if (!overflowChecked) {
            overflowChecked = true
            if (source.read() >= 0) truncated = true
        }
        return true
    }
}
