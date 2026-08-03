package com.example.newproject.data

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.provider.DocumentsContract
import com.example.newproject.domain.ByteBudgetCache
import com.example.newproject.domain.image.ImageRequest
import com.example.newproject.domain.image.ImageResolution
import com.example.newproject.domain.image.NoteImageLimits
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

    /** [targetWidthPx] を鍵に含めるのは、幅が変われば必要な解像度も変わるため。 */
    private data class CacheKey(val ref: DocumentRef, val targetWidthPx: Int)

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
        val ref = when (val resolution = indexStore.resolve(request)) {
            is ImageResolution.Resolved -> resolution.ref
            is ImageResolution.Failed -> return NoteImageResult.Failed(resolution.reason)
        }
        return try {
            val key = CacheKey(ref, targetWidthPx)
            NoteImageResult.Loaded(cache.getOrLoad(key, loadScope) { decode(ref, targetWidthPx) })
        } catch (e: CancellationException) {
            throw e
        } catch (e: ImageDecodeFailure) {
            NoteImageResult.Failed(e.reason)
        } catch (e: Exception) {
            NoteImageResult.Failed(NoteImageFailure.Broken)
        }
    }

    private suspend fun decode(ref: DocumentRef, targetWidthPx: Int): Bitmap =
        withContext(ioDispatcher) {
            if (byteSizeOf(ref)?.let { it > NoteImageLimits.MAX_INPUT_BYTES } == true) {
                throw ImageDecodeFailure(NoteImageFailure.TooLarge)
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            openStream(ref) { BitmapFactory.decodeStream(it, null, bounds) }
            rejectionForBounds(bounds.outWidth, bounds.outHeight)?.let { throw ImageDecodeFailure(it) }

            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, targetWidthPx)
            }
            openStream(ref) { BitmapFactory.decodeStream(it, null, options) }
                ?: throw ImageDecodeFailure(NoteImageFailure.Broken)
        }

    private fun <T> openStream(ref: DocumentRef, read: (java.io.InputStream) -> T): T? =
        try {
            contentResolver.openInputStream(ref.toUri())?.use(read)
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
