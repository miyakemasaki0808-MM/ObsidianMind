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
 * **同一プロセス内の Activity 再生成**をまたいだ振る舞い（→ instrumentation_testing 段階4b）。
 *
 * ## 覆う範囲と、覆わない範囲
 *
 * `ActivityScenario.recreate()` は `onSaveInstanceState()` の後に Activity を破棄し、
 * 保存された Bundle で**新しい Activity を同じプロセス内に**作る。
 * したがって覆うのは**回転・Fold開閉・設定変更**に相当する経路までである。
 *
 * **プロセス死亡後の復元は覆わない。** プロセス・Application・静的状態・
 * プロセス内キャッシュはすべて生き残るため、
 * **プロセス死亡時だけ初期化される状態や、永続層からの復元の退行は検出されない。**
 * ここが緑でも「プロセス死亡に耐える」とは言えない。
 * 保証したくなったら、対象プロセスを終了して永続状態から起動し直す
 * 独立したシナリオを別に足すこと（→ current_issues TEST-5 の経緯）。
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
class ActivityRecreationTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    /**
     * Activity再生成でオープニングを再生し直さない。
     *
     * `showOpening` は `savedInstanceState == null` で決まる。回転・Fold開閉では
     * 非nullになるため、OPは出ない
     * （出ると、回転のたびにブランドアニメが挟まって操作が中断される）。
     *
     * 判定に `Text("Vigilith AI")` を使えるのは、**UI上ここにしか無い**ため
     * （同じ文字列は補記ファイルの署名にも出るが、そちらは画面ではない）。
     */
    @Test
    fun Activity再生成でオープニングを再生し直さない() {
        // 起動直後のOPは有限アニメなので、ルールが idle を待つ間に終わっている。
        composeRule.onNodeWithText(SCREEN_MARKER).assertIsDisplayed()

        composeRule.activityRule.scenario.recreate()
        composeRule.waitForIdle()

        composeRule.onNodeWithText(OPENING_BRAND).assertDoesNotExist()
        composeRule.onNodeWithText(SCREEN_MARKER).assertIsDisplayed()
    }

    /**
     * 再生成を繰り返しても描画が壊れない。
     *
     * 回転を続けざまに行う操作に相当する（プロセスは生き続ける）。**1回で通ることと、繰り返して通ることは別** —
     * 復元のたびに積み上がる状態があると2回目以降で崩れる。
     */
    @Test
    fun Activity再生成を繰り返しても画面が描画され続ける() {
        repeat(3) {
            composeRule.activityRule.scenario.recreate()
            composeRule.waitForIdle()
            composeRule.onNodeWithText(SCREEN_MARKER).assertIsDisplayed()
        }
        composeRule.onNodeWithText(OPENING_BRAND).assertDoesNotExist()
    }

    private companion object {
        /** OP画面にだけ出るブランド表記。 */
        const val OPENING_BRAND = "Vigilith AI"

        /**
         * ノートタブの見出し。**Vault選択の有無に関わらず出る**ことが要点。
         *
         * 最初は「Vaultを選択」を使っていたが、あれは `if (!vaultSelected)` の中にあり
         * **Vault未選択のときしか描画されない。** instrumentation は実機に入っている
         * アプリの `SharedPreferences` をそのまま使うので、
         * **端末でVaultを選んだ瞬間にテストが落ちる**状態だった（2026-08-08 に実際に落ちた）。
         *
         * **目印は端末の実データに依存しないものから選ぶ。**
         */
        const val SCREEN_MARKER = "Rediscover"
    }
}
