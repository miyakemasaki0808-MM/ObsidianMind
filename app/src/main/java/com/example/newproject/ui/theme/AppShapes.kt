package com.example.newproject.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

// ---------------------------------------------------------------------------
// 面の形の役割。
//
// **形は「その面で何をする場所か」を表すチャネル**である（→ system/bearing_channels.md）。
// 色は年代が持ち切っているので、面の役割は形でしか表せない。
//
// **値ではなく役割で持つ理由。** 冊子と本文が同じ `RoundedCornerShape(8.dp)` を
// 直に書いていた間、区別は誰かが「揃えたほうが綺麗」と言った瞬間に消える状態にあった。
// 役割の違いとして持てば、**どちらの面がどちらの役割を引いているかを走査で固定できる**
// （→ BearingChannelTest）。
//
// **ここに「値が近いから」という理由で3つ目を足さない。** 増やすのは役割が増えたときだけで、
// 役割が増えるということは bearing_channels の割り当て表に行が増えるということである。
// ---------------------------------------------------------------------------

/**
 * **読む面。** 本文が載り、スクロールで続いていく面。
 *
 * 角を丸めるのは、これがアプリのカードだからである。
 * 現在の利用者はノート本文パネル（通常表示・全画面で共用）。
 */
internal val ReadingSurfaceShape: Shape = RoundedCornerShape(8.dp)

/**
 * **眺める面。** 1枚で完結し、めくって次へ渡る面。
 *
 * **ほぼ直角にするのは「断ち切った紙」だから。** 紙は切ってあるもの、UIカードは丸いもの、
 * という一手で読む面と分ける。0dp にしないのは、完全な直角だと画面の隅と見分けが付かず
 * 「面が置かれている」ことまで消えるため。
 *
 * 現在の利用者は冊子の紙。**「眺める面」が2つ目に現れたときも、この役割を共有する**
 * （形を新しく決め直さない）。
 */
internal val BrowsingSheetShape: Shape = RoundedCornerShape(2.dp)
