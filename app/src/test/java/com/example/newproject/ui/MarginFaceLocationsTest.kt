package com.example.newproject.ui

import com.example.newproject.domain.locateMemos
import com.example.newproject.domain.markdown.buildNoteSectionModel
import com.example.newproject.model.MarginMemo
import com.example.newproject.model.MemoPassage
import com.example.newproject.ui.screen.LocatedMemos
import com.example.newproject.ui.screen.currentLocations
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * 照らし直しの結果は Main の外で遅れて届く。**作ったときのメモと解析結果が今のものと違えば使わない**（→ features/margin_quote.md §5.5）。
 * ノートの切替・本文の解析し直し・メモの保存や削除の後に、前の結果で並べないため。
 */
class MarginFaceLocationsTest {

    private val content = "## 節\n\n意志の力に頼ると"
    private val memos = listOf(MarginMemo("メモ", 1L, "節", MemoPassage.Seen("意志の力に頼ると")))

    private fun locatedFor(memos: List<MarginMemo>, content: String = this.content): LocatedMemos {
        val model = buildNoteSectionModel(content)
        return LocatedMemos(memos, model, locateMemos(memos, model.passages, model.headingIndex))
    }

    @Test
    fun `作ったときと同じメモと解析結果なら使う`() {
        val located = locatedFor(memos)

        assertSame(located.locations, currentLocations(located, memos, located.model))
    }

    /** メモは中身で比べる — 保存の状態だけが替わって一覧が作り直されても、中身が同じなら同じ結果を使う。 */
    @Test
    fun `中身が同じなら作り直したメモの一覧でも使う`() {
        val located = locatedFor(memos)

        assertSame(located.locations, currentLocations(located, memos.toList(), located.model))
    }

    /** 解析結果は同じものかで比べる — 同じ本文を解析し直しても別の結果として扱い、作り直しを待つ。 */
    @Test
    fun `解析し直した後は前の結果を使わない`() {
        val located = locatedFor(memos)
        val reparsed = buildNoteSectionModel(content)

        assertNull(currentLocations(located, memos, reparsed))
    }

    @Test
    fun `メモが替わった後は前の結果を使わない`() {
        val located = locatedFor(memos)
        val added = memos + MarginMemo("足したメモ", 2L, "節")

        assertNull(currentLocations(located, added, located.model))
    }

    @Test
    fun `結果がまだ無いか、メモか解析結果が無ければ使わない`() {
        val located = locatedFor(memos)

        assertNull(currentLocations(null, memos, located.model))
        assertNull(currentLocations(located, null, located.model))
        assertNull(currentLocations(located, memos, null))
    }
}
