package com.example.newproject.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.newproject.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * タブ遷移の往復と、連続操作に対する耐性（→ instrumentation_testing 段階4c）。
 *
 * ## JVMでは書けない理由
 *
 * `NavHost` のバックスタックと Compose の再コンポーズを実際に走らせないと出ない。
 * 画面遷移の状態は `NoteUiState` の外（`NavController`）にあるので、
 * 状態のユニットテストでは触れない。
 *
 * ## 何を見ているか
 *
 * **タブは往復できること**と、**素早く切り替えても最後の選択に落ち着くこと**。
 * 後者は `launchSingleTop` とバックスタックの積み上がりに関わる部分で、
 * 積み上がると戻る操作の回数が合わなくなる。
 *
 * ## Vault 未選択のまま確かめる
 *
 * 遷移そのものが対象なので、ノートの中身は要らない。
 * 実依存のまま起動でき、**本番へ差し替え口を入れずに済む**（→ 判断2）。
 */
@RunWith(AndroidJUnit4::class)
class TabNavigationTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun タブを移動して元のタブへ戻れる() {
        composeRule.onNodeWithText(NOTE_MARKER).assertIsDisplayed()

        composeRule.onNodeWithText(TAB_SEARCH).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText(SEARCH_MARKER).assertIsDisplayed()

        composeRule.onNodeWithText(TAB_NOTE).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText(NOTE_MARKER).assertIsDisplayed()
    }

    @Test
    fun 複数のタブを順に開いて戻れる() {
        listOf(TAB_SEARCH to SEARCH_MARKER, TAB_OPTIONS to OPTIONS_MARKER).forEach { (tab, marker) ->
            composeRule.onNodeWithText(tab).performClick()
            composeRule.waitForIdle()
            composeRule.onNodeWithText(marker).assertIsDisplayed()
        }

        composeRule.onNodeWithText(TAB_NOTE).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText(NOTE_MARKER).assertIsDisplayed()
    }

    /**
     * 素早く切り替えても最後の選択に落ち着く。
     *
     * **待ち合わせを挟まずに連打する**のが要点。1操作ずつ idle を待つと、
     * 遷移が重なった状態を作れず、`launchSingleTop` の効きを試したことにならない。
     */
    @Test
    fun タブを連打しても最後の選択に落ち着く() {
        repeat(3) {
            composeRule.onNodeWithText(TAB_SEARCH).performClick()
            composeRule.onNodeWithText(TAB_OPTIONS).performClick()
        }
        composeRule.onNodeWithText(TAB_NOTE).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText(NOTE_MARKER).assertIsDisplayed()
    }

    /** 遷移した先で再生成しても、その画面のまま戻ってくる。 */
    @Test
    fun 遷移先で再生成しても同じ画面へ戻る() {
        composeRule.onNodeWithText(TAB_OPTIONS).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText(OPTIONS_MARKER).assertIsDisplayed()

        composeRule.activityRule.scenario.recreate()
        composeRule.waitForIdle()

        composeRule.onNodeWithText(OPTIONS_MARKER).assertIsDisplayed()
    }

    private companion object {
        const val TAB_NOTE = "ノート"
        const val TAB_SEARCH = "さがす"
        const val TAB_OPTIONS = "オプション"

        /**
         * 各画面の目印。**タブのラベルと重ならない文言を選ぶ。**
         *
         * オプション画面は見出しがタブと同じ「オプション」なので使えない
         * （`onNodeWithText` が2件に当たって落ちる）。画面内の項目名を使う。
         */
        const val NOTE_MARKER = "Rediscover"
        const val SEARCH_MARKER = "Explore"
        const val OPTIONS_MARKER = "AI補記メモを削除"
    }
}
