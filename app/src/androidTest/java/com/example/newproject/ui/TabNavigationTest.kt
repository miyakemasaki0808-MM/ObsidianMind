package com.example.newproject.ui

import android.os.SystemClock
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.newproject.MainActivity
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * タブ遷移の往復・バックスタック契約・連続入力に対する耐性（→ instrumentation_testing 段階4c）。
 *
 * ## JVMでは書けない理由
 *
 * `NavHost` のバックスタックと Compose の再コンポーズを実際に走らせないと出ない。
 * 画面遷移の状態は `NoteUiState` の外（`NavController`）にあるので、状態のテストでは触れない。
 *
 * ## 「連打」は**ここでは試していない**
 *
 * `onNodeWithText(...).performClick()` を続けて呼んでも**遷移は重ならない。**
 * `onNodeWithText` が毎回 `fetchSemanticsNode()` でUIと同期してからノードを取り直すため、
 * `waitForIdle()` を書かなくても1操作ずつ落ち着いてしまう。
 *
 * 生の `MotionEvent` を `Instrumentation.sendPointerSync()` で投げる形も試したが、
 * **Android 17 では instrumentation のUIDからの入力注入が拒否される。**
 * UiAutomator を足せば通る余地はあるが、**1つのテストのために依存を増やさない。**
 *
 * したがって**競合そのものは主張しない。** 連打で本当に困るのは
 * 「履歴が積み上がって戻る回数が合わなくなる」ことなので、
 * **その契約のほうを「戻る」で直接観測する。**
 *
 * ## バックスタックは「戻る」で観測する
 *
 * 最後に選んだ画面が出ていることは、**履歴が積み上がっていても成立する。**
 * `launchSingleTop` / `popUpTo` を外しても最終assertは通ってしまうため、
 * **戻ったときにどこへ着くか**を観測点にする。
 *
 * **守るのは3設定（`popUpTo` / `launchSingleTop` / `restoreState`）の組み合わせ**であって、
 * 個々のオプション単体ではない（実測の内訳は下の各テストのKDocにある）。
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
     * **戻る1回で開始タブへ戻る。** タブを何枚めくっても履歴は積み上がらない。
     *
     * `navigateToTab()` の `popUpTo(startDestination)` が効いていることの観測点。
     * これを外すと `ノート → さがす → オプション` が積まれ、戻る1回では「さがす」に着く。
     */
    @Test
    fun 複数のタブを開いても戻る1回で開始タブへ戻る() {
        composeRule.onNodeWithText(TAB_SEARCH).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText(TAB_OPTIONS).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText(OPTIONS_MARKER).assertIsDisplayed()

        pressBackOnce()

        composeRule.onNodeWithText(NOTE_MARKER).assertIsDisplayed()
    }

    /**
     * **開始タブを選び直しても履歴は増えない。**
     *
     * 判定は**戻るがActivityの終了になるか** — 履歴が1件なら終了し、
     * 2件積まれていれば1件目へ戻るだけで終了しない。
     *
     * **これが守るのは3設定の組み合わせであって、`launchSingleTop` 単体ではない。**
     * 実機変異（2026-08-08）の結果は次のとおり。
     *
     * | 外した設定 | 結果 |
     * |---|---|
     * | `launchSingleTop` だけ | **緑のまま**（`restoreState` が肩代わりする） |
     * | `launchSingleTop` ＋ `restoreState` | **落ちる**（`RESUMED` のまま） |
     *
     * **単体を隔離するテストは作らない。** それは AndroidX のオプションの挙動確認であって、
     * 本番の配線を守ることにならない。ここで固定したいのは
     * **「タブを何度選んでも戻る操作が設計どおりになる」という契約**そのものである。
     */
    @Test
    fun 開始タブを選び直しても履歴は増えない() {
        composeRule.onNodeWithText(NOTE_MARKER).assertIsDisplayed()
        composeRule.onNodeWithText(TAB_NOTE).performClick()
        composeRule.waitForIdle()

        composeRule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }

        assertTrue(
            "戻るでActivityが終了しなかった。開始タブの履歴が積み上がっている" +
                "（`launchSingleTop` を確認すること）。現在の状態: ${activityState()}",
            waitUntilDestroyed()
        )
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

    // --- 補助 -----------------------------------------------------------------

    private fun pressBackOnce() {
        composeRule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        composeRule.waitForIdle()
    }

    private fun activityState() = composeRule.activityRule.scenario.state

    /** 終了は非同期に反映されるので、少し待ってから判定する。 */
    private fun waitUntilDestroyed(timeoutMillis: Long = 3_000): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMillis
        while (SystemClock.uptimeMillis() < deadline) {
            if (activityState() == Lifecycle.State.DESTROYED) return true
            Thread.sleep(50)
        }
        return activityState() == Lifecycle.State.DESTROYED
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
