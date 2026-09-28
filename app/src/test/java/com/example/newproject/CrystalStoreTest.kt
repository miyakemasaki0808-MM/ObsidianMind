package com.example.newproject

import com.example.newproject.data.CrystalDocument
import com.example.newproject.data.CrystalDocumentGateway
import com.example.newproject.data.CrystalJson
import com.example.newproject.data.CrystalListing
import com.example.newproject.data.CrystalSaveResult
import com.example.newproject.data.CrystalStore
import com.example.newproject.data.FileCrystalMaterialStore
import com.example.newproject.model.Crystal
import com.example.newproject.model.CrystalLimits
import com.example.newproject.model.CrystalMaterial
import com.example.newproject.model.CrystalMaterialLog
import com.example.newproject.model.CrystalSource
import java.io.IOException
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CrystalStoreTest {

    // ── ファイル形式 ────────────────────────────────────────────────────────

    @Test
    fun `書いた結晶はそのまま読み戻せる。ファイル名は読んだ側が付ける`() {
        val crystal = crystal()
        val decoded = CrystalJson.decode(CrystalJson.encode(crystal), "123.json")
        assertEquals(crystal.copy(fileName = "123.json"), decoded)
    }

    @Test
    fun `中身を書き換えたファイルは checksum で読めない`() {
        val text = String(CrystalJson.encode(crystal()), Charsets.UTF_8)
        val tampered = text.replace("共通する筋", "書き換えた筋")
        assertNull(CrystalJson.decode(tampered.toByteArray(Charsets.UTF_8), "x.json"))
    }

    @Test
    fun `未知の版と壊れたJSONは読めない`() {
        val future = String(CrystalJson.encode(crystal()), Charsets.UTF_8)
            .replace("\"schemaVersion\": 1", "\"schemaVersion\": 2")
        assertNull(CrystalJson.decode(future.toByteArray(Charsets.UTF_8), "x.json"))
        assertNull(CrystalJson.decode("{".toByteArray(), "x.json"))
        assertNull(CrystalJson.decode(ByteArray(0), "x.json"))
    }

    // ── 置き場 ──────────────────────────────────────────────────────────────

    @Test
    fun `読めない1件だけを飛ばし、一覧全体は止めない`() {
        val gateway = FakeGateway()
        gateway.files += CrystalDocument("1.json", CrystalJson.encode(crystal(createdAt = 1)))
        gateway.files += CrystalDocument("2.json", "{".toByteArray())
        gateway.files += CrystalDocument("3.json", null)
        val listing = CrystalStore(gateway).readAll(VAULT) as CrystalListing.Available
        assertEquals(listOf("1.json"), listing.crystals.map { it.fileName })
        assertEquals(2, listing.unreadable)
    }

    @Test
    fun `列挙できなければ結晶が無いとは言わない`() {
        val gateway = FakeGateway().apply { listable = false }
        assertTrue(CrystalStore(gateway).readAll(VAULT) is CrystalListing.Unavailable)
    }

    @Test
    fun `保存は作成時刻の名前で新しいファイルを作り、実際の名前を返す`() {
        val gateway = FakeGateway().apply { rename = { "$it (1)" } }
        val result = CrystalStore(gateway).append(crystal(createdAt = 42), VAULT) as CrystalSaveResult.Saved
        assertEquals("42.json (1)", result.crystal.fileName)
        assertEquals(listOf("42.json (1)"), gateway.files.map { it.name })
    }

    @Test
    fun `上限を超える結晶は書かない`() {
        val gateway = FakeGateway()
        val huge = crystal().copy(
            sources = List(CrystalLimits.MAX_SOURCES) { CrystalSource("p$it.md", "題".repeat(3_000), "断片") }
        )
        assertTrue(CrystalStore(gateway).append(huge, VAULT) is CrystalSaveResult.Failure)
        assertTrue(gateway.files.isEmpty())
    }

    @Test
    fun `境界が書けなければ失敗を返す`() {
        val gateway = FakeGateway().apply { failCreate = true }
        assertTrue(CrystalStore(gateway).append(crystal(), VAULT) is CrystalSaveResult.Failure)
    }

    // ── 控え（端末内）──────────────────────────────────────────────────────

    @Test
    fun `控えは Vault ごとに書いて読み戻せる`() {
        val directory = Files.createTempDirectory("crystal_material").toFile()
        val store = FileCrystalMaterialStore(directory)
        val log = CrystalMaterialLog(
            lastAttemptAt = 7,
            entries = listOf(CrystalMaterial("a.md", "A", "要約。", lastSeenAt = 3)),
            presented = listOf("fp1", "fp2")
        )
        store.save("vault-a", log)
        assertEquals(log, store.load("vault-a"))
        assertEquals(CrystalMaterialLog.EMPTY, store.load("vault-b"))
        directory.deleteRecursively()
    }

    @Test
    fun `壊れた控えは空から始める`() {
        val directory = Files.createTempDirectory("crystal_material").toFile()
        val store = FileCrystalMaterialStore(directory)
        store.save("vault-a", CrystalMaterialLog(lastAttemptAt = 1))
        directory.listFiles()!!.single { it.name.endsWith(".json") }.writeText("{壊れた")
        assertEquals(CrystalMaterialLog.EMPTY, store.load("vault-a"))
        directory.deleteRecursively()
    }

    private class FakeGateway : CrystalDocumentGateway {
        val files = mutableListOf<CrystalDocument>()
        var listable = true
        var failCreate = false
        var rename: (String) -> String = { it }

        override fun readAll(maximumBytes: Int, vaultKey: String): List<CrystalDocument>? =
            if (listable) files.toList() else null

        override fun create(fileName: String, bytes: ByteArray, vaultKey: String): String {
            if (failCreate) throw IOException("作れない")
            val name = rename(fileName)
            files += CrystalDocument(name, bytes)
            return name
        }
    }

    private fun crystal(createdAt: Long = 100) = Crystal(
        createdAt = createdAt,
        sentence = "どちらも共通する筋を扱っている。",
        sources = listOf(
            CrystalSource("a.md", "A", "Aの要約。"),
            CrystalSource("b.md", "B", "Bの要約。")
        ),
        fileName = ""
    )

    private companion object {
        const val VAULT = "vault-a"
    }
}
