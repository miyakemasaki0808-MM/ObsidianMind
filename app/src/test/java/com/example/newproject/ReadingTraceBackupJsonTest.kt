package com.example.newproject

import com.example.newproject.data.ReadingTraceBackupEntry
import com.example.newproject.data.ReadingTraceBackupJson
import com.example.newproject.data.ReadingTraceBackupReadResult
import com.example.newproject.data.ReadingTraceBackupTooLargeException
import com.example.newproject.model.READING_TRACE_SCHEMA_VERSION
import com.example.newproject.model.ReadingTrace
import com.example.newproject.model.ReadingTraceBackupLimits
import com.example.newproject.model.ReadingVisit
import com.example.newproject.model.Reflection
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 退避ファイルの束ね方。
 *
 * **中身1件ずつの版と checksum は `ReadingTraceJsonTest` が持つ。** ここで見るのは
 * 束ねる側だけ — 別形式・新しすぎる版を**ファイルごと**止めること、
 * 壊れた1件を黙って捨てないこと、そして端末に紐づく値を外へ出さないこと。
 */
class ReadingTraceBackupJsonTest {

    @Test
    fun `書き出して読み戻すと中身が保たれる`() {
        val traces = listOf(
            trace("ideas/habit.md").copy(
                reflection = Reflection("問い", 100L, "返事", 200L, "映し返し")
            ),
            trace("journal/2026.md")
        )

        val decoded = ReadingTraceBackupJson.decode(ReadingTraceBackupJson.encode(traces, 1_000L))

        val entries = (decoded as ReadingTraceBackupReadResult.Valid).entries
        assertEquals(2, entries.size)
        val first = (entries[0] as ReadingTraceBackupEntry.Valid).trace
        assertEquals("ideas/habit.md", first.vaultRelativePath)
        assertEquals("返事", first.reflection?.reply)
        assertEquals("映し返し", first.reflection?.mirrored)
    }

    /**
     * **`documentId` は外へ出さない。**
     *
     * 別端末では無効な値であるうえ、SAF の documentId には端末内のパスが入る。
     * 退避ファイルは平文でアプリの管理外へ出るので、載せる理由が1つも無い。
     */
    @Test
    fun `documentId は退避ファイルへ書かない`() {
        val traces = listOf(trace("ideas/habit.md").copy(documentId = "content://device/tree/doc"))

        val bytes = ReadingTraceBackupJson.encode(traces, 1_000L)

        assertTrue(
            "端末内のパスが退避ファイルへ漏れている",
            !String(bytes, Charsets.UTF_8).contains("content://device")
        )
        val entries = (ReadingTraceBackupJson.decode(bytes) as ReadingTraceBackupReadResult.Valid).entries
        assertNull((entries[0] as ReadingTraceBackupEntry.Valid).trace.documentId)
    }

    @Test
    fun `別形式のJSONは受け付けない`() {
        val alien = JSONObject().put("hello", "world").toString().toByteArray(Charsets.UTF_8)

        assertTrue(ReadingTraceBackupJson.decode(alien) is ReadingTraceBackupReadResult.Unusable)
    }

    @Test
    fun `JSONですらないファイルは受け付けない`() {
        val alien = "# ただのMarkdown\n".toByteArray(Charsets.UTF_8)

        assertTrue(ReadingTraceBackupJson.decode(alien) is ReadingTraceBackupReadResult.Unusable)
    }

    /**
     * **新しすぎる版は1件も読まない。** 読めた分だけ適用すると、
     * 不可逆な操作を中途半端に残す（→ reading_trace_backup §11）。
     */
    @Test
    fun `新しすぎる版はファイルごと中止する`() {
        val bytes = ReadingTraceBackupJson.encode(listOf(trace("ideas/habit.md")), 1_000L)
        val root = JSONObject(String(bytes, Charsets.UTF_8))
            .put("backupVersion", ReadingTraceBackupLimits.FORMAT_VERSION + 1)

        val result = ReadingTraceBackupJson.decode(root.toString().toByteArray(Charsets.UTF_8))

        assertTrue(result is ReadingTraceBackupReadResult.Unusable)
        assertTrue(
            "版が新しいことを理由として伝えていない: ${(result as ReadingTraceBackupReadResult.Unusable).reason}",
            result.reason.contains("新しい版")
        )
    }

    /** 壊れた1件は**捨てずに数える**。捨てると「何件が適用されなかったか」が消える。 */
    @Test
    fun `壊れた1件は残りを止めずに保留として数える`() {
        val bytes = ReadingTraceBackupJson.encode(
            listOf(trace("ideas/habit.md"), trace("journal/2026.md")),
            1_000L
        )
        val root = JSONObject(String(bytes, Charsets.UTF_8))
        // checksum が守っている欄を書き換える。整合性検査で1件だけが破損になる。
        root.getJSONArray("traces").getJSONObject(0).put("noteTitle", "改ざん")

        val entries = (ReadingTraceBackupJson.decode(root.toString().toByteArray(Charsets.UTF_8))
            as ReadingTraceBackupReadResult.Valid).entries

        assertTrue(entries[0] is ReadingTraceBackupEntry.Corrupt)
        assertTrue(entries[1] is ReadingTraceBackupEntry.Valid)
    }

    @Test
    fun `件数が上限を超えたら書き出さない`() {
        val traces = (0..ReadingTraceBackupLimits.MAX_ENTRIES).map { trace("notes/$it.md") }

        try {
            ReadingTraceBackupJson.encode(traces, 1_000L)
            throw AssertionError("上限を超えても書き出せてしまった")
        } catch (expected: ReadingTraceBackupTooLargeException) {
            assertTrue(expected.message!!.contains("多すぎ"))
        }
    }

    @Test
    fun `件数が上限を超えた退避ファイルは読まない`() {
        val root = JSONObject()
            .put("format", ReadingTraceBackupLimits.FORMAT_ID)
            .put("backupVersion", ReadingTraceBackupLimits.FORMAT_VERSION)
            .put("traces", org.json.JSONArray().apply {
                repeat(ReadingTraceBackupLimits.MAX_ENTRIES + 1) { put(JSONObject()) }
            })

        assertTrue(
            ReadingTraceBackupJson.decode(root.toString().toByteArray(Charsets.UTF_8))
                is ReadingTraceBackupReadResult.Unusable
        )
    }

    /** 束ねる版は痕跡のスキーマ版とは別物。**同じ数字だからと片方で代用しない。** */
    @Test
    fun `退避形式の版と痕跡のスキーマ版を別々に書く`() {
        val bytes = ReadingTraceBackupJson.encode(listOf(trace("ideas/habit.md")), 1_000L)

        val root = JSONObject(String(bytes, Charsets.UTF_8))
        assertEquals(ReadingTraceBackupLimits.FORMAT_VERSION, root.getInt("backupVersion"))
        assertEquals(READING_TRACE_SCHEMA_VERSION, root.getInt("traceSchemaVersion"))
    }
}

private fun trace(path: String) = ReadingTrace(
    vaultRelativePath = path,
    noteTitle = path.substringAfterLast('/'),
    documentId = null,
    visits = listOf(ReadingVisit(1_000L, "見出し", 50)),
    totalVisitCount = 1
)
