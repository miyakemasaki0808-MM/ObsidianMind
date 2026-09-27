package com.example.newproject.domain

import com.example.newproject.ai.PromptBuilder
import com.example.newproject.model.Crystal
import com.example.newproject.model.CrystalLimits
import com.example.newproject.model.CrystalMaterial
import com.example.newproject.model.CrystalMaterialLog
import com.example.newproject.model.CrystalSource
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrystalCandidatesTest {

    // ── 断片 ────────────────────────────────────────────────────────────────

    @Test
    fun `断片は改行と空白を均し、上限以内ならそのまま使う`() {
        assertEquals("一文目。 二文目。", crystalFragmentOf("  一文目。\n\n二文目。  "))
    }

    @Test
    fun `断片が上限を超えたら文の区切りまで手前へ寄せる`() {
        val summary = "あ".repeat(200) + "。" + "い".repeat(100) + "。"
        val fragment = crystalFragmentOf(summary)
        assertEquals("あ".repeat(200) + "。", fragment)
    }

    @Test
    fun `区切りの無い長文は切って三点リーダを付け、上限に収める`() {
        val fragment = crystalFragmentOf("あ".repeat(500))
        assertEquals(CrystalLimits.FRAGMENT_CHARACTERS, fragment.length)
        assertTrue(fragment.endsWith("…"))
    }

    @Test
    fun `サロゲートペアの途中では切らない`() {
        // 238字の後ろに絵文字（2単位）を置くと、239単位目がペアの上位になる。
        val fragment = crystalFragmentOf("あ".repeat(238) + "😀".repeat(20))
        val body = fragment.removeSuffix("…")
        assertFalse(Character.isHighSurrogate(body.last()))
        assertTrue(fragment.length <= CrystalLimits.FRAGMENT_CHARACTERS)
    }

    // ── 控えと候補 ──────────────────────────────────────────────────────────

    @Test
    fun `同じノートは1件にまとめ、最後に見た時刻だけを進める`() {
        var log = CrystalMaterialLog.EMPTY
        log = recordCrystalMaterial(log, "a.md", "A", "要約A。", now = 1)
        log = recordCrystalMaterial(log, "a.md", "A", "要約A。", now = 2)
        assertEquals(1, log.entries.size)
        assertEquals(2L, log.entries.single().lastSeenAt)
    }

    @Test
    fun `控えは上限を超えたら古いものから押し出す`() {
        var log = CrystalMaterialLog.EMPTY
        repeat(CrystalLimits.MATERIAL_ENTRIES + 3) { index ->
            log = recordCrystalMaterial(log, "n$index.md", "N$index", "要約$index。", now = index.toLong())
        }
        assertEquals(CrystalLimits.MATERIAL_ENTRIES, log.entries.size)
        assertFalse(log.entries.any { it.vaultRelativePath == "n0.md" })
    }

    @Test
    fun `候補は今のノートを先頭に、最近見た順に6件まで`() {
        var log = CrystalMaterialLog.EMPTY
        (1..9).forEach { index ->
            log = recordCrystalMaterial(log, "n$index.md", "N$index", "要約$index。", now = index.toLong())
        }
        val candidates = selectCrystalCandidates(log, "n3.md")
        assertEquals(listOf("n3.md", "n9.md", "n8.md", "n7.md", "n6.md", "n5.md"), candidates.map { it.vaultRelativePath })
    }

    @Test
    fun `今のノートが控えに無ければ候補は空`() {
        val log = recordCrystalMaterial(CrystalMaterialLog.EMPTY, "a.md", "A", "要約。", now = 1)
        assertTrue(selectCrystalCandidates(log, "b.md").isEmpty())
    }

    // ── 試す条件（判断5）────────────────────────────────────────────────────

    @Test
    fun `異なる3件で初回1回`() {
        val reader = Reader()
        assertFalse(reader.open("a.md", day = 1))
        assertFalse(reader.open("b.md", day = 1))
        assertTrue(reader.open("c.md", day = 1))
    }

    @Test
    fun `同じ内容の3件を翌日に開き直しても0回`() {
        val reader = Reader()
        listOf("a.md", "b.md", "c.md").forEach { reader.open(it, day = 1) }
        assertEquals(1, reader.attempts)
        listOf("a.md", "b.md", "c.md").forEach { reader.open(it, day = 2) }
        assertEquals(1, reader.attempts)
    }

    @Test
    fun `同じ1件を3回開いても1件と数える`() {
        val reader = Reader()
        repeat(3) { reader.open("a.md", day = it + 1) }
        assertEquals(0, reader.attempts)
    }

    @Test
    fun `まだ渡していない材料が2件なら0回、3件で暦日も満たせば1回`() {
        val reader = Reader()
        listOf("a.md", "b.md", "c.md").forEach { reader.open(it, day = 1) }
        assertEquals(1, reader.attempts)
        reader.open("d.md", day = 2)
        reader.open("e.md", day = 2)
        assertEquals(1, reader.attempts)
        assertTrue(reader.open("f.md", day = 2))
        assertEquals(2, reader.attempts)
    }

    @Test
    fun `同じ日に3件そろっても2回目は試さない`() {
        val reader = Reader()
        listOf("a.md", "b.md", "c.md", "d.md", "e.md", "f.md").forEach { reader.open(it, day = 1) }
        assertEquals(1, reader.attempts)
    }

    @Test
    fun `断片が変わったノートは新しい材料として1件に数える`() {
        val reader = Reader()
        listOf("a.md", "b.md", "c.md").forEach { reader.open(it, day = 1) }
        reader.open("d.md", day = 2)
        reader.open("e.md", day = 2)
        assertTrue(reader.open("a.md", day = 2, fragment = "書き直したAの要約。"))
    }

    /**
     * **判定を渡す候補に結び付ける**（設計修正レビューの反例）。控えには未提示の D・E・F が残るが、
     * 3日目・4日目に渡す候補は2日目と同じ L〜G なので試さない。
     */
    @Test
    fun `候補の外に残った未提示の材料では同じ6件を渡さない`() {
        val reader = Reader()
        listOf("a", "b", "c").forEach { reader.open("$it.md", day = 1) }
        assertEquals(1, reader.attempts)
        ('d'..'l').forEach { reader.open("$it.md", day = 1) }
        assertEquals(1, reader.attempts)

        assertTrue(reader.open("l.md", day = 2))
        assertEquals(listOf("l.md", "k.md", "j.md", "i.md", "h.md", "g.md"), reader.lastSent)

        assertFalse(reader.open("l.md", day = 3))
        assertFalse(reader.open("l.md", day = 4))
        assertEquals(2, reader.attempts)

        // 候補の外にあった材料も、読み直して直近に上がれば数える。
        assertFalse(reader.open("d.md", day = 5))
        assertFalse(reader.open("e.md", day = 5))
        assertTrue(reader.open("f.md", day = 5))
        assertEquals(listOf("f.md", "e.md", "d.md", "l.md", "k.md", "j.md"), reader.lastSent)
    }

    /** 予算で候補を落としたら、**落とした後の集合**で判定する。 */
    @Test
    fun `予算で候補を落とした後の集合で判定する`() {
        var log = CrystalMaterialLog.EMPTY
        listOf("a.md", "b.md", "c.md").forEachIndexed { index, path ->
            log = recordCrystalMaterial(log, path, path, "要約${path}。", now = index.toLong())
        }
        val candidates = selectCrystalCandidates(log, "c.md")
        assertEquals(3, candidates.size)

        // 1行ぶんの予算にすると、今のノートだけが残る。
        val trimmed = PromptBuilder.buildCrystalPrompt(candidates, candidateBudget = 1)
        assertEquals(listOf("c.md"), trimmed.sentMaterials.map { it.vaultRelativePath })
        assertFalse(shouldAttemptCrystal(trimmed.sentMaterials, log, now = 10, zone = ZoneOffset.UTC))

        val full = PromptBuilder.buildCrystalPrompt(candidates)
        assertTrue(shouldAttemptCrystal(full.sentMaterials, log, now = 10, zone = ZoneOffset.UTC))
    }

    @Test
    fun `渡した材料の記録は直近の件数で溢れる`() {
        var log = CrystalMaterialLog.EMPTY
        repeat(20) { round ->
            val sent = (0 until CrystalLimits.CANDIDATES).map { material("r$round-$it.md") }
            log = markCrystalAttempted(log, sent, now = round.toLong())
        }
        assertEquals(CrystalLimits.PRESENTED_HISTORY, log.presented.size)
        assertEquals(19L, log.lastAttemptAt)
    }

    @Test
    fun `今日すでに試したかは暦日で見る`() {
        val zone = ZoneOffset.ofHours(9)
        fun at(text: String) = java.time.OffsetDateTime.parse(text).toInstant().toEpochMilli()
        val evening = at("2026-09-27T21:00:00+09:00")
        val lateNight = at("2026-09-27T23:30:00+09:00")
        val afterMidnight = at("2026-09-28T00:30:00+09:00")
        assertTrue(crystalAttemptedToday(evening, lateNight, zone))
        assertFalse(crystalAttemptedToday(lateNight, afterMidnight, zone))
        assertFalse(crystalAttemptedToday(null, lateNight, zone))
    }

    // ── 表示 ────────────────────────────────────────────────────────────────

    @Test
    fun `今のノートを根拠に含む結晶だけを新しい順に3件まで`() {
        val crystals = (1..5).map { index -> crystal(createdAt = index.toLong(), "x.md", "y$index.md") } +
            crystal(createdAt = 99, "other.md", "z.md")
        val shown = crystalsForNote(crystals, "x.md")
        assertEquals(listOf(5L, 4L, 3L), shown.map { it.createdAt })
        assertTrue(crystalsForNote(crystals, null).isEmpty())
    }

    @Test
    fun `同じファイルは重ねても1件`() {
        val a = crystal(createdAt = 1, "x.md", "y.md")
        val merged = mergeCrystals(listOf(a), listOf(a, crystal(createdAt = 2, "x.md", "z.md")))
        assertEquals(listOf(2L, 1L), merged.map { it.createdAt })
    }

    /**
     * Controller と同じ順で純関数を通す読み手。控える → 暦日 → 候補 → 渡す候補 → 判定 → 記録。
     * 生成は常に返ったものとして数える（空振りか成功かで規則は変わらない）。
     */
    private class Reader {
        var log = CrystalMaterialLog.EMPTY
        var attempts = 0
        var lastSent: List<String> = emptyList()
        private var clock = 0L

        fun open(path: String, day: Int, fragment: String = "要約$path。"): Boolean {
            val now = day * DAY + (++clock) * 1_000
            log = recordCrystalMaterial(log, path, path, fragment, now)
            if (crystalAttemptedToday(log.lastAttemptAt, now, ZoneOffset.UTC)) return false
            val sent = PromptBuilder.buildCrystalPrompt(selectCrystalCandidates(log, path)).sentMaterials
            if (!shouldAttemptCrystal(sent, log, now, ZoneOffset.UTC)) return false
            log = markCrystalAttempted(log, sent, now)
            attempts++
            lastSent = sent.map { it.vaultRelativePath }
            return true
        }
    }

    private fun material(path: String) = CrystalMaterial(path, path, "要約$path。", lastSeenAt = 0)

    private fun crystal(createdAt: Long, vararg paths: String) = Crystal(
        createdAt = createdAt,
        sentence = "共通する筋の一文です。",
        sources = paths.map { CrystalSource(it, it, "断片") },
        fileName = "$createdAt-${paths.joinToString("_")}.json"
    )

    private companion object {
        const val HOUR = 3_600_000L
        const val DAY = 24 * HOUR
    }
}
