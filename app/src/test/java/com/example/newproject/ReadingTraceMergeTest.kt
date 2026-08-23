package com.example.newproject

import com.example.newproject.domain.adoptImportedTrace
import com.example.newproject.domain.mergeReadingTraces
import com.example.newproject.domain.replacesReply
import com.example.newproject.model.ReadingTrace
import com.example.newproject.model.ReadingTraceLimits
import com.example.newproject.model.ReadingVisit
import com.example.newproject.model.Reflection
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

    // ── 返事（再生成できないもの）────────────────────────────────────────

    @Test
    fun `両方に返事があるときは新しい返事が残る`() {
        val local = trace().copy(reflection = reflection("古い問い", 100L, "古い返事", 200L))
        val imported = trace().copy(reflection = reflection("新しい問い", 300L, "新しい返事", 400L))

        assertEquals("新しい返事", mergeReadingTraces(local, imported).reflection?.reply)
    }

    // **これが直感とずれる側。** 「新しい方が残る」と期待されるが、
    // ひとことは本文から作り直せるのに対し返事は二度と作れない。
    @Test
    fun `片方だけ返事があるなら古くてもそちらが残る`() {
        val local = trace().copy(reflection = reflection("古い問い", 100L, "消してはいけない返事", 150L))
        val imported = trace().copy(reflection = reflection("新しい問い", 9_000L))

        val merged = mergeReadingTraces(local, imported)
        assertEquals("消してはいけない返事", merged.reflection?.reply)
        assertEquals("古い問い", merged.reflection?.remark)
    }

    @Test
    fun `退避側にだけ返事があるならそちらを採る`() {
        val local = trace().copy(reflection = reflection("問い", 9_000L))
        val imported = trace().copy(reflection = reflection("問い", 100L, "退避側の返事", 150L))

        assertEquals("退避側の返事", mergeReadingTraces(local, imported).reflection?.reply)
    }

    // 返事がどちらにも無ければ、新しいひとことを採る（作り直せるので損失が無い）。
    @Test
    fun `返事が無い者どうしはひとことの新しい方を採る`() {
        val local = trace().copy(reflection = reflection("古い問い", 100L))
        val imported = trace().copy(reflection = reflection("新しい問い", 500L))

        assertEquals("新しい問い", mergeReadingTraces(local, imported).reflection?.remark)
    }

    // 同点は端末側。**いま使っている側を理由なく動かさない。**
    @Test
    fun `同じ日時なら端末側の対話を保つ`() {
        val local = trace().copy(reflection = reflection("端末側", 500L))
        val imported = trace().copy(reflection = reflection("退避側", 500L))

        assertEquals("端末側", mergeReadingTraces(local, imported).reflection?.remark)
    }

    @Test
    fun `表示名は採用した対話の側に揃う`() {
        val local = trace().copy(noteTitle = "端末側の名前", reflection = reflection("問い", 100L))
        val imported = trace().copy(
            noteTitle = "退避側の名前",
            reflection = reflection("問い", 100L, "返事", 200L)
        )

        assertEquals("退避側の名前", mergeReadingTraces(local, imported).noteTitle)
    }

    @Test
    fun `置き換わる返事の件数は中身が違うときだけ数える`() {
        val withReply = trace().copy(reflection = reflection("問い", 100L, "同じ返事", 200L))
        val other = trace().copy(reflection = reflection("問い", 300L, "違う返事", 400L))
        val withoutReply = trace().copy(reflection = reflection("問い", 100L))

        assertTrue(replacesReply(withReply, other))
        assertFalse("同じ文なら失うものは無い", replacesReply(withReply, withReply))
        assertFalse(replacesReply(withReply, withoutReply))
        assertFalse(replacesReply(withoutReply, withReply))
    }

    // ── 訪問と累計 ──────────────────────────────────────────────────────

    @Test
    fun `訪問は時刻で重複排除して結合する`() {
        val local = trace().copy(visits = listOf(visit(100L), visit(200L)), totalVisitCount = 2)
        val imported = trace().copy(visits = listOf(visit(200L), visit(300L)), totalVisitCount = 2)

        val merged = mergeReadingTraces(local, imported)
        assertEquals(listOf(100L, 200L, 300L), merged.visits.map { it.atEpochMillis })
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

        val merged = mergeReadingTraces(local, imported)
        assertEquals(ReadingTraceLimits.MAX_VISITS, merged.visits.size)
        // 直近を残す。古い側から捨てる。
        assertEquals(500L, merged.visits.last().atEpochMillis)
    }

    @Test
    fun `累計は大きい方を採る`() {
        val local = trace().copy(totalVisitCount = 40)
        val imported = trace().copy(totalVisitCount = 7)

        assertEquals(40, mergeReadingTraces(local, imported).totalVisitCount)
    }

    // 累計が保持件数を下回ると検証で弾かれる。両方の累計が小さくても結合で件数が増えうる。
    @Test
    fun `累計は結合後の保持件数を下回らない`() {
        val local = trace().copy(visits = listOf(visit(100L)), totalVisitCount = 1)
        val imported = trace().copy(visits = listOf(visit(200L)), totalVisitCount = 1)

        val merged = mergeReadingTraces(local, imported)
        assertEquals(2, merged.totalVisitCount)
        validateReadingTrace(merged)
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

        val merged = mergeReadingTraces(local, imported)
        assertEquals(9, merged.totalVisitCount)
        assertNull(merged.aiSummary)
        assertNull(merged.aiSummaryVisitCount)
        // **種別だけ残さない。** 残すと内容の無い前置きが出る。
        assertNull(merged.aiSummaryKind)
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

        val merged = mergeReadingTraces(local, imported)
        assertEquals("退避側の要約", merged.aiSummary)
        assertEquals(ReunionKind.Overview, merged.aiSummaryKind)
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

        val merged = mergeReadingTraces(local, imported)
        assertEquals("まだ考えたい内容", merged.markedSummary)
        assertEquals(500L, merged.markedAtEpochMillis)
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

        assertEquals("新しい印", mergeReadingTraces(local, imported).markedSummary)
    }

    // ── 端末に紐づく値 ──────────────────────────────────────────────────

    @Test
    fun `documentId は端末側のまま保たれる`() {
        val local = trace().copy(documentId = "content://this-device/doc")
        val imported = trace().copy(documentId = "content://other-device/doc")

        assertEquals(
            "content://this-device/doc",
            mergeReadingTraces(local, imported).documentId
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
            reflection = reflection("問い", 100L, "返事", 200L),
            documentId = "content://this-device/doc"
        )
        val imported = trace().copy(
            visits = (31..60).map { visit(it * 10L) },
            totalVisitCount = 45,
            markedAtEpochMillis = 900L,
            markedSummary = "印",
            markedKind = ReunionKind.Overview
        )

        validateReadingTrace(mergeReadingTraces(local, imported))
    }
}

private fun trace() = ReadingTrace(
    vaultRelativePath = "ideas/habit.md",
    noteTitle = "habit",
    documentId = null,
    visits = listOf(ReadingVisit(1_000L, null, 50)),
    totalVisitCount = 1
)

private fun visit(at: Long) = ReadingVisit(at, null, 50)

private fun reflection(
    remark: String,
    remarkedAt: Long,
    reply: String? = null,
    repliedAt: Long? = null
) = Reflection(
    remark = remark,
    remarkedAtEpochMillis = remarkedAt,
    reply = reply,
    repliedAtEpochMillis = repliedAt
)
