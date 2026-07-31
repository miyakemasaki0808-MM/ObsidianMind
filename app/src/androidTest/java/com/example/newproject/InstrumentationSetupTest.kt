package com.example.newproject

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * instrumentation テストの土台が組み上がっていることだけを確かめる。
 *
 * このソースセットは長らく存在せず、`androidTestImplementation` も
 * `testInstrumentationRunner` も未設定だった。そのため「実端末でしか確認できない領域
 * （SAF走査・端末AI・Compose Navigation・画面回転）のテストを書こう」と思った時点で、
 * 依存の選定から始めなければならない状態が続いていた。
 *
 * ここで守るのは機能ではなく**環境**。Composeのテストルールを使わないことで、
 * Runner／Contextの故障をUI同期の初期化失敗から切り離して観測する。
 */
@RunWith(AndroidJUnit4::class)
class InstrumentationSetupTest {
    @Test
    fun 対象アプリのContextが引ける() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        // テストAPKは対象アプリの applicationId に `.test` を足したIDを名乗る。
        // ここが食い違うと、テストが別プロセスを見ていることになる。
        assertEquals(
            target.packageName + ".test",
            InstrumentationRegistry.getInstrumentation().context.packageName
        )
    }
}
