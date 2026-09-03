package com.example.newproject

import androidx.compose.foundation.pager.PagerState
import com.example.newproject.ui.screen.alignPager
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 引き直したときに**ページャが新しい束の先頭へ戻る**ことを固定する。
 *
 * ## なぜ要るか
 *
 * 束の世代で積み直りを再生できるようにした後も、**ページャの位置は旧いままだった。**
 * `rememberPagerState` は `rememberSaveable` で、`initialPage` は作られたときにしか使われない。
 * 位置合わせの効果が `Unit` を鍵にしていたので、**画面が `Loading` を挟まなかった引き直し**
 * （ノート一覧がキャッシュから同期で返る通常経路）では一度も走り直さず、
 * **束は新しいのに「もうN枚引く」の終端が出たまま**になった（2026-09-03 のレビュー `P2-1`）。
 *
 * ## 何をどこで見るか
 *
 * - **位置が実際に動くこと**は、ここで**本物の `PagerState`** を使って見る。
 *   束の `page` が 0 であることだけを見ても、画面が先頭を出す証拠にならない。
 * - **いつ走り直すか**（＝束の世代を鍵にしていること）は走査で見る。
 *   走査は境界までで、結果は上の1件が受け持つ（→ docs/dev/lessons/L55.md）。
 * - 世代そのものが引き直しで進むことは `BookletRestackTest` が見る。
 */
class BookletPagerAlignmentTest {

    /** 10枚束の終端（「もう10枚引く」）から引き直したときに戻ること。 */
    @Test
    fun `終端から引き直すとページャは先頭へ戻る`() {
        val pager = pagerAt(page = 10, sheets = 10)

        alignPager(pager, target = 0)

        assertEquals("新しい束が届いても終端のページが残っています。", 0, pager.currentPage)
    }

    /**
     * **3枚しかないVaultでも同じ。** 枚数が減る引き直し（10枚 → 3枚）を含め、
     * 終端の位置は束ごとに違うので、枚数を1つだけ試しても足りない。
     */
    @Test
    fun `枚数の違う束でも先頭へ戻る`() {
        val pager = pagerAt(page = 3, sheets = 3)

        alignPager(pager, target = 0)

        assertEquals(0, pager.currentPage)
    }

    /** 同じ束の中では動かさない。**ページ送りと扉の読込では世代が変わらない。** */
    @Test
    fun `合わせ先が今の位置なら動かさない`() {
        val pager = pagerAt(page = 2, sheets = 10)

        alignPager(pager, target = 2)

        assertEquals(2, pager.currentPage)
    }

    /** ノートから戻ったときは、束が覚えている位置へ戻す（1枚目ではない）。 */
    @Test
    fun `覚えている位置へも合わせられる`() {
        val pager = pagerAt(page = 0, sheets = 10)

        alignPager(pager, target = 4)

        assertEquals(4, pager.currentPage)
    }

    /**
     * **位置合わせの契機は束の世代である。**
     *
     * `Unit` に戻すと、`Loading` を挟まなかった引き直しで走り直さず、旧い位置が残る。
     * ここで見るのは鍵だけで、**何が起きるかは上の4件が結果で見る**（→ L55）。
     */
    @Test
    fun `位置合わせは束の世代を鍵にする`() {
        val screen = File("src/main/java/com/example/newproject/ui/screen/BookletScreen.kt").readText()

        assertTrue(
            "位置合わせが `LaunchedEffect(drawId)` の中にありません。`Unit` を鍵にすると、" +
                "キャッシュ経由の引き直しでページャが旧い束の終端に残ります。",
            // **定義と呼び出しを引数の書き方で見分ける**（定義は `alignPager(pagerState: PagerState`）。
            // 見分けないと、先に現れる定義の手前を見てしまう。
            screen.substringBefore("alignPager(pagerState, ").trimEnd().endsWith("LaunchedEffect(drawId) {")
        )
    }

    /** [sheets] 枚の束（終端ページを含めて `sheets + 1` ページ）の、[page] にいるページャ。 */
    private fun pagerAt(page: Int, sheets: Int): PagerState =
        PagerState(currentPage = page, currentPageOffsetFraction = 0f, pageCount = { sheets + 1 })
}
