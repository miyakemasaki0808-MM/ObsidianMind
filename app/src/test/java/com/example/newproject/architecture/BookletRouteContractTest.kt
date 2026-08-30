package com.example.newproject.architecture

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 冊子ルートの**配線**を固定する。
 *
 * ## なぜソース走査なのか
 *
 * 束の作り方は `BookletControllerTest`、扉の描画は `BookletScreenTest`、
 * 読書時間の積み方は `ReadingTraceControllerTest` が押さえている。
 * **しかし「冊子ルートがそれらを正しい順で呼ぶ」ことは、どれからも観測できない。**
 * `NoteViewModel` は `AndroidViewModel` なので素のJVMでは組み立てられず、
 * この配線だけが検査の外に残る。
 *
 * ここで見る3つは、いずれも**外したときに静かに壊れる**ものである。
 *
 * - 読書時間の停止 — ルート遷移では Activity が `onStop` しないので、
 *   止めないと冊子を眺めた時間が直前のノートの読書時間になる
 * - 読込中の要求の取り消し — 取り消さないと、冊子が前面のまま痕跡・履歴・AIが始まる
 * - スクロール位置の先頭戻し — `noteListState` は Activity 生存で共有され、
 *   ノート切替でリセットされない
 *
 * 正本は [features/booklet_mode.md](../../../../../../../../docs/dev/features/booklet_mode.md)。
 */
class BookletRouteContractTest {

    @Test
    fun `冊子ルートは読書時間を止めて戻す`() {
        val route = bookletRoute()

        assertTrue("冊子へ入るときに読書時間を止めていません", "pauseReadingTrace()" in route)
        assertTrue("冊子から出るときに読書時間を戻していません", "resumeReadingTrace()" in route)
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

    @Test
    fun `これを読むは本文を先頭から開く`() {
        assertTrue(
            "冊子から渡すときにスクロール位置を先頭へ戻していません",
            "requestScrollToItem(0)" in bookletRoute()
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
