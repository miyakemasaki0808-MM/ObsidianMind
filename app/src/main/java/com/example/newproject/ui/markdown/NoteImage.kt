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
import androidx.compose.ui.platform.LocalConfiguration
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
internal fun MarkdownImage(
    block: MarkdownBlock.Image,
    loader: NoteImageLoader?,
    measurements: NoteImageMeasurements?
) {
    if (loader == null) {
        MarkdownParagraph(block.sourceText())
        return
    }

    // **測定結果は共有の入れ物から先に引く。** 全画面は新しいコンポジションなので、
    // ここで持ち回らないと入った瞬間に未計測へ戻り、位置だけ引き継いだ結果
    // 後続ブロックが可視になって到達率が水増しされる（→ NoteImageMeasurements）。
    var measurement by remember(block) { mutableStateOf(measurements?.measurementOf(block)) }
    var content by remember(block) { mutableStateOf<NoteImageContent?>(null) }
    // **このコンポジションで確かめ終えたか。** 共有された寸法は高さの初期値としては
    // 正しく使えるが、**世代が変わっていないことの根拠にはならない**。
    var verified by remember(block) { mutableStateOf(false) }

    // **測ってあっても確かめ直す。** 共有の入れ物は参照文字列だけを鍵にするので、
    // 同じ参照へ縦横比の違う画像を上書きされると、旧寸法のまま新しいBitmapを
    // 受け取り、**古い比率の枠へ収めて描いてしまう**（→ note_image_rendering §6）。
    // 世代が変わっていなければ [NoteImageLoader.measure] はヘッダを読み直さないので、
    // 全画面へ入り直したときの測り直しは従来どおり起きない。
    // **失敗していても確かめ直す。** 「高さが確定した」ことと「もう試さない」ことは別で、
    // 畳むと**画像を足した・壊れた画像を直した・プロバイダが復旧した**のいずれでも
    // 失敗表示がノート切替まで残る。再試行の頻度は索引のTTLとGatewayの
    // 「失敗はキャッシュしない」方針が決める（→ note_image_rendering §8）。
    LaunchedEffect(block) {
        val measured = loader.measure(block)
        measurements?.record(block, measured)
        measurement = measured
        verified = true
    }

    // 寸法が分かるまでは画面の高さを確保する。**誤るなら大きい側へ誤る** —
    // 小さすぎる確保は後続ブロックを一瞬だけ画面へ入れ、最深到達点は下がらないので
    // 誤った到達率がそのまま永続化される（→ note_image_rendering §6）。
    val pendingHeightDp = LocalConfiguration.current.screenHeightDp
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val widthDp = maxWidth.value.toInt()
        val measured = measurement as? NoteImageMeasurement.Measured
        val heightDp = reservedImageHeightDp(
            widthDp = widthDp,
            sourceWidth = measured?.width ?: 0,
            sourceHeight = measured?.height ?: 0,
            pendingHeightDp = pendingHeightDp
        )
        val widthPx = with(LocalDensity.current) { maxWidth.roundToPx() }

        // 寸法が取れてから中身を読む。先に読むと確保する高さが決まらない。
        // **確かめ終えるまで読み始めない。** 共有された旧寸法で先に復号すると、
        // 新しいBitmapが**古い比率の枠へ収まった状態が見えている時間**ができる
        // （遠いプロバイダほど長い）。世代が同じならヘッダは読み直されないので、
        // この待ちがI/Oを増やすことはない。
        LaunchedEffect(block, measured, widthPx, verified) {
            if (verified && measured != null && widthPx > 0) content = loader.load(block, widthPx)
        }

        val failure = (content as? NoteImageContent.Failed)?.reason
            ?: (measurement as? NoteImageMeasurement.Failed)?.reason
        val bitmap = (content as? NoteImageContent.Loaded)?.bitmap

        when {
            failure != null -> NoteImageFailurePanel(block, failure, heightDp)
            bitmap != null -> NoteImageBitmap(block, bitmap, heightDp)
            else -> NoteImagePlaceholder(heightDp)
        }
    }
}

@Composable
private fun NoteImageBitmap(block: MarkdownBlock.Image, bitmap: ImageBitmap, heightDp: Int) {
    Image(
        bitmap = bitmap,
        // alt が空ならファイル名を読む。ビューアなので画像は装飾ではなく内容。
        contentDescription = noteImageContentDescription(block.alt, noteImageDisplayName(block)),
        // **確保したのと同じ高さで描く。** 高さを指定しないと縦横比なりの高さになり、
        // 極端に横長な画像では最低高さを割って、成功した瞬間にブロックが縮む
        // （＝確保していた意味が無くなり、到達率の水増しが起きる）。
        contentScale = ContentScale.Fit,
        modifier = Modifier.fillMaxWidth().height(heightDp.dp)
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
                text = noteImageFailureText(reason, noteImageDisplayName(block)),
                color = OnSurfaceSubtle,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                modifier = Modifier.semantics {
                    contentDescription = noteImageFailureText(reason, noteImageDisplayName(block))
                }
            )
        }
    }
}
