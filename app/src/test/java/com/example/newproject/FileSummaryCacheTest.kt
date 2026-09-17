package com.example.newproject

import com.example.newproject.data.FileSummaryCache
import com.example.newproject.data.sha256Hex
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 要約を端末に残す実装（→ `docs/dev/features/note_summary.md` 判断7）。 */
class FileSummaryCacheTest {
    private val root = Files.createTempDirectory("summary-cache-test").toFile()
    private val directory = File(root, FileSummaryCache.DIRECTORY_NAME)
    private var now = 1_000_000L

    @After
    fun cleanUp() {
        root.deleteRecursively()
    }

    @Test
    fun `保存した要約は同じプロンプトで引け、作り直したインスタンスからも引ける`() = runTest {
        cache().save("プロンプトA", "要約A")

        assertEquals("要約A", cache().find("プロンプトA"))
    }

    @Test
    fun `1文字でも違うプロンプトでは引けない`() = runTest {
        cache().save("プロンプトA", "要約A")

        assertNull(cache().find("プロンプトA "))
    }

    @Test
    fun `1件1ファイルで、ファイル名はプロンプトのハッシュ`() = runTest {
        val cache = cache()
        cache.save("プロンプトA", "要約A")
        cache.save("プロンプトB", "要約B")

        assertEquals(
            setOf(hash("プロンプトA"), hash("プロンプトB")),
            directory.list().orEmpty().toSet()
        )
    }

    /** 当たったら最後に使った時刻が進むので、一番古く保存したものでも消えない。 */
    @Test
    fun `上限を超えたら最後に使った時刻が古いものから消える`() = runTest {
        val cache = cache(maxEntries = 3)
        now = 1_000_000L; cache.save("A", "要約A")
        now = 2_000_000L; cache.save("B", "要約B")
        now = 3_000_000L; cache.save("C", "要約C")
        now = 4_000_000L; assertEquals("要約A", cache.find("A"))

        now = 5_000_000L; cache.save("D", "要約D")

        assertNull(cache.find("B"))
        assertEquals(listOf("要約A", "要約C", "要約D"), listOf("A", "C", "D").map { cache.find(it) })
    }

    @Test
    fun `上限を大きく超えて保存し続けても件数は上限で止まり、新しいほうが残る`() = runTest {
        val cache = cache(maxEntries = 50)
        repeat(120) { index ->
            now = 1_000_000L + index * 1_000L
            cache.save("プロンプト$index", "要約$index")
        }

        assertEquals(50, directory.list().orEmpty().size)
        assertNull(cache.find("プロンプト69"))
        assertEquals("要約70", cache.find("プロンプト70"))
    }

    @Test
    fun `空の要約と大きすぎる要約は保存しない`() = runTest {
        val cache = cache()
        cache.save("A", "")
        cache.save("B", "あ".repeat(FileSummaryCache.MAX_ENTRY_BYTES / 3 + 1))

        assertNull(cache.find("A"))
        assertNull(cache.find("B"))
        assertTrue(directory.list().orEmpty().isEmpty())
    }

    @Test
    fun `大きすぎるファイルは要約として読まない`() = runTest {
        directory.mkdirs()
        File(directory, hash("A")).writeBytes(ByteArray(FileSummaryCache.MAX_ENTRY_BYTES + 1) { 'a'.code.toByte() })

        assertNull(cache().find("A"))
    }

    @Test
    fun `書きかけの一時ファイルは次の保存で片付く`() = runTest {
        directory.mkdirs()
        File(directory, hash("落ちた書き込み") + ".tmp").writeText("書きかけ")

        cache().save("A", "要約A")

        assertEquals(setOf(hash("A")), directory.list().orEmpty().toSet())
    }

    @Test
    fun `置き場を作れなくても投げず、当たらなかったことにする`() = runTest {
        directory.writeText("同じ名前のファイルが先にある")

        cache().save("A", "要約A")

        assertNull(cache().find("A"))
    }

    private fun cache(maxEntries: Int = FileSummaryCache.MAX_ENTRIES) = FileSummaryCache(
        directory = directory,
        maxEntries = maxEntries,
        clock = { now },
        ioDispatcher = Dispatchers.Unconfined
    )

    private fun hash(prompt: String): String = sha256Hex(prompt.toByteArray(Charsets.UTF_8))
}
