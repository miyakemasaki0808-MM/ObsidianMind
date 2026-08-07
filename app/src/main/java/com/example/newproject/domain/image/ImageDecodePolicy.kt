package com.example.newproject.domain.image

import com.example.newproject.model.NoteImageFailure

// ---------------------------------------------------------------------------
// 復号の方針。BitmapFactory を呼ぶのは data 側で、ここは「どこまで読むか」
// 「どれだけ間引くか」「弾くか」だけを決める純関数。JVMテストで検証できる。
// ---------------------------------------------------------------------------

/**
 * 画像1枚にかける予算。
 *
 * **4つを別々に持つのが要点。** ファイルサイズ1つでは画像爆弾を防げない —
 * 圧縮率の高い小さなファイルが巨大な寸法を宣言でき、復号した瞬間に落ちる。
 * 逆に寸法だけ見ても、読み込みそのものが暴走する経路は塞げない。
 */
internal object NoteImageLimits {

    /** 入力バイト数。これを超えるファイルはそもそも読まない。 */
    const val MAX_INPUT_BYTES = 16L * 1024 * 1024

    /** 縦・横それぞれの上限。宣言された寸法がこれを超えたら間引く前に弾く。 */
    const val MAX_DIMENSION = 20_000

    /**
     * 復号後のピクセル数。ARGB_8888 なので 1px = 4byte、400万px ≒ 16MB。
     * 間引き後もこれを超えるなら、さらに間引いてから復号する。
     */
    const val MAX_DECODED_PIXELS = 4_000_000L

    /** LRU の総バイト数。**件数ではなくバイトで持つ**（1件の重さが2桁違う）。 */
    const val CACHE_MAX_BYTES = 32L * 1024 * 1024

    /** 間引き倍率の上限。異常な寸法で無限ループにしないための歯止め。 */
    const val MAX_SAMPLE_SIZE = 1024
}

/**
 * `BitmapFactory` が復号できる拡張子。
 *
 * [com.example.newproject.model.IMAGE_FILE_EXTENSIONS] より**狭い**。
 * 認識はするが復号できないもの（SVG）を「見つかりません」ではなく
 * 「形式が非対応」と言うために、2つの一覧を分けている。
 *
 * `heic` / `heif` / `avif` は端末とAPIレベル次第で失敗するが、
 * **静的に落とさない** — 復号できる端末では出せるので、
 * 失敗の判定は実際に試した結果に任せる。
 */
private val DECODABLE_EXTENSIONS = setOf(
    "png", "jpg", "jpeg", "gif", "webp", "bmp", "heic", "heif", "avif"
)

/** 拡張子から見て復号を試す価値があるか。偽なら [NoteImageFailure.Unsupported]。 */
internal fun isDecodableImageFileName(name: String): Boolean =
    name.substringAfterLast('.', missingDelimiterValue = "").lowercase() in DECODABLE_EXTENSIONS

/**
 * 寸法を見て弾くべきかを決める。弾かないなら null。
 *
 * 寸法が取れない（0以下）のは、`BitmapFactory` が形式を理解できなかった場合で、
 * ファイルが壊れているか拡張子が実体と食い違っている。どちらも
 * [NoteImageFailure.Broken] として扱う（利用者にとっては同じ「読めない」）。
 */
internal fun rejectionForBounds(width: Int, height: Int): NoteImageFailure? = when {
    width <= 0 || height <= 0 -> NoteImageFailure.Broken
    width > NoteImageLimits.MAX_DIMENSION || height > NoteImageLimits.MAX_DIMENSION ->
        NoteImageFailure.TooLarge
    else -> null
}

/**
 * 復号を試みた結果から失敗理由を決める。成功なら null。
 *
 * **順序がこの関数の全部である。** 上限で打ち切ると `BitmapFactory` は
 * 「復号できなかった」としか言わないので、Bitmap が null であることを先に見ると
 * **大きすぎる画像がすべて `Broken` になる。** ユーザーには原因も次の行動も違って見える
 * （`TooLarge` は縮小すれば直る、`Broken` はファイルが壊れている）ので、
 * **打ち切りを先に判定する。**
 *
 * 逆に、打ち切っていないのに Bitmap が null なら本当に壊れている。
 * ここを `TooLarge` に倒すと、壊れた画像を前にユーザーが縮小を試み続ける。
 *
 * @param truncated 入力が上限を**超えて**打ち切られたか（上限ちょうどは超過ではない）
 * @param decoded Bitmap が得られたか
 */
internal fun imageDecodeFailureFor(truncated: Boolean, decoded: Boolean): NoteImageFailure? = when {
    truncated -> NoteImageFailure.TooLarge
    !decoded -> NoteImageFailure.Broken
    else -> null
}

/**
 * `BitmapFactory.Options.inSampleSize` に渡す間引き倍率（2の冪）。
 *
 * 2段階で決める。
 * 1. 表示幅まで落とす — 画面より細かく復号しても見えないぶんメモリだけ食う
 * 2. なお [NoteImageLimits.MAX_DECODED_PIXELS] を超えるなら、さらに落とす
 *
 * **2段目が要るのは極端な縦横比があるため。** 幅1,000・高さ200,000 のような画像は
 * 表示幅基準では間引かれないのに、復号すると数百MBになる。
 */
internal fun sampleSizeFor(sourceWidth: Int, sourceHeight: Int, targetWidth: Int): Int {
    if (sourceWidth <= 0 || sourceHeight <= 0 || targetWidth <= 0) return 1
    var sample = 1
    while (sample < NoteImageLimits.MAX_SAMPLE_SIZE && sourceWidth / (sample * 2) >= targetWidth) {
        sample *= 2
    }
    while (
        sample < NoteImageLimits.MAX_SAMPLE_SIZE &&
        decodedPixels(sourceWidth, sourceHeight, sample) > NoteImageLimits.MAX_DECODED_PIXELS
    ) {
        sample *= 2
    }
    return sample
}

/** 間引き後のピクセル数。`Int` で掛けると容易に溢れるので `Long` で数える。 */
internal fun decodedPixels(sourceWidth: Int, sourceHeight: Int, sampleSize: Int): Long =
    (sourceWidth / sampleSize).toLong() * (sourceHeight / sampleSize).toLong()
