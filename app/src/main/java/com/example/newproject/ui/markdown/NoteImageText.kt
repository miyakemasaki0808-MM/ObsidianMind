package com.example.newproject.ui.markdown

import com.example.newproject.model.NoteImageFailure

// ---------------------------------------------------------------------------
// 画像プレースホルダの文面と高さ。Composeを起動せずJVMテストできるよう純関数に切り出す。
//
// **文面の方針:** 内部語（索引・解決・復号）を出さない。ユーザーにとっての事実は
// 「ファイルが見つからない」「この形式は出せない」であって、こちらの実装都合ではない。
// そして**「無い」と「確かめられなかった」を必ず別の言葉にする** —
// 同じ文面にすると、同期の途中を「ファイルを消してしまった」と読み違える。
// ---------------------------------------------------------------------------

/**
 * プレースホルダの高さ（dp）の下限。
 *
 * **0にしてはいけない。** 高さ0のブロックは `visibleFractionOfBlock` が
 * 「全部見えている」として扱うため、読書痕跡の到達率が水増しされ、
 * その水増しは最深到達点として固着したままサイドカーへ書かれる
 * （→ note_image_rendering §6）。
 */
internal const val NOTE_IMAGE_MIN_HEIGHT_DP = 96

/** 寸法が分かるまでのプレースホルダ高さ（dp）。 */
internal const val NOTE_IMAGE_PENDING_HEIGHT_DP = 160

/**
 * 表示幅と元の寸法から、確保すべき高さ（dp）を出す。
 *
 * 寸法が不明（[sourceWidth] か [sourceHeight] が0以下）なら
 * [NOTE_IMAGE_PENDING_HEIGHT_DP] を返す。**分からないことを理由に0を返さない。**
 *
 * 縦横比どおりの高さを返すので、**復号が終わってもレイアウトが動かない**。
 * 動くと、読み込み前後で画面に入るブロック数が変わって到達率がずれる。
 */
internal fun reservedImageHeightDp(widthDp: Int, sourceWidth: Int, sourceHeight: Int): Int {
    if (widthDp <= 0) return NOTE_IMAGE_MIN_HEIGHT_DP
    if (sourceWidth <= 0 || sourceHeight <= 0) return NOTE_IMAGE_PENDING_HEIGHT_DP
    val scaled = widthDp.toLong() * sourceHeight / sourceWidth
    return scaled.coerceIn(NOTE_IMAGE_MIN_HEIGHT_DP.toLong(), Int.MAX_VALUE.toLong()).toInt()
}

/**
 * 失敗の理由をユーザー向けの1文にする。
 *
 * [fileName] は原文に書かれていた参照先。**必ず添える** — どの画像の話か分からないと、
 * 長いノートでは直しようがない。
 */
internal fun noteImageFailureText(reason: NoteImageFailure, fileName: String): String =
    when (reason) {
        is NoteImageFailure.NotFound ->
            "画像が見つかりません（$fileName）"

        is NoteImageFailure.Unverifiable ->
            "画像を確認できませんでした（$fileName）。フォルダを読み取れていない可能性があります"

        is NoteImageFailure.Ambiguous ->
            "同じ名前の画像が${reason.candidateCount}件あります（$fileName）。" +
                "フォルダを含めた書き方にすると特定できます"

        is NoteImageFailure.External ->
            "外部の画像は表示できません（${reason.url}）"

        is NoteImageFailure.Empty ->
            "画像の参照先が空です"

        is NoteImageFailure.Unsupported ->
            "この形式の画像は表示できません（$fileName）"

        is NoteImageFailure.TooLarge ->
            "画像が大きすぎて表示できません（$fileName）"

        is NoteImageFailure.Broken ->
            "画像を読み取れませんでした（$fileName）"
    }

/**
 * 読み上げ用の説明。
 *
 * alt が空なら**ファイル名を読む**。本アプリはビューアなので、
 * ノートに置かれた画像は装飾ではなく内容であり、読み飛ばす（null にする）と
 * 「そこに何かある」ことすら伝わらない。
 */
internal fun noteImageContentDescription(alt: String, fileName: String): String =
    alt.ifBlank { fileName }
