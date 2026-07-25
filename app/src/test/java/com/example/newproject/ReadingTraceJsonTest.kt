package com.example.newproject

import com.example.newproject.data.ReadingTraceJson
import com.example.newproject.data.ReadingTraceReadResult
import com.example.newproject.model.ReadingTrace
import com.example.newproject.model.ReadingTraceLimits
import com.example.newproject.model.ReadingVisit
import com.example.newproject.model.READING_TRACE_SCHEMA_VERSION
import com.example.newproject.model.needsAiSummary
import com.example.newproject.model.withVisit
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadingTraceJsonTest {

    @Test
    fun `round trips a trace`() {
        val trace = trace(
            visits = listOf(
                ReadingVisit(atEpochMillis = 100L, deepestSectionTitle = "導入", progressPercent = 30),
                ReadingVisit(atEpochMillis = 200L, deepestSectionTitle = null, progressPercent = 100)
            ),
            aiSummary = "2回開いて前半で止まっています",
            aiSummaryVisitCount = 2
        )

        val decoded = ReadingTraceJson.decode(ReadingTraceJson.encode(trace))

        assertEquals(ReadingTraceReadResult.Valid(trace), decoded)
    }

    @Test
    fun `written json is human readable`() {
        val bytes = ReadingTraceJson.encode(trace())

        val text = bytes.toString(Charsets.UTF_8)
        assertTrue("整形されていない: $text", text.contains("\n"))
        assertTrue(text.contains("vaultRelativePath"))
    }

    @Test
    fun `tampered checksum is corrupt`() {
        val corrupted = mutate(trace()) { it.put("checksum", "0".repeat(64)) }

        assertCorrupt(ReadingTraceJson.decode(corrupted))
    }

    @Test
    fun `tampered content without checksum update is corrupt`() {
        val corrupted = mutate(trace()) { it.put("noteTitle", "すり替えたタイトル") }

        assertCorrupt(ReadingTraceJson.decode(corrupted))
    }

    @Test
    fun `empty file is corrupt`() {
        assertCorrupt(ReadingTraceJson.decode(ByteArray(0)))
    }

    @Test
    fun `truncated file is corrupt`() {
        val bytes = ReadingTraceJson.encode(trace())

        assertCorrupt(ReadingTraceJson.decode(bytes.copyOf(bytes.size / 2)))
    }

    @Test
    fun `unknown schema version is corrupt`() {
        val corrupted = mutate(trace()) { it.put("schemaVersion", READING_TRACE_SCHEMA_VERSION + 1) }

        assertCorrupt(ReadingTraceJson.decode(corrupted))
    }

    @Test
    fun `missing required field is corrupt`() {
        val corrupted = mutate(trace()) { it.remove("vaultRelativePath") }

        assertCorrupt(ReadingTraceJson.decode(corrupted))
    }

    @Test
    fun `invalid utf8 bytes are corrupt`() {
        // 単独の継続バイトはUTF-8として不正
        assertCorrupt(ReadingTraceJson.decode(byteArrayOf(0x80.toByte(), 0x81.toByte())))
    }

    // documentId は端末内キャッシュなので checksum の対象外。別端末で引き当て直して
    // 書き換えても、ユーザー内容の整合性検証は通り続ける必要がある。
    @Test
    fun `replacing document id keeps the trace valid`() {
        val rebound = mutate(trace(documentId = "local-id-a")) { it.put("documentId", "local-id-b") }

        val decoded = ReadingTraceJson.decode(rebound)

        assertTrue(decoded is ReadingTraceReadResult.Valid)
        assertEquals("local-id-b", (decoded as ReadingTraceReadResult.Valid).trace.documentId)
    }

    @Test
    fun `null document id survives round trip`() {
        val decoded = ReadingTraceJson.decode(ReadingTraceJson.encode(trace(documentId = null)))

        assertNull((decoded as ReadingTraceReadResult.Valid).trace.documentId)
    }

    // null と空文字を区別できること（canonical payload の存在フラグが効いているか）
    @Test
    fun `empty section title is distinguished from null`() {
        val withEmpty = trace(visits = listOf(visit(section = "")))
        val withNull = trace(visits = listOf(visit(section = null)))

        val a = ReadingTraceJson.decode(ReadingTraceJson.encode(withEmpty))
        val b = ReadingTraceJson.decode(ReadingTraceJson.encode(withNull))

        assertEquals("", (a as ReadingTraceReadResult.Valid).trace.visits.single().deepestSectionTitle)
        assertNull((b as ReadingTraceReadResult.Valid).trace.visits.single().deepestSectionTitle)
    }

    // 長さ前置なので、区切り文字を本文に混ぜて別の内容へ偽装できない。
    @Test
    fun `field boundaries cannot be forged by embedding separators`() {
        val a = ReadingTraceJson.encode(trace(path = "a/b.md", title = "T"))
        val b = ReadingTraceJson.encode(trace(path = "a", title = "/b.mdT"))

        assertTrue(checksumOf(a) != checksumOf(b))
    }

    @Test
    fun `encode rejects empty visits`() {
        assertFailsWithMessage { ReadingTraceJson.encode(trace(visits = emptyList())) }
    }

    @Test
    fun `encode rejects progress outside range`() {
        assertFailsWithMessage { ReadingTraceJson.encode(trace(visits = listOf(visit(progress = 101)))) }
        assertFailsWithMessage { ReadingTraceJson.encode(trace(visits = listOf(visit(progress = -1)))) }
    }

    @Test
    fun `encode rejects blank relative path`() {
        assertFailsWithMessage { ReadingTraceJson.encode(trace(path = "  ")) }
    }

    // 上限はバイト基準。日本語はUTF-8で1文字3バイトなので、文字数では収まっていても弾かれる。
    @Test
    fun `section title limit is measured in utf8 bytes`() {
        val justOver = "あ".repeat(ReadingTraceLimits.MAX_SECTION_TITLE_BYTES / 3 + 1)
        val within = "あ".repeat(ReadingTraceLimits.MAX_SECTION_TITLE_BYTES / 3)

        assertFailsWithMessage {
            ReadingTraceJson.encode(trace(visits = listOf(visit(section = justOver))))
        }
        ReadingTraceJson.encode(trace(visits = listOf(visit(section = within))))
    }

    @Test
    fun `encode rejects ai summary without visit count`() {
        assertFailsWithMessage {
            ReadingTraceJson.encode(trace(aiSummary = "要約", aiSummaryVisitCount = null))
        }
        assertFailsWithMessage {
            ReadingTraceJson.encode(trace(aiSummary = null, aiSummaryVisitCount = 1))
        }
    }

    @Test
    fun `encode rejects visit count greater than visits`() {
        assertFailsWithMessage {
            ReadingTraceJson.encode(
                trace(visits = listOf(visit()), aiSummary = "要約", aiSummaryVisitCount = 2)
            )
        }
    }

    @Test
    fun `with visit drops the oldest beyond the cap`() {
        val cap = ReadingTraceLimits.MAX_VISITS
        var subject = trace(visits = listOf(visit(at = 0L)))
        (1..cap).forEach { index -> subject = subject.withVisit(visit(at = index.toLong())) }

        assertEquals(cap, subject.visits.size)
        // 最初の訪問（at=0）が押し出され、最後に足した訪問が残る
        assertEquals(1L, subject.visits.first().atEpochMillis)
        assertEquals(cap.toLong(), subject.visits.last().atEpochMillis)
    }

    @Test
    fun `capped trace still encodes`() {
        var subject = trace(visits = listOf(visit(at = 0L)))
        (1..ReadingTraceLimits.MAX_VISITS + 5).forEach { subject = subject.withVisit(visit(at = it.toLong())) }

        assertTrue(ReadingTraceJson.decode(ReadingTraceJson.encode(subject)) is ReadingTraceReadResult.Valid)
    }

    @Test
    fun `ai summary is not needed for a single visit`() {
        assertTrue(!trace(visits = listOf(visit())).needsAiSummary)
    }

    @Test
    fun `ai summary is needed when visits grew past the cached count`() {
        val subject = trace(
            visits = listOf(visit(at = 1L), visit(at = 2L), visit(at = 3L)),
            aiSummary = "古い要約",
            aiSummaryVisitCount = 2
        )

        assertTrue(subject.needsAiSummary)
    }

    @Test
    fun `ai summary is reused when visit count matches`() {
        val subject = trace(
            visits = listOf(visit(at = 1L), visit(at = 2L)),
            aiSummary = "要約",
            aiSummaryVisitCount = 2
        )

        assertTrue(!subject.needsAiSummary)
    }
}

// --- ヘルパ ---------------------------------------------------------------

private fun visit(
    at: Long = 1_000L,
    section: String? = "導入",
    progress: Int = 40
) = ReadingVisit(atEpochMillis = at, deepestSectionTitle = section, progressPercent = progress)

private fun trace(
    path: String = "ideas/habit.md",
    title: String = "習慣について",
    documentId: String? = "doc-1",
    visits: List<ReadingVisit> = listOf(visit()),
    aiSummary: String? = null,
    aiSummaryVisitCount: Int? = null
) = ReadingTrace(
    vaultRelativePath = path,
    noteTitle = title,
    documentId = documentId,
    visits = visits,
    aiSummary = aiSummary,
    aiSummaryVisitCount = aiSummaryVisitCount
)

/** encode した JSON を書き換えて破損・改変を再現する。 */
private fun mutate(trace: ReadingTrace, edit: (JSONObject) -> Unit): ByteArray {
    val root = JSONObject(ReadingTraceJson.encode(trace).toString(Charsets.UTF_8))
    edit(root)
    return root.toString().toByteArray(Charsets.UTF_8)
}

private fun checksumOf(encoded: ByteArray): String =
    JSONObject(encoded.toString(Charsets.UTF_8)).getString("checksum")

private fun assertCorrupt(result: ReadingTraceReadResult) {
    assertTrue("Corrupt を期待したが $result だった", result is ReadingTraceReadResult.Corrupt)
}

private fun assertFailsWithMessage(block: () -> Unit) {
    try {
        block()
    } catch (error: IllegalArgumentException) {
        assertTrue("日本語の理由が付いていない", !error.message.isNullOrBlank())
        return
    }
    throw AssertionError("検証エラーを期待したが例外が出なかった")
}
