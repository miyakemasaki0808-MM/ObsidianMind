package com.example.newproject.ui

import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import com.example.newproject.model.state.DistillCandidateItem
import com.example.newproject.ui.screen.highlightedParent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 調整シートが**確定範囲だけを太字＋下線で示す**ことを固定する。
 *
 * **テスト名とコメントで描画を保証した扱いにしない。** 親文が表示されていることだけを見ると、
 * 強調を丸ごと外す変異が素通りする（2026-08-29 のレビュー P2-3）。
 * ここは値として観測し、実際に描かれることは `DistillRangeAdjustUiTest` が見る。
 */
class DistillRangeHighlightTest {

    private fun emphasizedRanges(item: DistillCandidateItem) =
        highlightedParent(item).spanStyles
            .filter {
                it.item.fontWeight == FontWeight.Bold &&
                    it.item.textDecoration == TextDecoration.Underline
            }
            .map { it.start to it.end }

    @Test
    fun `確定範囲だけに太字と下線が掛かる`() {
        val item = itemOf(parent = "前半の句、後半の句。", start = 0, end = 5)

        assertEquals("前半の句、後半の句。", highlightedParent(item).text)
        assertEquals(listOf(0 to 5), emphasizedRanges(item))
    }

    @Test
    fun `範囲の外には強調が掛からない`() {
        val item = itemOf(parent = "前半の句、後半の句。", start = 5, end = 10)
        val emphasized = emphasizedRanges(item).single()

        assertEquals(5 to 10, emphasized)
        // 前後の文字が同じ組を貰っていないこと。
        assertTrue(highlightedParent(item).spanStyles.none { it.start < 5 || it.end > 10 })
    }

    @Test
    fun `親文の外へ出る指定は親文の内側へ丸める`() {
        val item = itemOf(parent = "短い親文。", start = -3, end = 99)

        assertEquals(listOf(0 to 5), emphasizedRanges(item))
    }

    private fun itemOf(parent: String, start: Int, end: Int) = DistillCandidateItem(
        id = "S001",
        text = parent.substring(start.coerceIn(0, parent.length), end.coerceIn(0, parent.length)),
        heading = null,
        positionLabel = "1 / 2",
        context = parent,
        parentText = parent,
        boldStartInParent = start,
        boldEndInParent = end
    )
}
