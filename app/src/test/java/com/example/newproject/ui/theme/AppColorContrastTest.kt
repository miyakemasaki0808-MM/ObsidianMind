package com.example.newproject.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * 配色の意図を数値で固定する。
 *
 * ここで守るのは「誰かが色を1つ変えたときに、気づかないままAA基準を割らないこと」。
 * 見た目の好みではなく、読めるかどうかだけを見る。
 *
 * トップレベルの `Panel` 等は `@Composable` の窓口になったためテストからは読めない。
 * 値の実体である [LightAppColors] / [DarkAppColors] を直接検証する。
 */
class AppColorContrastTest {

    private fun relativeLuminance(color: Color): Double {
        fun channel(v: Float): Double {
            val c = v.toDouble()
            return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(color.red) +
            0.7152 * channel(color.green) +
            0.0722 * channel(color.blue)
    }

    private fun contrast(a: Color, b: Color): Double {
        val la = relativeLuminance(a)
        val lb = relativeLuminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    private fun assertAtLeast(expected: Double, actual: Double, label: String) {
        assertTrue(
            "$label のコントラストが $expected を下回った（実測 ${"%.2f".format(actual)}）",
            actual >= expected
        )
    }

    private val schemes = listOf("ライト" to LightAppColors, "ダーク" to DarkAppColors)

    // -- ボタン3役 -----------------------------------------------------------
    // ラベルは 4.5:1（文字）、塗りは 3:1（非文字＝ボタンの輪郭が見つかること）。

    @Test
    fun `塗りボタンのラベルは明暗どちらでもAA基準を満たす`() {
        schemes.forEach { (name, c) ->
            assertAtLeast(4.5, contrast(c.onButtonPrimary, c.buttonPrimary), "$name ButtonPrimary のラベル")
            assertAtLeast(4.5, contrast(c.onButtonSecondary, c.buttonSecondary), "$name ButtonSecondary のラベル")
            assertAtLeast(4.5, contrast(c.onButtonAi, c.buttonAi), "$name ButtonAi のラベル")
        }
    }

    @Test
    fun `塗りボタンは自面の上で輪郭が判別できる`() {
        schemes.forEach { (name, c) ->
            assertAtLeast(3.0, contrast(c.buttonPrimary, c.panel), "$name ButtonPrimary の塗り")
            assertAtLeast(3.0, contrast(c.buttonSecondary, c.panel), "$name ButtonSecondary の塗り")
            assertAtLeast(3.0, contrast(c.buttonAi, c.panel), "$name ButtonAi の塗り")
        }
    }

    @Test
    fun `AIボタンはダークで明るい版に差し替わっている`() {
        // 元のIndigo(#4D3DFF)は暗面で2.83しかなく、塗りとしても文字としても使えない。
        // 色相・彩度を保ったまま明度だけ上げた別値になっていることを固定する。
        assertNotEquals(LightAppColors.buttonAi, DarkAppColors.buttonAi)
        assertTrue(
            "ダークのButtonAiが元のIndigoのままなら暗面で基準を割る",
            contrast(LightAppColors.buttonAi, DarkAppColors.panel) < 3.0
        )
    }

    @Test
    fun `白ラベルはライトのIndigo以外では基準を満たさない`() {
        // 「白で統一したくなる」誘惑への歯止め。ここが緩むと元の不具合に戻る。
        assertTrue(contrast(Color.White, LightAppColors.buttonPrimary) < 4.5)
        assertTrue(contrast(Color.White, LightAppColors.buttonSecondary) < 4.5)
        assertTrue(contrast(Color.White, LightAppColors.buttonAi) >= 4.5)
    }

    // -- 面の上の文字 ---------------------------------------------------------

    /**
     * 面の上に置く文字は、明暗どちらでも例外なくAAを満たす。
     *
     * 見出し2色は 11sp Bold で、AAの大文字例外（18pt相当／Bold 14pt相当）に**入らない**。
     * 小さい文字ほど基準が要るので、装飾的な色でも4.5:1から降りない。
     */
    @Test
    fun `面の上の文字トークンは明暗どちらでもAA基準を満たす`() {
        schemes.forEach { (name, c) ->
            mapOf(
                "onSurface" to c.onSurface,
                "onSurfaceMuted" to c.onSurfaceMuted,
                "onSurfaceSubtle" to c.onSurfaceSubtle,
                "onSurfaceFaint" to c.onSurfaceFaint,
                "onSurfaceMetaBlue" to c.onSurfaceMetaBlue,
                "linkText" to c.linkText,
                "errorText" to c.errorText,
                "dangerAction" to c.dangerAction,
                "relatedHeading" to c.relatedHeading,
                "aiHeading" to c.aiHeading
            ).forEach { (token, color) ->
                assertAtLeast(4.5, contrast(color, c.panel), "$name $token（panel上）")
            }
        }
    }

    /**
     * 弱い文字は3段階しか持たない。
     *
     * ライトの白面ではAAの床が #767676（ちょうど4.50）にあり、本文 `#202124` から
     * そこまでの間に区別できる濃さは3つしか取れない。4つ目を足すと、名前は違うのに
     * 実質同じ濃さのトークンが増えて「どれを使うか」が決められなくなる。
     * 段数を明暗で揃えることも同時に固定する（片方だけ増える事故の検出）。
     */
    @Test
    fun `弱い文字は明暗どちらも3段階で単調に薄くなる`() {
        schemes.forEach { (name, c) ->
            val steps = listOf(c.onSurface, c.onSurfaceMuted, c.onSurfaceSubtle, c.onSurfaceFaint)
                .map { contrast(it, c.panel) }
            steps.zipWithNext().forEach { (strong, weak) ->
                assertTrue(
                    "$name の弱い文字は onSurface → Muted → Subtle → Faint の順に薄くなること" +
                        "（実測 ${steps.joinToString { "%.2f".format(it) }}）",
                    strong > weak
                )
            }
            assertAtLeast(4.5, steps.last(), "$name の最も弱い文字（onSurfaceFaint）")
        }
    }

    /**
     * **バッジの塗りは下部ナビ帯の上では基準を満たしていない**（既知・未修正）。
     *
     * パネル上の塗りと違い、バッジはブランド色そのものの帯（ライト=Indigo／ダーク=`navBar`）
     * に載る。ライトのIndigoは彩度が高く相対輝度も中位なので、その上で3:1を取れる塗りが
     * ほとんど無い。中の記号は対の前景で読めるようにしてあるため、状態の判別は記号が担う。
     *
     * 直すには塗りの明度を上げるか輪郭線を足すかで、どちらもナビ帯の見た目に踏み込む。
     * ここは実測値を固定するだけに留め、判断は別途行う。
     */
    @Test
    fun `ナビ帯の上のバッジ塗りが基準未達であることを記録する`() {
        val onLightNav = mapOf(
            "Successバッジ（緑）" to contrast(LightAppColors.buttonSecondary, LightAppColors.navBar),
            "Errorバッジ（赤）" to contrast(LightAppColors.errorSurface, LightAppColors.navBar)
        )
        onLightNav.forEach { (label, actual) ->
            assertTrue(
                "$label がライトのナビ帯で3:1を満たすようになった（実測 ${"%.2f".format(actual)}）。" +
                    "修正が入ったならこのテストを消し、上の基準テストへ移すこと",
                actual < 3.0
            )
        }
        // 中の記号は塗りの上で読めていること（ここは満たしている）。
        assertAtLeast(
            4.5,
            contrast(LightAppColors.onButtonSecondary, LightAppColors.buttonSecondary),
            "Successバッジの「✓」"
        )
        assertAtLeast(
            4.5,
            contrast(LightAppColors.onErrorSurface, LightAppColors.errorSurface),
            "Errorバッジの「!」"
        )
    }

    @Test
    fun `グラデーション上の文字は明暗どちらでも読める`() {
        // 背景がグラデーションなので、最も明るい停止色との比で見る
        // （ライトはAqua、ダークは最も明るい暗色）。
        assertAtLeast(4.5, contrast(LightAppColors.onVibrant, Indigo), "ライト onVibrant（Indigo上）")
        schemes.forEach { (name, c) ->
            assertAtLeast(4.5, contrast(c.onVibrantMuted, LogoNavy), "$name onVibrantMuted")
        }
    }

    // -- 塗りの上に載る文字（バッジ・チップ） -----------------------------------

    @Test
    fun `塗りとして使う色は前景トークンと対で基準を満たす`() {
        // レビュー指摘の再発防止。`errorText` や `dangerAction` を Badge の containerColor へ
        // 流用したうえで白文字を固定していたため、暗所で「明るい赤に白文字」（2.52〜2.78）
        // という読めない組み合わせが生まれていた。塗りには必ず対の前景を使う。
        schemes.forEach { (name, c) ->
            assertAtLeast(4.5, contrast(c.onErrorSurface, c.errorSurface), "$name エラーバッジの文字")
            assertAtLeast(4.5, contrast(c.onDangerAction, c.dangerAction), "$name 削除チップの文字")
            assertAtLeast(4.5, contrast(c.onAccentSurface, c.accentSurface), "$name アクセント塗りの文字")
        }
    }

    @Test
    fun `Vigilithの状態バッジは暗い背景から見つけられる`() {
        // Readyバッジは `Indigo` 固定のままだと暗背景で 2.20 しかなく、輪郭が出ない。
        // 非文字コントラストの3:1を満たす塗りを使う。
        assertAtLeast(3.0, contrast(DarkAppColors.accentSurface, DarkAppColors.panel), "Readyバッジの塗り")
        assertAtLeast(3.0, contrast(DarkAppColors.errorSurface, DarkAppColors.navBar), "Errorバッジの塗り")
        assertTrue(
            "Indigoをそのままバッジに使うと暗背景で基準を割る（この前提が変わったら見直す）",
            contrast(Indigo, DarkAppColors.panel) < 3.0
        )
    }

    // -- 面どうしの分離 -------------------------------------------------------

    @Test
    fun `ダークではパネルが背景から浮いている`() {
        // 暗面どうしは輝度差が小さいので比では測りにくい。ここでは「同じ色ではない」
        // ことと、パネルが背景より明るい側にあることだけを固定する。
        assertNotEquals(DarkAppColors.panel, DarkAppColors.codePanel)
        assertTrue(
            "ダークのcodePanelはpanelより沈んでいること（コードブロックが浮くと本文より目立つ）",
            relativeLuminance(DarkAppColors.codePanel) < relativeLuminance(DarkAppColors.panel)
        )
        assertTrue(
            "ダークのskeletonHighlightはskeletonBaseより明るいこと（走る光が見えない）",
            relativeLuminance(DarkAppColors.skeletonHighlight) >
                relativeLuminance(DarkAppColors.skeletonBase)
        )
    }

    @Test
    fun `明暗で文字と面の明暗関係が反転している`() {
        // ライトは暗い文字／明るい面、ダークはその逆。片方の値をコピーし忘れる事故の検出。
        assertTrue(
            relativeLuminance(LightAppColors.onSurface) < relativeLuminance(LightAppColors.panel)
        )
        assertTrue(
            relativeLuminance(DarkAppColors.onSurface) > relativeLuminance(DarkAppColors.panel)
        )
    }

    // -- クイズ画面（テーマに追従しない固定の暗色配色） -------------------------

    @Test
    fun `クイズ画面の文字は暗面でAA基準を満たす`() {
        assertAtLeast(4.5, contrast(OnQuizSurface, QuizPanel), "OnQuizSurface（QuizPanel上）")
        assertAtLeast(4.5, contrast(OnQuizAccent, QuizSurface), "OnQuizAccent（QuizSurface上）")
        assertAtLeast(4.5, contrast(OnQuizMuted, QuizPanel), "OnQuizMuted（QuizPanel上）")
        assertAtLeast(4.5, contrast(OnQuizLoading, QuizSurface), "OnQuizLoading（QuizSurface上）")
        // 以前は #CC0000（2.86）と #555555（2.25）で読めなかった箇所。
        assertAtLeast(4.5, contrast(OnQuizError, QuizSurface), "OnQuizError（QuizSurface上）")
        assertAtLeast(4.5, contrast(OnQuizEmpty, QuizSurface), "OnQuizEmpty（QuizSurface上）")
    }

    @Test
    fun `正解と誤答の色は暗面で区別できる`() {
        assertAtLeast(3.0, contrast(SuccessMark_ForTest, QuizPanel), "正解")
        assertAtLeast(3.0, contrast(FailureMark_ForTest, QuizSurface), "誤答")
        assertNotEquals(SuccessMark_ForTest, FailureMark_ForTest)
    }

    private val SuccessMark_ForTest = LightAppColors.successMark
    private val FailureMark_ForTest = LightAppColors.failureMark

    // -- テーマ構造 -----------------------------------------------------------

    @Test
    fun `ライトとダークで全トークンが別の値を持つか意図的に共有している`() {
        // 片方だけ定義し忘れる事故を防ぐ。共有してよいのは、明暗どちらでも成立すると
        // 判断した色（白文字・ピンク/緑の塗り・誤答）だけ。
        val intentionallyShared = setOf(
            "onVibrant",          // 白。暗面でも明面（グラデーション）でも文字として成立する
            "buttonPrimary",      // ピンク。明暗どちらでも塗りとして基準を満たす
            "onButtonPrimary",    // 黒ラベル
            "onButtonSecondary",  // 黒ラベル
            "failureMark"         // 誤答の赤。明暗どちらでも区別できる
        )
        val pairs = mapOf(
            "panel" to (LightAppColors.panel to DarkAppColors.panel),
            "onSurface" to (LightAppColors.onSurface to DarkAppColors.onSurface),
            "onVibrant" to (LightAppColors.onVibrant to DarkAppColors.onVibrant),
            "linkText" to (LightAppColors.linkText to DarkAppColors.linkText),
            "errorText" to (LightAppColors.errorText to DarkAppColors.errorText),
            "buttonPrimary" to (LightAppColors.buttonPrimary to DarkAppColors.buttonPrimary),
            "buttonSecondary" to (LightAppColors.buttonSecondary to DarkAppColors.buttonSecondary),
            "buttonAi" to (LightAppColors.buttonAi to DarkAppColors.buttonAi),
            "onButtonPrimary" to (LightAppColors.onButtonPrimary to DarkAppColors.onButtonPrimary),
            "onButtonSecondary" to (LightAppColors.onButtonSecondary to DarkAppColors.onButtonSecondary),
            "onButtonAi" to (LightAppColors.onButtonAi to DarkAppColors.onButtonAi),
            "failureMark" to (LightAppColors.failureMark to DarkAppColors.failureMark),
            "successMark" to (LightAppColors.successMark to DarkAppColors.successMark)
        )
        pairs.forEach { (name, pair) ->
            val (light, dark) = pair
            if (name in intentionallyShared) {
                assertEquals("$name は明暗で共有する想定", light, dark)
            } else {
                assertNotEquals("$name にダーク専用の値が入っていない", light, dark)
            }
        }
    }
}
