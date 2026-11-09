package com.example.newproject.ui

import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue

/**
 * **実窓がアプリの机を出す幅（Expanded）のときだけ続ける。** 狭い窓では skip する。
 *
 * 机は 960dp の箱で組み、窓より大きいと中央に寄せて置かれる。カバー画面のような狭い窓では箱の両端が窓の外へ出て、
 * 題名の表示確認と端寄りの払いが届かない。アプリも閉じた外画面には机を出さないので、
 * 机の保証は内側の画面で確かめる（→ docs/dev/system/instrumentation_testing.md 判断11）。
 * 題名の表示や端寄りの座標に頼るテストと、机でだけ出す部品のテストが呼ぶ。存在の確認へ弱めて通すことはしない。
 * **見るのは箱が窓に収まるかだけで、折り目は見ない** — 折り目はテストが決める（同 判断12）。
 */
@OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
internal fun assumeDeskWindow() {
    val configuration = InstrumentationRegistry.getInstrumentation().targetContext.resources.configuration
    val window = DpSize(configuration.screenWidthDp.dp, configuration.screenHeightDp.dp)
    assumeTrue(
        "机を出さない幅の窓（${configuration.screenWidthDp}dp）。机の保証は内側の画面で確かめる",
        WindowSizeClass.calculateFromSize(window).widthSizeClass == WindowWidthSizeClass.Expanded
    )
}
