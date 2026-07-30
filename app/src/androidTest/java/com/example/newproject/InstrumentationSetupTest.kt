package com.example.newproject

import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
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
 * ここで守るのは機能ではなく**環境**。Runnerが起動すること、対象アプリのContextが
 * 引けること、Composeのテストルールが実際に描画できることの3点で、
 * 「土台が壊れた」と「テスト対象が壊れた」を切り分けられるようにする。
 */
@RunWith(AndroidJUnit4::class)
class InstrumentationSetupTest {

    @get:Rule
    val composeRule = createComposeRule()

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

    @Test
    fun Composeのテストルールが描画できる() {
        composeRule.setContent { Text("土台の確認") }
        composeRule.onNodeWithText("土台の確認").assertIsDisplayed()
    }
}
