package com.example.newproject.ui.theme

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * グラデーション直上の文字を、**背景を持たない場所へ書けなくする**。
 *
 * `onVibrant` / `onVibrantMuted` は「鮮やかな背景の上の文字」という意味だが、
 * ライトのグラデーションは停止色によって明るさが 0.121〜0.458 まで動くので、
 * **背景を知らずにこの色を選ぶと必ずどこかで基準を割る**（実測 1.89〜6.13）。
 *
 * したがって使ってよいのは、**自分の背景を所有している共通部品だけ**とする。
 * 画面側が直接使うと、その画面の背景が何であるかを部品の外で仮定することになり、
 * 検証も画面ごとに書き足す必要が出る。
 *
 * 同じ理由で、文字色に任意の `copy(alpha = …)` を掛けるのも禁じる。
 * 実効的な色が下地で変わるため、トークンの実測値が意味を失う
 * （実際に `OnSurface.copy(0.6)` が 4.31、`OnVibrant.copy(0.6)` が 3.14 だった）。
 */
class VibrantTextUsageTest {

    private val screenDir = File("src/main/java/com/example/newproject/ui/screen")

    /**
     * 背景を所有していて、`onVibrant` を使ってよい部品。
     *
     * - `GradientHeader` / `NoteComponents`(IconPill) / `AppScaffold`(NavBar) は自前の面を描く
     * - `FullscreenNoteScreen` の全画面FABとラベルは `accentGlass` を直接背景に敷いている
     * - `OpeningScreen` は起動アニメの専用面（`VigilithSlate`）の上にだけ文字を置く
     *
     * **増やすときは、その面に対するコントラストを [AppColorContrastTest] へ足すこと。**
     * 面を持たない画面をここへ入れると、この検証は意味を失う。
     */
    private val backgroundOwners = setOf(
        "GradientHeader.kt", "NoteComponents.kt", "AppScaffold.kt",
        "FullscreenNoteScreen.kt", "OpeningScreen.kt"
    )

    @Test
    fun `画面は onVibrant を直接使わない`() {
        val offenders = screenDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name !in backgroundOwners }
            .flatMap { file ->
                file.readLines().withIndex()
                    .filter { (_, line) ->
                        !line.trimStart().startsWith("import") &&
                            !line.trimStart().startsWith("//") &&
                            Regex("""\bOnVibrant(Muted)?\b""").containsMatchIn(line)
                    }
                    .map { (i, line) -> "${file.name}:${i + 1} ${line.trim()}" }
            }
            .toList()
        assertTrue(
            "画面から onVibrant を直接使っている。背景を持つ共通部品（GradientHeader 等）へ\n" +
                "寄せるか、その部品を新設すること:\n" + offenders.joinToString("\n"),
            offenders.isEmpty()
        )
    }

    @Test
    fun `文字色に任意のアルファを掛けない`() {
        // 塗り・枠線・トラックは非文字（3:1）で基準が違うため対象外にする。
        // ただし「文字トークンを塗りへ流用する」こと自体は別の既知課題として扱う。
        val fillProperty = Regex("""(?i)(track|border|container|indicator|scrim|background)Color\s*=""")
        val textTokens = Regex("""\b(OnSurface|OnVibrant|OnVibrantMuted|OnSurfaceMuted|OnSurfaceSubtle|OnSurfaceFaint|AccentText|ErrorText)\.copy\(alpha""")
        val offenders = File("src/main/java/com/example/newproject/ui").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                file.readLines().withIndex()
                    .filter { (_, line) ->
                        textTokens.containsMatchIn(line) && !fillProperty.containsMatchIn(line)
                    }
                    .map { (i, line) -> "${file.name}:${i + 1} ${line.trim()}" }
            }
            .toList()
        assertTrue(
            "文字色に copy(alpha = …) を掛けている。実効的な色が下地で変わり、\n" +
                "トークンの実測値が意味を失う。弱さは名前付きトークンの段階から選ぶこと:\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty()
        )
    }
}
