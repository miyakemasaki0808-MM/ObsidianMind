package com.example.newproject

import com.example.newproject.data.ReadingTraceJson
import com.example.newproject.data.ReadingTraceReadResult
import com.example.newproject.data.sha256Hex
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import org.json.JSONArray
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

    // ── schema v1 → v2 の移行 ────────────────────────────────────────────
    //
    // v1 は累計回数を持たず、保持件数（最大30）を回数として使っていた。
    // 既存の痕跡を破損扱いにせず読めることが最優先（読めないと全部消える）。

    @Test
    fun `v1 の痕跡は累計を保持件数で補って読める`() {
        val visits = listOf(visit(at = 1L), visit(at = 2L), visit(at = 3L))

        val decoded = ReadingTraceJson.decode(encodeAsV1(trace(visits = visits)))

        val loaded = (decoded as ReadingTraceReadResult.Valid).trace
        assertEquals(3, loaded.totalVisitCount)
        assertEquals(3, loaded.visits.size)
    }

    // 読み込んだ時点で現行版へ寄せる。次の保存で v1 が書き戻されないことの担保。
    @Test
    fun `v1 を読むと現行フォーマットへ移行される`() {
        val decoded = ReadingTraceJson.decode(encodeAsV1(trace()))

        val loaded = (decoded as ReadingTraceReadResult.Valid).trace
        assertEquals(READING_TRACE_SCHEMA_VERSION, loaded.schemaVersion)
        val reencoded = JSONObject(ReadingTraceJson.encode(loaded).toString(Charsets.UTF_8))
        assertEquals(READING_TRACE_SCHEMA_VERSION, reencoded.getInt("schemaVersion"))
        assertEquals(1, reencoded.getInt("totalVisitCount"))
    }

    // v1 の checksum を v2 の正規形で照合すると全ての既存痕跡が破損になる。
    // 逆に、改変された v1 は従来どおり弾けていること。
    @Test
    fun `改変された v1 は破損扱いのまま`() {
        val bytes = encodeAsV1(trace())
        val root = JSONObject(bytes.toString(Charsets.UTF_8))
        root.put("noteTitle", "すり替えたタイトル")

        assertCorrupt(ReadingTraceJson.decode(root.toString().toByteArray(Charsets.UTF_8)))
    }

    // v1 の checksum は累計を含まないため、書き足されていても信用しない
    // （読むと checksum を通り抜けて任意の回数を名乗れる入口になる）。
    @Test
    fun `v1 に書き足された累計は無視される`() {
        val visits = listOf(visit(at = 1L), visit(at = 2L))
        val root = JSONObject(encodeAsV1(trace(visits = visits)).toString(Charsets.UTF_8))
        root.put("totalVisitCount", 999)

        val decoded = ReadingTraceJson.decode(root.toString().toByteArray(Charsets.UTF_8))

        assertEquals(2, (decoded as ReadingTraceReadResult.Valid).trace.totalVisitCount)
    }

    // ── schema v2 → v3 の移行（ひとこと） ────────────────────────────────
    //
    // **v2 は実際に端末へ書き出されている現行版だった。** v1 と違い「古い実験的な版」
    // ではなく全ユーザーの手元にある形なので、ここが読めなくなると痕跡が全部消える。

    @Test
    fun `v2 の痕跡はひとこと無しで読める`() {
        val visits = listOf(visit(at = 1L), visit(at = 2L))
        val source = trace(visits = visits, aiSummary = "2回開いています", aiSummaryVisitCount = 2)

        val decoded = ReadingTraceJson.decode(encodeAsV2(source))

        val loaded = (decoded as ReadingTraceReadResult.Valid).trace
        assertNull(loaded.remark)
        assertEquals(2, loaded.totalVisitCount)
        assertEquals("2回開いています", loaded.aiSummary)
    }

    @Test
    fun `v2 を読むと現行フォーマットへ移行される`() {
        val decoded = ReadingTraceJson.decode(encodeAsV2(trace()))

        val loaded = (decoded as ReadingTraceReadResult.Valid).trace
        assertEquals(READING_TRACE_SCHEMA_VERSION, loaded.schemaVersion)
        val reencoded = JSONObject(ReadingTraceJson.encode(loaded).toString(Charsets.UTF_8))
        assertEquals(READING_TRACE_SCHEMA_VERSION, reencoded.getInt("schemaVersion"))
    }

    @Test
    fun `改変された v2 は破損扱いのまま`() {
        val root = JSONObject(encodeAsV2(trace()).toString(Charsets.UTF_8))
        root.put("noteTitle", "すり替えたタイトル")

        assertCorrupt(ReadingTraceJson.decode(root.toString().toByteArray(Charsets.UTF_8)))
    }

    // v2 の checksum はひとことを含まないため、書き足されていても信用しない
    // （読むと checksum を通り抜けて任意の文言を名乗れる入口になる）。
    @Test
    fun `v2 に書き足されたひとことは無視される`() {
        val root = JSONObject(encodeAsV2(trace()).toString(Charsets.UTF_8))
        root.put("remark", "外から差し込んだひとこと")

        val decoded = ReadingTraceJson.decode(root.toString().toByteArray(Charsets.UTF_8))

        assertNull((decoded as ReadingTraceReadResult.Valid).trace.remark)
    }

    // ── ひとこと（v3） ──────────────────────────────────────────────────

    @Test
    fun `ひとことが往復する`() {
        val source = trace(remark = "この考えの根拠になった経験は何だろう？")

        val decoded = ReadingTraceJson.decode(ReadingTraceJson.encode(source))

        assertEquals(source, (decoded as ReadingTraceReadResult.Valid).trace)
    }

    @Test
    fun `ひとことの改変は破損扱いになる`() {
        val corrupted = mutate(trace(remark = "元のひとこと")) { it.put("remark", "すり替えたひとこと") }

        assertCorrupt(ReadingTraceJson.decode(corrupted))
    }

    // null と空文字を区別する存在フラグが、ひとことにも効いていること。
    @Test
    fun `ひとことの有無は checksum で区別される`() {
        val withRemark = ReadingTraceJson.encode(trace(remark = "ひとこと"))
        val withoutRemark = ReadingTraceJson.encode(trace(remark = null))

        assertTrue(checksumOf(withRemark) != checksumOf(withoutRemark))
    }

    @Test
    fun `空白だけのひとことは保存できない`() {
        assertFailsWithMessage { ReadingTraceJson.encode(trace(remark = "   ")) }
    }

    // 要約と同じくバイト基準。1文しか入らない枠であることを保存側でも固定する。
    @Test
    fun `ひとことの上限はutf8バイトで測る`() {
        val justOver = "あ".repeat(ReadingTraceLimits.MAX_REMARK_BYTES / 3 + 1)
        val within = "あ".repeat(ReadingTraceLimits.MAX_REMARK_BYTES / 3)

        assertFailsWithMessage { ReadingTraceJson.encode(trace(remark = justOver)) }
        ReadingTraceJson.encode(trace(remark = within))
    }
}

/**
 * schema v1 のサイドカーを組み立てる（本番の encode は現行版しか書けないため）。
 *
 * **v1 の正規形をテスト側に写し取っている。** production の canonicalPayload を
 * 呼べば楽だが、それでは「v1 の checksum を v1 の形で照合している」ことを検証できず、
 * 実装を変えたときに一緒に壊れて気付けない。旧形式は仕様として固定する。
 */
private fun encodeAsV1(trace: ReadingTrace): ByteArray {
    val visits = JSONArray()
    trace.visits.forEach { v ->
        visits.put(
            JSONObject()
                .put("at", v.atEpochMillis)
                .put("deepestSection", v.deepestSectionTitle ?: JSONObject.NULL)
                .put("progressPercent", v.progressPercent)
        )
    }
    val payload = ByteArrayOutputStream()
    DataOutputStream(payload).use { out ->
        out.writeInt(1)
        out.writeSizedForTest(trace.vaultRelativePath)
        out.writeSizedForTest(trace.noteTitle)
        out.writeInt(trace.visits.size)
        trace.visits.forEach { v ->
            out.writeLong(v.atEpochMillis)
            out.writeInt(v.progressPercent)
            if (v.deepestSectionTitle == null) {
                out.writeByte(0)
            } else {
                out.writeByte(1)
                out.writeSizedForTest(v.deepestSectionTitle)
            }
        }
        if (trace.aiSummary == null) {
            out.writeByte(0)
        } else {
            out.writeByte(1)
            out.writeSizedForTest(trace.aiSummary)
        }
        out.writeInt(trace.aiSummaryVisitCount ?: -1)
    }
    return JSONObject()
        .put("schemaVersion", 1)
        .put("vaultRelativePath", trace.vaultRelativePath)
        .put("noteTitle", trace.noteTitle)
        .put("documentId", trace.documentId ?: JSONObject.NULL)
        .put("visits", visits)
        .put("aiSummary", trace.aiSummary ?: JSONObject.NULL)
        .put("aiSummaryVisitCount", trace.aiSummaryVisitCount ?: JSONObject.NULL)
        .put("checksum", sha256Hex(payload.toByteArray()))
        .toString(2)
        .toByteArray(Charsets.UTF_8)
}

/**
 * schema v2 のサイドカーを組み立てる。
 *
 * v1 と同じ理由でテスト側に正規形を写し取っている（production の canonicalPayload を
 * 呼ぶと、実装を変えたときに一緒に壊れて互換の破れに気付けない）。
 * **v2 は v1 と違って全ユーザーの端末に実在する形**なので、ここが仕様として固定される。
 */
private fun encodeAsV2(trace: ReadingTrace): ByteArray {
    val visits = JSONArray()
    trace.visits.forEach { v ->
        visits.put(
            JSONObject()
                .put("at", v.atEpochMillis)
                .put("deepestSection", v.deepestSectionTitle ?: JSONObject.NULL)
                .put("progressPercent", v.progressPercent)
        )
    }
    val payload = ByteArrayOutputStream()
    DataOutputStream(payload).use { out ->
        out.writeInt(2)
        out.writeSizedForTest(trace.vaultRelativePath)
        out.writeSizedForTest(trace.noteTitle)
        out.writeInt(trace.visits.size)
        trace.visits.forEach { v ->
            out.writeLong(v.atEpochMillis)
            out.writeInt(v.progressPercent)
            if (v.deepestSectionTitle == null) {
                out.writeByte(0)
            } else {
                out.writeByte(1)
                out.writeSizedForTest(v.deepestSectionTitle)
            }
        }
        if (trace.aiSummary == null) {
            out.writeByte(0)
        } else {
            out.writeByte(1)
            out.writeSizedForTest(trace.aiSummary)
        }
        out.writeInt(trace.aiSummaryVisitCount ?: -1)
        out.writeInt(trace.totalVisitCount)
    }
    return JSONObject()
        .put("schemaVersion", 2)
        .put("vaultRelativePath", trace.vaultRelativePath)
        .put("noteTitle", trace.noteTitle)
        .put("documentId", trace.documentId ?: JSONObject.NULL)
        .put("visits", visits)
        .put("aiSummary", trace.aiSummary ?: JSONObject.NULL)
        .put("aiSummaryVisitCount", trace.aiSummaryVisitCount ?: JSONObject.NULL)
        .put("totalVisitCount", trace.totalVisitCount)
        .put("checksum", sha256Hex(payload.toByteArray()))
        .toString(2)
        .toByteArray(Charsets.UTF_8)
}

private fun DataOutputStream.writeSizedForTest(value: String) {
    val encoded = value.toByteArray(Charsets.UTF_8)
    writeInt(encoded.size)
    write(encoded)
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
    aiSummaryVisitCount: Int? = null,
    remark: String? = null
) = ReadingTrace(
    vaultRelativePath = path,
    noteTitle = title,
    documentId = documentId,
    visits = visits,
    aiSummary = aiSummary,
    aiSummaryVisitCount = aiSummaryVisitCount,
    remark = remark
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
