package com.example.newproject

import androidx.compose.ui.graphics.asImageBitmap
import com.example.newproject.data.NoteImageGateway
import com.example.newproject.data.NoteImageMeasureResult
import com.example.newproject.data.NoteImageResult
import com.example.newproject.domain.markdown.MarkdownBlock
import com.example.newproject.ui.markdown.NoteImageContent
import com.example.newproject.ui.markdown.NoteImageLoader
import com.example.newproject.ui.markdown.NoteImageMeasurement

/**
 * `ui` が宣言した読み込み口へ `data` の実装を差し込むアダプタ。
 *
 * **ルートパッケージに置くのは、ここだけが `ui` と `data` の両方を import できるため。**
 * `data` に置くと `data → ui`、`ui` に置くと `ui → data` になり、
 * どちらも依存の向きの規約に反する（→ `PackageDependencyTest`）。
 *
 * やっているのは `Bitmap` を Compose の `ImageBitmap` へ包み直すことだけで、
 * 判断は何も持たない。**判断を足したくなったら、それは上下どちらかの層の仕事**である。
 */
internal class AppNoteImageLoader(
    private val gateway: NoteImageGateway
) : NoteImageLoader {

    override suspend fun measure(image: MarkdownBlock.Image): NoteImageMeasurement =
        when (val result = gateway.measure(image)) {
            is NoteImageMeasureResult.Measured ->
                NoteImageMeasurement.Measured(result.width, result.height)
            is NoteImageMeasureResult.Failed ->
                NoteImageMeasurement.Failed(result.reason)
        }

    override suspend fun load(image: MarkdownBlock.Image, targetWidthPx: Int): NoteImageContent =
        when (val result = gateway.load(image, targetWidthPx)) {
            is NoteImageResult.Loaded -> NoteImageContent.Loaded(result.bitmap.asImageBitmap())
            is NoteImageResult.Failed -> NoteImageContent.Failed(result.reason)
        }
}
