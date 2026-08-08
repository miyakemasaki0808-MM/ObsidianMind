package com.example.newproject.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.newproject.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 画面回転・プロセス再生成をまたいだ振る舞い（→ instrumentation_testing 段階4b）。
 *
 * ## JVMでは書けない理由
 *
 * `Activity` の再生成そのものが対象で、`savedInstanceState` の有無で分岐する。
 * Compose の再コンポーズも実際に走らせないと確かめられない。
 *
 * ## 本番変更は要らなかった
 *
 * 着手前は「Activity を通すなら `MainActivity` の `by viewModels()` に
 * 差し替え口が要るのでは」と見ていたが、**ここで確かめたい振る舞いは
 * Vault 未選択のままで観測できる**ので、実依存のまま起動してよい。
 * 判断2（先回りで seam を作らない）をそのまま維持する。
 *
 * ## ここで扱わないもの
 *
 * **`VigilithHost` の位置（`rememberSaveable`）の復元は入れていない。**
 * 検証にはドラッグ操作と座標の突き合わせが要り、実測のぶれで
 * 壊れていないのに赤くなりやすい。**入れるなら別の観測方法を先に決める**
 * （位置そのものではなく、保存キーの往復を見る等）。
 */
@RunWith(AndroidJUnit4::class)
class ProcessRecreationTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    /**
     * 再生成でオープニングを再生し直さない。
     *
     * `showOpening` は `savedInstanceState == null` で決まる。回転・Fold開閉・
     * プロセス復元では非nullになるため、OPは出ない
     * （出ると、回転のたびにブランドアニメが挟まって操作が中断される）。
     *
     * 判定に `Text("Vigilith AI")` を使えるのは、**UI上ここにしか無い**ため
     * （同じ文字列は補記ファイルの署名にも出るが、そちらは画面ではない）。
     */
    @Test
    fun 再生成でオープニングを再生し直さない() {
        // 起動直後のOPは有限アニメなので、ルールが idle を待つ間に終わっている。
        composeRule.onNodeWithText(VAULT_BUTTON).assertIsDisplayed()

        composeRule.activityRule.scenario.recreate()
        composeRule.waitForIdle()

        composeRule.onNodeWithText(OPENING_BRAND).assertDoesNotExist()
        composeRule.onNodeWithText(VAULT_BUTTON).assertIsDisplayed()
    }

    /**
     * 再生成を繰り返しても描画が壊れない。
     *
     * 回転を続けざまに行う操作に相当する。**1回で通ることと、繰り返して通ることは別** —
     * 復元のたびに積み上がる状態があると2回目以降で崩れる。
     */
    @Test
    fun 再生成を繰り返しても画面が描画され続ける() {
        repeat(3) {
            composeRule.activityRule.scenario.recreate()
            composeRule.waitForIdle()
            composeRule.onNodeWithText(VAULT_BUTTON).assertIsDisplayed()
        }
        composeRule.onNodeWithText(OPENING_BRAND).assertDoesNotExist()
    }

    private companion object {
        /** OP画面にだけ出るブランド表記。 */
        const val OPENING_BRAND = "Vigilith AI"

        /** Vault未選択のときに必ず出るボタン。復元後も操作可能であることの目印。 */
        const val VAULT_BUTTON = "Vaultを選択"
    }
}
