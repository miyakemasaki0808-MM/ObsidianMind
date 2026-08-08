package com.example.newproject.ui

import android.os.SystemClock
import android.view.MotionEvent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.newproject.MainActivity
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
 * ## 「連打」は Compose のAPIでは作れない
 *
 * `onNodeWithText(...).performClick()` を続けて呼んでも**遷移は重ならない。**
 * `onNodeWithText` が毎回 `fetchSemanticsNode()` でUIと同期してからノードを取り直すため、
 * `waitForIdle()` を書かなくても1操作ずつ落ち着いてしまう
 * （2026-08-08 の外部レビュー指摘。当初の連打テストはこの形で、何も競合させていなかった）。
 *
 * そこで**座標を1度だけ取り、以降は生の `MotionEvent` を同期なしで投げる。**
 * `Instrumentation.sendPointerSync()` は Compose の待ち合わせを通らないので、
 * 遷移が処理される前に次の入力が届く。
 *
 * ## バックスタックは競合と分けて確かめる
 *
 * 最後に選んだ画面が出ていることは、**バックスタックが積み上がっていても成立する。**
 * `launchSingleTop` / `popUpTo` を外しても最終assertは通ってしまうので、
 * **「戻る1回で開始タブへ戻る」**を別の観測点として置く。
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
     * **同じタブを続けて選んでも履歴は増えない。**
     *
     * `launchSingleTop` が効いていることの観測点。外すと同じ行き先が2件積まれ、
     * 戻る1回では「さがす」に留まる。
     */
    @Test
    fun 同じタブを2回選んでも戻る1回で開始タブへ戻る() {
        repeat(2) {
            composeRule.onNodeWithText(TAB_SEARCH).performClick()
            composeRule.waitForIdle()
        }
        composeRule.onNodeWithText(SEARCH_MARKER).assertIsDisplayed()

        pressBackOnce()

        composeRule.onNodeWithText(NOTE_MARKER).assertIsDisplayed()
    }

    /**
     * **同期を挟まずに入力を投げ込んでも、最後の選択に落ち着く。**
     *
     * 座標は最初に1度だけ取り、以降は `sendPointerSync` で生のタップを送る。
     * Compose の待ち合わせを通らないので、遷移の処理中に次の入力が届く。
     */
    @Test
    fun 同期を挟まない連続タップでも最後の選択に落ち着く() {
        val search = centerOf(TAB_SEARCH)
        val options = centerOf(TAB_OPTIONS)
        val note = centerOf(TAB_NOTE)

        repeat(4) {
            tap(search)
            tap(options)
        }
        tap(note)
        composeRule.waitForIdle()

        composeRule.onNodeWithText(NOTE_MARKER).assertIsDisplayed()
        // 連打しても履歴は積み上がらない。
        pressBackOnce()
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

    // --- 補助 -----------------------------------------------------------------

    /** 画面座標での中心。**1度だけ取って使い回す**（取り直すと同期が入る）。 */
    private fun centerOf(text: String): Pair<Float, Float> {
        val node = composeRule.onNodeWithText(text).fetchSemanticsNode()
        val position = node.positionOnScreen
        return (position.x + node.size.width / 2f) to (position.y + node.size.height / 2f)
    }

    /** Compose の待ち合わせを通さずにタップを送る。 */
    private fun tap(point: Pair<Float, Float>) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val at = SystemClock.uptimeMillis()
        listOf(MotionEvent.ACTION_DOWN to at, MotionEvent.ACTION_UP to at + 16).forEach { (action, time) ->
            MotionEvent.obtain(at, time, action, point.first, point.second, 0).use { event ->
                instrumentation.sendPointerSync(event)
            }
        }
    }

    private fun pressBackOnce() {
        composeRule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        composeRule.waitForIdle()
    }

    private inline fun <T> MotionEvent.use(block: (MotionEvent) -> T): T =
        try {
            block(this)
        } finally {
            recycle()
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
