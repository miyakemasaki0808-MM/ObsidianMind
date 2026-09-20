package com.example.newproject

import com.example.newproject.domain.adoptImportedTrace
import com.example.newproject.domain.mergeReadingTraces
import com.example.newproject.domain.ReadingTraceMergeResult
import com.example.newproject.model.ReadingTrace
import com.example.newproject.model.ReadingTraceLimits
import com.example.newproject.model.ReadingVisit
import com.example.newproject.model.MarginMemo
import com.example.newproject.model.ReunionKind
import com.example.newproject.model.validateReadingTrace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 読み戻しの突き合わせ規則。**この機能で唯一「間違えると黙って何かを失う」場所。**
 *
 * 規則の正本は features/reading_trace_backup.md §5。ここが見ているのは
 * 「再生成できないものが残るか」で、件数や体裁は見ない。
 */
class ReadingTraceMergeTest {

    // ── 余白メモ（再生成できないもの）────────────────────────────────────
    //
    // **配列なので「どちらを選ぶか」が要らない。** 1ノート1組だった旧ひとことは
    // 両方に返事があれば必ず片方が消えていた。代わりに、合流が保持上限を超えるという
    // 新しい境界ができる（→ features/reflect_margin_memo.md 判断5）。

    @Test
    fun `両方のメモが残る`() {
        val local = trace().copy(memos = listOf(memo("端末側", 100L)))
        val imported = trace().copy(memos = listOf(memo("退避側", 200L)))

        assertEquals(
            listOf("端末側", "退避側"),
            merged(local, imported).memos.map { it.text }
        )
    }

    /** 同じ断片は畳む。**見出しは畳む鍵に入れない**（同じ瞬間の同じ文は同一のもの）。 */
    @Test
    fun `同じ日時と本文のメモは畳まれる`() {
        val local = trace().copy(memos = listOf(memo("同じ文", 100L, "導入")))
        val imported = trace().copy(memos = listOf(memo("同じ文", 100L, null)))

        assertEquals(1, merged(local, imported).memos.size)
    }

    @Test
    fun `合流したメモは日時の順に並ぶ`() {
        val local = trace().copy(memos = listOf(memo("あと", 300L), memo("さき", 100L)))
        val imported = trace().copy(memos = listOf(memo("あいだ", 200L)))

        assertEquals(
            listOf("さき", "あいだ", "あと"),
            merged(local, imported).memos.map { it.text }
        )
    }

    /**
     * **上限を超える合流は切らずに保留する。**
     * 切れば「古いものから捨てない」に反し、全部持てば検証と容量に反する。
     */
    @Test
    fun `上限を超える合流は保留になる`() {
        val local = trace().copy(
            memos = (1..ReadingTraceLimits.MAX_MEMOS).map { memo("端末側$it", it * 10L) }
        )
        val imported = trace().copy(memos = listOf(memo("あふれる1件", 99_000L)))

        assertEquals(
            ReadingTraceMergeResult.HeldOverCapacity,
            mergeReadingTraces(local, imported)
        )
    }

    /** ちょうど上限なら合流できる。境界を1つずらすと片側が静かに落ちる。 */
    @Test
    fun `ちょうど上限までは合流できる`() {
        val half = ReadingTraceLimits.MAX_MEMOS / 2
        val local = trace().copy(memos = (1..half).map { memo("端末側$it", it * 10L) })
        val imported = trace().copy(
            memos = (1..(ReadingTraceLimits.MAX_MEMOS - half)).map { memo("退避側$it", 1_000L + it * 10L) }
        )

        assertEquals(ReadingTraceLimits.MAX_MEMOS, merged(local, imported).memos.size)
    }

    /** 重複を除いた結果が上限内なら、見かけの合計が超えていても合流できる。 */
    @Test
    fun `重複を除けば上限内なら合流できる`() {
        val shared = (1..ReadingTraceLimits.MAX_MEMOS).map { memo("共有$it", it * 10L) }
        val local = trace().copy(memos = shared)
        val imported = trace().copy(memos = shared)

        assertEquals(ReadingTraceLimits.MAX_MEMOS, merged(local, imported).memos.size)
    }

    /** **表示名は端末側を保つ。** メモは両方残るので「採用した側」が存在しない。 */
    @Test
    fun `表示名は端末側のまま保たれる`() {
        val local = trace(title = "端末側の名前").copy(memos = listOf(memo("端末側", 100L)))
        val imported = trace(title = "退避側の名前").copy(memos = listOf(memo("退避側", 200L)))

        assertEquals("端末側の名前", merged(local, imported).noteTitle)
    }

    // ── 訪問と累計 ──────────────────────────────────────────────────────

    @Test
    fun `訪問は時刻で重複排除して結合する`() {
        val local = trace().copy(visits = listOf(visit(100L), visit(200L)), totalVisitCount = 2)
        val imported = trace().copy(visits = listOf(visit(200L), visit(300L)), totalVisitCount = 2)

        val result = merged(local, imported)
        assertEquals(listOf(100L, 200L, 300L), result.visits.map { it.atEpochMillis })
    }

    @Test
    fun `結合しても保持上限を超えない`() {
        val local = trace().copy(
            visits = (1..25).map { visit(it * 10L) },
            totalVisitCount = 25
        )
        val imported = trace().copy(
            visits = (26..50).map { visit(it * 10L) },
            totalVisitCount = 25
        )

        val result = merged(local, imported)
        assertEquals(ReadingTraceLimits.MAX_VISITS, result.visits.size)
        // 直近を残す。古い側から捨てる。
        assertEquals(500L, result.visits.last().atEpochMillis)
    }

    @Test
    fun `累計は大きい方を採る`() {
        val local = trace().copy(totalVisitCount = 40)
        val imported = trace().copy(totalVisitCount = 7)

        assertEquals(40, merged(local, imported).totalVisitCount)
    }

    // 累計が保持件数を下回ると検証で弾かれる。両方の累計が小さくても結合で件数が増えうる。
    @Test
    fun `累計は結合後の保持件数を下回らない`() {
        val local = trace().copy(visits = listOf(visit(100L)), totalVisitCount = 1)
        val imported = trace().copy(visits = listOf(visit(200L)), totalVisitCount = 1)

        val result = merged(local, imported)
        assertEquals(2, result.totalVisitCount)
        validateReadingTrace(result)
    }

    // ── AI要約（作り直せるもの）─────────────────────────────────────────

    @Test
    fun `AI要約は採用後の累計と噛み合わなければ3つまとめて捨てる`() {
        val local = trace().copy(
            totalVisitCount = 5,
            aiSummary = "端末側の要約",
            aiSummaryVisitCount = 5,
            aiSummaryKind = ReunionKind.Overview
        )
        val imported = trace().copy(
            totalVisitCount = 9,
            aiSummary = "退避側の要約",
            aiSummaryVisitCount = 3,
            aiSummaryKind = ReunionKind.Overview
        )

        val result = merged(local, imported)
        assertEquals(9, result.totalVisitCount)
        assertNull(result.aiSummary)
        assertNull(result.aiSummaryVisitCount)
        // **種別だけ残さない。** 残すと内容の無い前置きが出る。
        assertNull(result.aiSummaryKind)
    }

    @Test
    fun `噛み合う側の要約は残る`() {
        val local = trace().copy(totalVisitCount = 3)
        val imported = trace().copy(
            totalVisitCount = 9,
            aiSummary = "退避側の要約",
            aiSummaryVisitCount = 9,
            aiSummaryKind = ReunionKind.Overview
        )

        val result = merged(local, imported)
        assertEquals("退避側の要約", result.aiSummary)
        assertEquals(ReunionKind.Overview, result.aiSummaryKind)
    }

    // ── 印（作り直せないもの）───────────────────────────────────────────

    @Test
    fun `印は持っている側が残る`() {
        val local = trace()
        val imported = trace().copy(
            markedAtEpochMillis = 500L,
            markedSummary = "まだ考えたい内容",
            markedKind = ReunionKind.Overview
        )

        val result = merged(local, imported)
        assertEquals("まだ考えたい内容", result.markedSummary)
        assertEquals(500L, result.markedAtEpochMillis)
    }

    @Test
    fun `両方に印があれば新しい方を採る`() {
        val local = trace().copy(
            markedAtEpochMillis = 100L,
            markedSummary = "古い印",
            markedKind = ReunionKind.Overview
        )
        val imported = trace().copy(
            markedAtEpochMillis = 900L,
            markedSummary = "新しい印",
            markedKind = ReunionKind.Overview
        )

        assertEquals("新しい印", merged(local, imported).markedSummary)
    }

    // ── 端末に紐づく値 ──────────────────────────────────────────────────

    @Test
    fun `documentId は端末側のまま保たれる`() {
        val local = trace().copy(documentId = "content://this-device/doc")
        val imported = trace().copy(documentId = "content://other-device/doc")

        assertEquals(
            "content://this-device/doc",
            merged(local, imported).documentId
        )
    }

    // 端末側に無い痕跡は退避側の値をそのまま受け入れるが、**引き当てキャッシュは落とす。**
    // 別端末はもちろん、同じ端末の再インストール後でも無効な値である。
    @Test
    fun `新規に受け入れる痕跡は documentId を持たない`() {
        val imported = trace().copy(documentId = "content://other-device/doc")

        assertNull(adoptImportedTrace(imported).documentId)
    }

    @Test
    fun `マージ結果は検証を通る`() {
        val local = trace().copy(
            visits = (1..30).map { visit(it * 10L) },
            totalVisitCount = 30,
            aiSummary = "要約",
            aiSummaryVisitCount = 30,
            aiSummaryKind = ReunionKind.Overview,
            memos = listOf(memo("端末側のメモ", 100L)),
            documentId = "content://this-device/doc"
        )
        val imported = trace().copy(
            visits = (31..60).map { visit(it * 10L) },
            totalVisitCount = 45,
            markedAtEpochMillis = 900L,
            markedSummary = "印",
            markedKind = ReunionKind.Overview
        )

        validateReadingTrace(merged(local, imported))
    }
}

private fun trace(title: String = "habit") = ReadingTrace(
    vaultRelativePath = "ideas/habit.md",
    noteTitle = title,
    documentId = null,
    visits = listOf(ReadingVisit(1_000L, null, 50)),
    totalVisitCount = 1
)

/** 合流できる前提のケースで、結果から痕跡を取り出す。 */
private fun merged(local: ReadingTrace, imported: ReadingTrace): ReadingTrace =
    (mergeReadingTraces(local, imported) as ReadingTraceMergeResult.Merged).trace

private fun memo(text: String, at: Long, section: String? = null) =
    MarginMemo(text = text, writtenAtEpochMillis = at, sectionTitle = section)

private fun visit(at: Long) = ReadingVisit(at, null, 50)

