package com.example.newproject.ui.markdown

import androidx.compose.ui.graphics.ImageBitmap
import com.example.newproject.domain.markdown.MarkdownBlock
import com.example.newproject.model.NoteImageFailure

/**
 * 画像の寸法と中身を読む口。**実装は `data`、宣言は `ui`。**
 *
 * `ui` はパッケージ依存の規約上 `data` を import できないので、
 * 必要な形をここで宣言し、両方を import できるルートパッケージが実装を注入する。
 *
 * **[measure] と [load] が分かれているのは、プレースホルダの高さを
 * 中身より先に確定させるため**（→ note_image_rendering §6）。
 * 高さ0のブロックを1フレームでも作ると読書痕跡の到達率が水増しされる。
 */
internal interface NoteImageLoader {

    /**
     * 寸法だけを先に読む。**ピクセルは復号しない**（ヘッダだけなので安い）。
     *
     * 失敗の理由まで返すのは、寸法が取れない時点で
     * 「見つからない」「外部URL」「形式が非対応」が確定するため。
     * その場合は [load] を呼ばずに理由を出せる。
     */
    suspend fun measure(image: MarkdownBlock.Image): NoteImageMeasurement

    /** 表示幅に合わせて復号する。[measure] が成功していることを前提にしない。 */
    suspend fun load(image: MarkdownBlock.Image, targetWidthPx: Int): NoteImageContent
}

/** 寸法の読み取り結果。 */
internal sealed interface NoteImageMeasurement {
    data class Measured(val width: Int, val height: Int) : NoteImageMeasurement
    data class Failed(val reason: NoteImageFailure) : NoteImageMeasurement
}

/** 中身の読み取り結果。 */
internal sealed interface NoteImageContent {
    data class Loaded(val bitmap: ImageBitmap) : NoteImageContent
    data class Failed(val reason: NoteImageFailure) : NoteImageContent
}
