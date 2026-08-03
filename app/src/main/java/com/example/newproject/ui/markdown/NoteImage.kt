package com.example.newproject.ui.markdown

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.newproject.domain.markdown.MarkdownBlock
import com.example.newproject.domain.markdown.sourceText
import com.example.newproject.model.NoteImageFailure
import com.example.newproject.ui.theme.CheckboxOutline
import com.example.newproject.ui.theme.CodePanel
import com.example.newproject.ui.theme.OnSurfaceSubtle

/**
 * ノート内の画像1枚。
 *
 * **高さは常に確保する。** 読み込み待ちでも、失敗しても、高さ0のブロックを作らない
 * （→ [NOTE_IMAGE_MIN_HEIGHT_DP] のKDoc）。寸法が分かった時点で縦横比どおりの
 * 高さへ移り、復号が終わってもレイアウトは動かない。
 *
 * [loader] が null なら読み込み口が無いので、原文をそのまま段落として出す
 * （補記結果の画面など、画像を持たない文脈で使い回せるようにするため）。
 */
@Composable
internal fun MarkdownImage(block: MarkdownBlock.Image, loader: NoteImageLoader?) {
    if (loader == null) {
        MarkdownParagraph(block.sourceText())
        return
    }

    var measurement by remember(block) { mutableStateOf<NoteImageMeasurement?>(null) }
    var content by remember(block) { mutableStateOf<NoteImageContent?>(null) }

    LaunchedEffect(block) { measurement = loader.measure(block) }

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val widthDp = maxWidth.value.toInt()
        val measured = measurement as? NoteImageMeasurement.Measured
        val heightDp = reservedImageHeightDp(
            widthDp = widthDp,
            sourceWidth = measured?.width ?: 0,
            sourceHeight = measured?.height ?: 0
        )
        val widthPx = with(LocalDensity.current) { maxWidth.roundToPx() }

        // 寸法が取れてから中身を読む。先に読むと確保する高さが決まらない。
        LaunchedEffect(block, measured, widthPx) {
            if (measured != null && widthPx > 0) content = loader.load(block, widthPx)
        }

        val failure = (content as? NoteImageContent.Failed)?.reason
            ?: (measurement as? NoteImageMeasurement.Failed)?.reason
        val bitmap = (content as? NoteImageContent.Loaded)?.bitmap

        when {
            failure != null -> NoteImageFailurePanel(block, failure, heightDp)
            bitmap != null -> NoteImageBitmap(block, bitmap)
            else -> NoteImagePlaceholder(heightDp)
        }
    }
}

@Composable
private fun NoteImageBitmap(block: MarkdownBlock.Image, bitmap: ImageBitmap) {
    Image(
        bitmap = bitmap,
        // alt が空ならファイル名を読む。ビューアなので画像は装飾ではなく内容。
        contentDescription = noteImageContentDescription(block.alt, block.target),
        contentScale = ContentScale.FillWidth,
        modifier = Modifier.fillMaxWidth()
    )
}

/** 読み込み中。**枠だけ置いて高さを確保する**（文字は出さない — 一瞬で消えるため）。 */
@Composable
private fun NoteImagePlaceholder(heightDp: Int) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(heightDp.dp)
            .background(CodePanel, RoundedCornerShape(6.dp))
    )
}

/**
 * 失敗の表示。
 *
 * **色だけで伝えない（WCAG 1.4.1）。** 枠・記号・文字の3つで示し、
 * 色は手がかりに留める。理由の文字が唯一の識別手段になるよう、
 * どの理由でも必ず1文を出す。
 */
@Composable
private fun NoteImageFailurePanel(
    block: MarkdownBlock.Image,
    reason: NoteImageFailure,
    heightDp: Int
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(heightDp.dp)
            .background(CodePanel, RoundedCornerShape(6.dp))
            .border(1.dp, CheckboxOutline, RoundedCornerShape(6.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 記号は色に頼らない識別手段。読み上げでは理由の文が読まれるので装飾扱いにする。
            Text(text = "⚠", color = OnSurfaceSubtle, fontSize = 16.sp)
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = noteImageFailureText(reason, block.target),
                color = OnSurfaceSubtle,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                modifier = Modifier.semantics {
                    contentDescription = noteImageFailureText(reason, block.target)
                }
            )
        }
    }
}
