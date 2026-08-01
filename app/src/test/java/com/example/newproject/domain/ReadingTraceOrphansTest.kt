package com.example.newproject.domain

import com.example.newproject.model.OrphanAssessment
import com.example.newproject.model.OrphanBlockReason
import com.example.newproject.model.OrphanLimits
import com.example.newproject.model.OrphanTraceInfo
import com.example.newproject.model.OrphanWithholdReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 孤児判定の遮断器を固定する。
 *
 * **守っている一点は「不在は証明ではない」。** SAF の列挙はフォルダ単位で失敗するため、
 * 「ノートが見つからない」は削除と列挙失敗の両方を意味しうる。したがって
 * 遮断器もフォルダ単位で置いてある。ここが緩むと、同期途中のVaultで
 * 生きている痕跡がまとめて削除候補になる。
 */
class ReadingTraceOrphansTest {

    // --- 基本 -----------------------------------------------------------------

    @Test
    fun `a trace whose note no longer exists becomes a candidate`() {
        val result = assess(
            traces = mapOf("k-habit" to info("ideas/habit.md")),
            noteKeys = emptySet()
        )

        assertEquals(
            listOf("ideas/habit.md"),
            assessed(result).orphans.map { it.vaultRelativePath }
        )
    }

    @Test
    fun `a trace whose note still exists is not a candidate`() {
        val result = assess(
            traces = mapOf("k-habit" to info("ideas/habit.md")),
            noteKeys = setOf("k-habit")
        )

        assertTrue(assessed(result).orphans.isEmpty())
        assertTrue(assessed(result).withheld.isEmpty())
    }

    @Test
    fun `candidates carry what the screen needs to show`() {
        val result = assess(
            traces = mapOf(
                "k-habit" to OrphanTraceInfo("ideas/habit.md", "習慣について", 7, 1_700L)
            ),
            noteKeys = emptySet()
        )

        val candidate = assessed(result).orphans.single()
        assertEquals("k-habit", candidate.key)
        assertEquals("習慣について", candidate.noteTitle)
        assertEquals(7, candidate.totalVisitCount)
        assertEquals(1_700L, candidate.lastVisitAtEpochMillis)
    }

    // --- 遮断器: フォルダ単位の一括欠落 -----------------------------------------
    //
    // querySafChildren の失敗は「そのフォルダの子が全部消える」形で現れるため、
    // ここが遮断器の本体になる。

    @Test
    fun `two traces missing from the same folder are withheld, not offered`() {
        val result = assess(
            traces = mapOf(
                "k-1" to info("ideas/a.md"),
                "k-2" to info("ideas/b.md")
            ),
            noteKeys = emptySet()
        )

        assertTrue(assessed(result).orphans.isEmpty())
        val withheld = assessed(result).withheld.single()
        assertEquals("ideas", withheld.folderPath)
        assertEquals(2, withheld.count)
        assertEquals(OrphanWithholdReason.FOLDER_WIDE_ABSENCE, withheld.reason)
    }

    @Test
    fun `one missing note per folder is still offered across several folders`() {
        val result = assess(
            traces = mapOf(
                "k-1" to info("ideas/a.md"),
                "k-2" to info("journal/b.md"),
                "k-3" to info("root.md")
            ),
            noteKeys = emptySet()
        )

        // フォルダが違えば同時に消えても「フォルダ列挙の失敗」では説明できない。
        assertEquals(
            listOf("ideas/a.md", "journal/b.md", "root.md"),
            assessed(result).orphans.map { it.vaultRelativePath }
        )
    }

    @Test
    fun `root level notes are grouped together as one folder`() {
        val result = assess(
            traces = mapOf(
                "k-1" to info("a.md"),
                "k-2" to info("b.md")
            ),
            noteKeys = emptySet()
        )

        // ルート直下も1つのフォルダとして数える（ルート列挙の失敗が同じ形で現れる）。
        assertTrue(assessed(result).orphans.isEmpty())
        assertEquals(OrphanWithholdReason.FOLDER_WIDE_ABSENCE, assessed(result).withheld.single().reason)
    }

    // --- 遮断器: 不完全な走査 ---------------------------------------------------

    @Test
    fun `candidates under an unreadable folder are withheld`() {
        val result = assess(
            traces = mapOf("k-1" to info("ideas/a.md")),
            noteKeys = emptySet(),
            unreadableFolderPaths = setOf("ideas")
        )

        val withheld = assessed(result).withheld.single()
        assertEquals(OrphanWithholdReason.UNREADABLE_FOLDER, withheld.reason)
    }

    @Test
    fun `an unreadable ancestor also withholds nested candidates`() {
        val result = assess(
            traces = mapOf("k-1" to info("ideas/2026/a.md")),
            noteKeys = emptySet(),
            unreadableFolderPaths = setOf("ideas")
        )

        assertEquals(
            OrphanWithholdReason.UNREADABLE_FOLDER,
            assessed(result).withheld.single().reason
        )
    }

    @Test
    fun `a folder with a similar prefix is not treated as nested`() {
        val result = assess(
            traces = mapOf("k-1" to info("ideas2/a.md")),
            noteKeys = emptySet(),
            unreadableFolderPaths = setOf("ideas")
        )

        // "ideas2" は "ideas" の配下ではない。素の startsWith だとここで取り違える。
        assertEquals(listOf("ideas2/a.md"), assessed(result).orphans.map { it.vaultRelativePath })
    }

    @Test
    fun `an unreadable branch does not stop cleanup elsewhere`() {
        val result = assess(
            traces = mapOf(
                "k-1" to info("ideas/a.md"),
                "k-2" to info("journal/b.md")
            ),
            noteKeys = emptySet(),
            unreadableFolderPaths = setOf("ideas")
        )

        // 失敗を全体1フラグで持たずパス集合で持つ理由がこれ。
        // 1フォルダ読み損ねただけで掃除が全面停止しない。
        assertEquals(listOf("journal/b.md"), assessed(result).orphans.map { it.vaultRelativePath })
        assertEquals(
            OrphanWithholdReason.UNREADABLE_FOLDER,
            assessed(result).withheld.single().reason
        )
    }

    @Test
    fun `an unreadable vault root blocks the whole assessment`() {
        val result = assess(
            traces = mapOf("k-1" to info("ideas/a.md")),
            noteKeys = emptySet(),
            unreadableFolderPaths = setOf("")
        )

        // ルートが読めていなければ全ノートが不在に見える。空の Assessed で返すと
        // 「孤児は無かった」と読めるので、Blocked で区別する。
        assertEquals(
            OrphanBlockReason.VAULT_ROOT_UNREADABLE,
            (result as OrphanAssessment.Blocked).reason
        )
    }

    // --- 遮断器: 急増 -----------------------------------------------------------

    @Test
    fun `too many candidates blocks the assessment`() {
        val traces = (1..5).associate { "k-$it" to info("f$it/a.md") }

        val result = assess(
            traces = traces,
            noteKeys = emptySet(),
            limits = OrphanLimits(maxTotalCandidates = 4)
        )

        val blocked = result as OrphanAssessment.Blocked
        assertEquals(OrphanBlockReason.TOO_MANY_CANDIDATES, blocked.reason)
        assertEquals(5, blocked.candidateCount)
    }

    // 件数の判定は resolve より前。後ろに置くと、異常時ほど大量にファイルを読む。
    @Test
    fun `no trace file is read when the candidate count is over the limit`() {
        val traces = (1..5).associate { "k-$it" to info("f$it/a.md") }
        var reads = 0

        assessReadingTraceOrphans(
            traceKeys = traces.keys,
            noteKeys = emptySet(),
            unreadableFolderPaths = emptySet(),
            limits = OrphanLimits(maxTotalCandidates = 4)
        ) { key -> reads++; traces[key] }

        assertEquals(0, reads)
    }

    @Test
    fun `only candidates are read, never the whole set of traces`() {
        val traces = mapOf(
            "k-1" to info("ideas/a.md"),
            "k-2" to info("journal/b.md")
        )
        val read = mutableListOf<String>()

        assessReadingTraceOrphans(
            traceKeys = traces.keys,
            noteKeys = setOf("k-2"),
            unreadableFolderPaths = emptySet()
        ) { key -> read += key; traces[key] }

        // 現存ノートの痕跡は集合差で落ちるので、ファイルを開かない。
        assertEquals(listOf("k-1"), read)
    }

    // --- 読めなかった候補 --------------------------------------------------------

    @Test
    fun `a candidate whose file cannot be read is withheld rather than deleted`() {
        val result = assessReadingTraceOrphans(
            traceKeys = setOf("k-broken"),
            noteKeys = emptySet(),
            unreadableFolderPaths = emptySet()
        ) { null }

        val assessed = assessed(result)
        assertTrue(assessed.orphans.isEmpty())
        assertEquals(OrphanWithholdReason.UNRESOLVABLE, assessed.withheld.single().reason)
    }

    @Test
    fun `unreadable candidates do not block the readable ones`() {
        val traces = mapOf("k-1" to info("ideas/a.md"))

        val result = assessReadingTraceOrphans(
            traceKeys = setOf("k-1", "k-broken"),
            noteKeys = emptySet(),
            unreadableFolderPaths = emptySet()
        ) { key -> traces[key] }

        assertEquals(listOf("ideas/a.md"), assessed(result).orphans.map { it.vaultRelativePath })
        assertEquals(1, assessed(result).withheld.single().count)
    }

    // --- パス補助 ---------------------------------------------------------------

    @Test
    fun `parent path of a root level note is the empty path`() {
        assertEquals("", parentVaultPath("habit.md"))
        assertEquals("ideas", parentVaultPath("ideas/habit.md"))
        assertEquals("ideas/2026", parentVaultPath("ideas/2026/habit.md"))
    }

    @Test
    fun `unreadable check matches the folder itself and its descendants only`() {
        val unreadable = setOf("ideas")

        assertTrue(isUnderUnreadableFolder("ideas", unreadable))
        assertTrue(isUnderUnreadableFolder("ideas/2026", unreadable))
        assertTrue(!isUnderUnreadableFolder("ideas2", unreadable))
        assertTrue(!isUnderUnreadableFolder("", unreadable))
    }
}

private fun info(
    path: String,
    title: String = "ノート",
    visits: Int = 1,
    lastVisit: Long? = 1_000L
) = OrphanTraceInfo(path, title, visits, lastVisit)

private fun assess(
    traces: Map<String, OrphanTraceInfo>,
    noteKeys: Set<String>,
    unreadableFolderPaths: Set<String> = emptySet(),
    limits: OrphanLimits = OrphanLimits()
): OrphanAssessment = assessReadingTraceOrphans(
    traceKeys = traces.keys,
    noteKeys = noteKeys,
    unreadableFolderPaths = unreadableFolderPaths,
    limits = limits
) { key -> traces[key] }

private fun assessed(result: OrphanAssessment): OrphanAssessment.Assessed =
    result as OrphanAssessment.Assessed
