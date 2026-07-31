package com.example.newproject

import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** ComposeのテストルールとEspressoのUI同期が対象端末で動作することを確認する。 */
@RunWith(AndroidJUnit4::class)
class ComposeRenderingSetupTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun Composeのテストルールが描画できる() {
        composeRule.setContent { Text("土台の確認") }
        composeRule.onNodeWithText("土台の確認").assertIsDisplayed()
    }
}
