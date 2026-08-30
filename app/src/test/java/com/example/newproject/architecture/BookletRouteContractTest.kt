package com.example.newproject.architecture

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 冊子ルートの**配線**を固定する。
 *
 * ## この検査が保証すること・しないこと
 *
 * **見るのは「ルートがその境界を通っているか」だけである。** 呼び出し先が何をするかは
 * ここでは分からないので、**振る舞いは別のテストが持つ**。
 *
 * | 契約 | 振る舞いを見ているところ |
 * |---|---|
 * | 冊子の間は読書時間を積まない | `ReadingTraceControllerTest`（停止理由つきの fake clock） |
 * | 取り消した読込は記録もAIも始めない | `NoteSessionCoordinatorTest`（履歴・状態・要約を観測） |
 * | 渡した本文は先頭から始まる | `BookletScreenTest`（実際の `LazyListState` の位置） |
 *
 * **ここが残っている理由は、`MainActivity` のルート定義だけが素のJVMから触れないため。**
 * `NoteViewModel` は `AndroidViewModel` で組み立てられず、ルートの配線を差し替えても
 * 上の3つはどれも落ちない。したがって**構造だけ**を見る — 呼び出し先の中身を空にする
 * 変異は上の3つが落とす。
 *
 * 正本は [features/booklet_mode.md](../../../../../../../../docs/dev/features/booklet_mode.md)。
 */
class BookletRouteContractTest {

    @Test
    fun `冊子ルートは読書時間を止めて戻す`() {
        val route = bookletRoute()

        // **止める理由と戻す理由は同じでなければならない。** 食い違うと停止理由が
        // 消えないまま残り、ノートへ戻っても計測が再開しない（逆に取り違えると、
        // 背面復帰が冊子の停止まで解く）。
        assertTrue(
            "冊子へ入るときに ReadingPauseReason.Booklet で止めていません",
            "pauseReadingTrace(ReadingPauseReason.Booklet)" in route
        )
        assertTrue(
            "冊子から出るときに ReadingPauseReason.Booklet で戻していません",
            "resumeReadingTrace(ReadingPauseReason.Booklet)" in route
        )
    }

    @Test
    fun `冊子ルートは読込中の要求を取り消す`() {
        assertTrue(
            "冊子へ戻ったときに「これを読む」の要求を取り消していません",
            "cancelBookletRead()" in bookletRoute()
        )
    }

    /**
     * **`navigateToTab` を使わない。** `popUpTo(startDestination)` が冊子ルートごと畳み、
     * 「戻れば同じ10枚」が成立しなくなる（→ booklet_mode 判断8）。
     */
    @Test
    fun `これを読むはタブ遷移ではなくルートを積む`() {
        val route = bookletRoute()

        assertTrue("これを読むが note ルートを積んでいません", """navigate("note")""" in route)
        assertFalse("冊子から navigateToTab を使うと冊子ルートが畳まれます", "navigateToTab" in route)
    }

    /**
     * **先頭から開く境界を通っているか。** 何をするかは `openFromBooklet` 側の
     * 描画テストが見る（実際の `LazyListState` の位置で確かめている）。
     */
    @Test
    fun `これを読むは先頭から開く境界を通る`() {
        assertTrue(
            "冊子から渡すときに openFromBooklet を通っていません",
            "openFromBooklet(" in bookletRoute()
        )
    }

    /**
     * `composable("booklet")` の中身。
     *
     * **次の `composable(` までを範囲とする。** 波かっこを数えるより壊れにくく、
     * ルートが並んで定義されているこのファイルでは同じ範囲になる。
     *
     * **コメントは落としてから見る。** 落とさないと「navigateToTab を使わない」と
     * 書いた注意書き自身が禁止語に当たり、**説明を書くほど検査が壊れる**。
     */
    private fun bookletRoute(): String {
        val source = mainActivity().readText()
        val start = source.indexOf("""composable("booklet")""")
        check(start >= 0) { "MainActivity に booklet ルートがありません" }
        val end = source.indexOf("composable(", start + 1)
        check(end > start) { "booklet ルートの終わりを特定できません" }
        return LINE_COMMENT.replace(source.substring(start, end), " ")
    }

    private companion object {
        val LINE_COMMENT = Regex("""//[^\n]*""")
    }

    private fun mainActivity(): File =
        repositoryRoot().resolve("app/src/main/java/com/example/newproject/MainActivity.kt").also {
            assertTrue("MainActivity.kt がありません", it.isFile)
        }

    private fun repositoryRoot(): File {
        val workingDirectory = File(
            requireNotNull(System.getProperty("user.dir")) { "user.dir が設定されていません" }
        )
        return sequenceOf(workingDirectory, workingDirectory.parentFile)
            .filterNotNull()
            .firstOrNull { it.resolve("docs/dev").isDirectory }
            ?: error("リポジトリルートが見つかりません: $workingDirectory")
    }
}
