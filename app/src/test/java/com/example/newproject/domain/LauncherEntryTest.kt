package com.example.newproject.domain

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ランチャー再タップの重複起動を畳む判定を固定する。
 *
 * **真理値表を全部置く。** 条件が3つしかないので、どれか1つを落としたり
 * 反転させたりする変更は、必ずどれかのケースで落ちる。
 *
 * SDKの定数値をここへ書けるのは、走査対象が `app/src/main` だけだからである
 * （→ `PackageDependencyTest`）。**本番側には1文字も書き写していない。**
 */
class LauncherEntryTest {

    @Test
    fun `既存タスクの上へ積まれたランチャー起動は畳む`() {
        assertTrue(duplicateLaunch(isTaskRoot = false, action = MAIN, categories = setOf(LAUNCHER)))
    }

    /**
     * **タスクの最初の1枚は畳まない。**
     *
     * ここを落とすと通常のコールド起動まで `finish()` され、アプリが開かなくなる。
     */
    @Test
    fun `タスクの最初の1枚は畳まない`() {
        assertFalse(duplicateLaunch(isTaskRoot = true, action = MAIN, categories = setOf(LAUNCHER)))
    }

    /**
     * **ランチャー以外の入口は畳まない。**
     *
     * 通知・ディープリンク・共有からの起動は基点Intentが違うので上へ積まれるが、
     * 畳むと「アプリが開いているときだけリンクが効かない」という別の欠陥になる。
     * 素のコンポーネント指定（`am start -n`）も action を持たないのでここに含まれ、
     * **実機検証がタスクを作る起動そのものは畳まれない。**
     */
    @Test
    fun `ランチャー以外の入口は畳まない`() {
        assertFalse(
            "action が別（通知・ディープリンク）",
            duplicateLaunch(isTaskRoot = false, action = "android.intent.action.VIEW", categories = setOf(LAUNCHER))
        )
        assertFalse(
            "action が無い（素のコンポーネント指定）",
            duplicateLaunch(isTaskRoot = false, action = null, categories = setOf(LAUNCHER))
        )
        assertFalse(
            "LAUNCHER カテゴリが付いていない",
            duplicateLaunch(isTaskRoot = false, action = MAIN, categories = setOf("android.intent.category.DEFAULT"))
        )
        assertFalse(
            "カテゴリが1つも付いていない",
            duplicateLaunch(isTaskRoot = false, action = MAIN, categories = null)
        )
    }

    /**
     * **ガードが効く前提（`MainActivity` が `standard` であること）を固定する。**
     *
     * `launchMode` を `singleTask` や `singleTop` へ変えると、ランチャー再タップでは
     * `onCreate` ではなく `onNewIntent` が呼ばれる。**ガードは呼ばれないまま残り、
     * 判定は死ぬ**が、テストもアプリも緑のまま通る。
     * `launchMode` を変えない判断そのものが前提なので、ここで見る。
     */
    @Test
    fun `MainActivity は launchMode を持たない`() {
        val manifest = repositoryRoot().resolve("app/src/main/AndroidManifest.xml")
        assertTrue("AndroidManifest.xml がありません: $manifest", manifest.isFile)

        assertFalse(
            "MainActivity に launchMode が付きました。付けるならガードの要否から見直すこと",
            "android:launchMode" in manifest.readText()
        )
    }

    private fun duplicateLaunch(isTaskRoot: Boolean, action: String?, categories: Set<String>?): Boolean =
        isDuplicateLauncherLaunch(
            isTaskRoot = isTaskRoot,
            action = action,
            categories = categories,
            launcherAction = MAIN,
            launcherCategory = LAUNCHER
        )

    private fun repositoryRoot(): File {
        val workingDirectory = File(requireNotNull(System.getProperty("user.dir")))
        val candidates = listOf(workingDirectory.resolve(".."), workingDirectory)
        return candidates.firstOrNull { it.resolve("CLAUDE.md").isFile }
            ?: error("リポジトリルートが見つかりません（作業ディレクトリ: $workingDirectory）")
    }

    private companion object {
        /** `android.content.Intent.ACTION_MAIN` と同値。 */
        const val MAIN = "android.intent.action.MAIN"

        /** `android.content.Intent.CATEGORY_LAUNCHER` と同値。 */
        const val LAUNCHER = "android.intent.category.LAUNCHER"
    }
}
