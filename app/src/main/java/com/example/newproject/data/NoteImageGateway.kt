package com.example.newproject.data

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.provider.DocumentsContract
import com.example.newproject.domain.BoundedInputStream
import com.example.newproject.domain.ByteBudgetCache
import com.example.newproject.domain.image.ImageRequest
import com.example.newproject.domain.image.ImageResolution
import com.example.newproject.domain.image.NoteImageLimits
import com.example.newproject.domain.image.imageDecodeFailureFor
import com.example.newproject.domain.image.imageRequestOf
import com.example.newproject.domain.image.isDecodableImageFileName
import com.example.newproject.domain.image.rejectionForBounds
import com.example.newproject.domain.image.sampleSizeFor
import com.example.newproject.domain.markdown.MarkdownBlock
import com.example.newproject.model.DocumentRef
import com.example.newproject.model.NoteImageFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 画像1枚の読み込み結果。 */
internal sealed interface NoteImageResult {
    data class Loaded(val bitmap: Bitmap) : NoteImageResult
    data class Failed(val reason: NoteImageFailure) : NoteImageResult
}

/** 寸法だけの読み取り結果。 */
internal sealed interface NoteImageMeasureResult {
    data class Measured(val width: Int, val height: Int) : NoteImageMeasureResult
    data class Failed(val reason: NoteImageFailure) : NoteImageMeasureResult
}

/** 復号を打ち切る内部例外。理由を [ByteBudgetCache] 越しに運ぶためだけに使う。 */
private class ImageDecodeFailure(val reason: NoteImageFailure) : Exception()

/**
 * ノート内画像の解決と復号。
 *
 * ## 二段復号は省略できない
 *
 * `BitmapFactory` は寸法を知らずに復号すると原寸で展開する。12MPの写真なら
 * ARGB_8888 で 48MB になり、数枚で確実に落ちる。そこで
 * **寸法だけ読む → 間引き倍率を決める → 本番の復号** の2回に分ける。
 *
 * **SAFのストリームは2回開く。** `reset()` できる保証がないため使い回せない。
 *
 * `ImageDecoder`（API 28+）なら1回で済むが minSdk 26 なので使えない。
 *
 * ## 失敗はキャッシュしない
 *
 * 成功した [Bitmap] だけを [ByteBudgetCache] へ載せる。壊れた画像は
 * 表示のたびに読み直すことになるが、**寸法読みの段階で失敗するので安い**
 * （索引の再走査＝Vault全走査とは桁が違う）。逆に失敗を載せると、
 * 一時的な失敗が次のVault切替まで固定される。
 */
/** 寸法キャッシュの件数上限（1件を1と数える）。値は Int 2つなので大きさは効かない。 */
private const val MEASURE_CACHE_MAX_ENTRIES = 128L

internal class NoteImageGateway(
    private val contentResolver: ContentResolver,
    private val indexStore: VaultImageIndexStore,
    private val loadScope: CoroutineScope,
    private val ioDispatcher: kotlin.coroutines.CoroutineContext = Dispatchers.IO
) {
    private val cache = ByteBudgetCache<CacheKey, Bitmap>(
        maxBytes = NoteImageLimits.CACHE_MAX_BYTES,
        // 追い出しでは recycle() しない（描画中の Bitmap を壊すため）。参照を落とすだけ。
        sizeOf = { it.allocationByteCount.toLong() }
    )

    /**
     * [targetWidthPx] を鍵に含めるのは、幅が変われば必要な解像度も変わるため。
     * [contentVersion]（更新日時）を含めるのは、**同じファイルを上書きされたときに
     * 古い画像を返し続けないため** — 参照は変わらないので、これが無いと見分けられない。
     */
    private data class CacheKey(
        val ref: DocumentRef,
        val contentVersion: Long?,
        val targetWidthPx: Int
    )

    /**
     * 寸法の鍵。**表示幅を含まない** — 元画像の寸法は表示幅で変わらない。
     */
    private data class MeasureKey(val ref: DocumentRef, val contentVersion: Long?)

    /**
     * 寸法のキャッシュ。**世代を鍵に含めるのが要点で、Bitmap側と同じ理由**である
     * （上書きされたら別物として扱う）。表示側は「同じ参照なら測り直さない」を
     * 参照文字列だけで判断していたため、**縦横比の違う画像へ上書きすると
     * 新しいBitmapが古い比率の枠へ描かれていた**（→ note_image_rendering §6）。
     *
     * **Bitmap側と同じ入れ物を使う。** 素の `LinkedHashMap` で持つと、
     * 1ノートに画像が複数あるだけで**読みは呼び出し側スレッド・書きはIOスレッド**の
     * 同時アクセスになり、`accessOrder` の付け替えまで同期なしで走る。
     * 同じ画像が複数箇所にある場合のヘッダ読みも重複する。
     * **要るのは予算の単位ではなく single-flight と排他**なので、
     * 1件を1と数えて件数上限として使う（値は Int 2つで揃っており、重さの差が無い）。
     *
     * **失敗は載せない** — 例外で抜けるので [ByteBudgetCache] は格納しない。
     */
    private val measureCache = ByteBudgetCache<MeasureKey, NoteImageMeasureResult.Measured>(
        maxBytes = MEASURE_CACHE_MAX_ENTRIES,
        sizeOf = { 1L }
    )

    /** ヘッダを実際に読んだ回数。**測り直しが起きていないことの検証にだけ使う。** */
    internal var boundsReadCount = 0
        private set

    /**
     * 画像ブロックを解決して復号する。
     *
     * **拡張子で復号可否を先に見る。** SVG を索引で引き当ててから復号に失敗させると
     * 「壊れています」と出てしまう。正しくは「形式が非対応」なので、
     * ファイルを開く前に分ける。
     */
    internal suspend fun load(block: MarkdownBlock.Image, targetWidthPx: Int): NoteImageResult {
        val request = imageRequestOf(block)
        if (request is ImageRequest.Lookup && !isDecodableImageFileName(request.fileName)) {
            return NoteImageResult.Failed(NoteImageFailure.Unsupported)
        }
        val resolved = when (val resolution = indexStore.resolve(request)) {
            is ImageResolution.Resolved -> resolution
            is ImageResolution.Failed -> return NoteImageResult.Failed(resolution.reason)
        }
        val ref = resolved.ref
        return try {
            val key = CacheKey(ref, resolved.contentVersion, targetWidthPx)
            NoteImageResult.Loaded(cache.getOrLoad(key, loadScope) { decode(ref, targetWidthPx) })
        } catch (e: CancellationException) {
            throw e
        } catch (e: ImageDecodeFailure) {
            NoteImageResult.Failed(e.reason)
        } catch (e: Exception) {
            NoteImageResult.Failed(NoteImageFailure.Broken)
        }
    }

    /**
     * 寸法だけを読む。**ピクセルは復号しない**のでヘッダ分の読み取りで済む。
     *
     * 表示側がプレースホルダの高さを中身より先に確定させるために要る
     * （高さ0のブロックを作ると読書痕跡の到達率が壊れる → note_image_rendering §6）。
     *
     * 寸法は [load] の中でももう一度読む。**共有しないのは、[load] が
     * キャッシュから返る場合はそもそも読まないため** — 経路をまたいで
     * 寸法だけを持ち回す仕掛けのほうが、ヘッダ1回ぶんより高くつく。
     */
    internal suspend fun measure(block: MarkdownBlock.Image): NoteImageMeasureResult {
        val request = imageRequestOf(block)
        if (request is ImageRequest.Lookup && !isDecodableImageFileName(request.fileName)) {
            return NoteImageMeasureResult.Failed(NoteImageFailure.Unsupported)
        }
        val resolved = when (val resolution = indexStore.resolve(request)) {
            is ImageResolution.Resolved -> resolution
            is ImageResolution.Failed -> return NoteImageMeasureResult.Failed(resolution.reason)
        }
        val ref = resolved.ref
        val key = MeasureKey(ref, resolved.contentVersion)
        return try {
            measureCache.getOrLoad(key, loadScope) {
                withContext(ioDispatcher) {
                    if (byteSizeOf(ref)?.let { it > NoteImageLimits.MAX_INPUT_BYTES } == true) {
                        throw ImageDecodeFailure(NoteImageFailure.TooLarge)
                    }
                    val bounds = readBounds(ref)
                    // **拒否も例外で返す。** 値で返すと失敗がキャッシュへ載る。
                    rejectionForBounds(bounds.outWidth, bounds.outHeight)
                        ?.let { throw ImageDecodeFailure(it) }
                    NoteImageMeasureResult.Measured(bounds.outWidth, bounds.outHeight)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ImageDecodeFailure) {
            NoteImageMeasureResult.Failed(e.reason)
        } catch (e: Exception) {
            NoteImageMeasureResult.Failed(NoteImageFailure.Broken)
        }
    }

    private fun readBounds(ref: DocumentRef): BitmapFactory.Options {
        boundsReadCount++
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val truncated = openStream(ref) {
            BitmapFactory.decodeStream(it, null, bounds)
            it.truncated
        } == true
        // 寸法すら読み切れないほど大きいなら「壊れている」ではなく「大きすぎる」。
        // 寸法読みでは Bitmap を作らないので、判定材料は打ち切りの有無だけ。
        imageDecodeFailureFor(truncated, decoded = true)?.let { throw ImageDecodeFailure(it) }
        return bounds
    }

    private suspend fun decode(ref: DocumentRef, targetWidthPx: Int): Bitmap =
        withContext(ioDispatcher) {
            if (byteSizeOf(ref)?.let { it > NoteImageLimits.MAX_INPUT_BYTES } == true) {
                throw ImageDecodeFailure(NoteImageFailure.TooLarge)
            }
            val bounds = readBounds(ref)
            rejectionForBounds(bounds.outWidth, bounds.outHeight)?.let { throw ImageDecodeFailure(it) }

            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, targetWidthPx)
            }
            var truncated = false
            val bitmap = openStream(ref) {
                val decoded = BitmapFactory.decodeStream(it, null, options)
                truncated = it.truncated
                decoded
            }
            // 打ち切りは復号失敗として返ってくるので、理由の判定は純関数へ寄せる
            // （TooLarge と Broken の取り違えは、この順序ひとつで起きる）。
            imageDecodeFailureFor(truncated, decoded = bitmap != null)
                ?.let { throw ImageDecodeFailure(it) }
            checkNotNull(bitmap)
        }

    /**
     * 上限つきでストリームを開く。
     *
     * **メタデータのサイズ照会だけでは上限を強制できない。** `COLUMN_SIZE` を
     * 返さないプロバイダでは照会が null になり、そのまま無制限に読み進んでしまう。
     * 実際に読んだバイト数で打ち切るので、サイズを申告しない相手にも効く。
     *
     * 打ち切ったことは [BoundedInputStream.truncated] で分かる。
     * `BitmapFactory` は途中で終わったストリームを「復号失敗」としか言わないので、
     * **理由を [NoteImageFailure.TooLarge] へ直すために呼び出し側で見る**。
     */
    private fun <T> openStream(ref: DocumentRef, read: (BoundedInputStream) -> T): T? =
        try {
            contentResolver.openInputStream(ref.toUri())?.use { raw ->
                read(BoundedInputStream(raw, NoteImageLimits.MAX_INPUT_BYTES))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw ImageDecodeFailure(NoteImageFailure.Broken)
        }

    /**
     * 宣言されたファイルサイズ。取れなければ null（**その場合は弾かない**）。
     *
     * サイズ不明を拒否に倒すと、サイズ列を返さないプロバイダで全画像が出なくなる。
     * 寸法とピクセル数の上限が後段に残っているので、ここを通しても青天井にはならない。
     */
    private fun byteSizeOf(ref: DocumentRef): Long? = try {
        contentResolver.query(
            ref.toUri(),
            arrayOf(DocumentsContract.Document.COLUMN_SIZE),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
        }
    } catch (e: Exception) {
        null
    }
}
