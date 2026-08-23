package com.example.newproject

import com.example.newproject.controller.ReadingTraceBackupController
import com.example.newproject.data.ReadingTraceBackupJson
import com.example.newproject.data.ReadingTraceFolderStatus
import com.example.newproject.data.ReadingTraceKeyListing
import com.example.newproject.data.ReadingTracePersistence
import com.example.newproject.data.ReadingTraceReadResult
import com.example.newproject.data.ReadingTraceSaveResult
import com.example.newproject.data.ReadingTraceStore
import com.example.newproject.model.ReadingTrace
import com.example.newproject.model.ReadingTraceBackupStateWriter
import com.example.newproject.model.ReadingTraceImportWithholdReason
import com.example.newproject.model.ReadingVisit
import com.example.newproject.model.Reflection
import com.example.newproject.model.state.ReadingTraceBackupState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 退避の結線を固定する。
 *
 * 突き合わせ規則は `ReadingTraceMergeTest`、束ね方は `ReadingTraceBackupJsonTest` が持つ。
 * ここで見るのは **Controller にしか無いもの** — 列挙の失敗を空の退避ファイルへ畳まないこと、
 * 下見と適用が分かれていて確定するまで1件も書かないこと、
 * そして走行中・下見中に Vault が切り替わったら書かないこと。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReadingTraceBackupControllerTest {

    // ── 書き出し ──────────────────────────────────────────────────────────

    @Test
    fun `全痕跡を1ファイルへ書き出す`() = runTest {
        val env = Env(this)
        env.persistence.put(trace("ideas/habit.md"))
        env.persistence.put(trace("journal/2026.md"))

        env.controller.export { bytes -> env.written = bytes }
        advanceUntilIdle()

        assertEquals(2, (env.state.value as ReadingTraceBackupState.Exported).written)
        val entries = ReadingTraceBackupJson.decode(env.written!!)
        assertTrue(entries is com.example.newproject.data.ReadingTraceBackupReadResult.Valid)
    }

    /**
     * **列挙の失敗を「痕跡ゼロ」へ畳まない。**
     *
     * 畳むと空の退避ファイルが書かれる。そのファイルを信じて端末を移した時点で、
     * 守るはずだったものが全部失われる — この機能で最悪の壊れ方。
     */
    @Test
    fun `列挙できなかったときは書き出さずエラーにする`() = runTest {
        val env = Env(this)
        env.persistence.put(trace("ideas/habit.md"))
        env.persistence.listingUnavailable = true

        env.controller.export { bytes -> env.written = bytes }
        advanceUntilIdle()

        assertTrue(env.state.value is ReadingTraceBackupState.Error)
        assertNull("空の退避ファイルを書いてしまった", env.written)
    }

    @Test
    fun `読めなかった痕跡は件数として報告し中身は含めない`() = runTest {
        val env = Env(this)
        env.persistence.put(trace("ideas/habit.md"))
        env.persistence.put(trace("journal/2026.md"))
        env.persistence.corruptKeys += ReadingTraceStore.keyFor("journal/2026.md")

        env.controller.export { bytes -> env.written = bytes }
        advanceUntilIdle()

        val exported = env.state.value as ReadingTraceBackupState.Exported
        assertEquals(1, exported.written)
        assertEquals(listOf(ReadingTraceStore.keyFor("journal/2026.md")), exported.unreadableKeys)
    }

    // 1件も読めなかったなら書き出さない。空のファイルを「退避できた」と見せない。
    @Test
    fun `どれも読めなかったときは書き出さない`() = runTest {
        val env = Env(this)
        env.persistence.put(trace("ideas/habit.md"))
        env.persistence.corruptKeys += ReadingTraceStore.keyFor("ideas/habit.md")

        env.controller.export { bytes -> env.written = bytes }
        advanceUntilIdle()

        assertTrue(env.state.value is ReadingTraceBackupState.Error)
        assertNull(env.written)
    }

    @Test
    fun `痕跡が1件も無いなら書き出さない`() = runTest {
        val env = Env(this)

        env.controller.export { bytes -> env.written = bytes }
        advanceUntilIdle()

        assertTrue(env.state.value is ReadingTraceBackupState.Error)
        assertNull(env.written)
    }

    // ── 読み戻しの下見 ────────────────────────────────────────────────────

    @Test
    fun `下見は件数を数えるだけで1件も書かない`() = runTest {
        val env = Env(this)
        env.persistence.put(trace("ideas/habit.md"))
        val backup = ReadingTraceBackupJson.encode(
            listOf(trace("ideas/habit.md").copy(totalVisitCount = 9), trace("new/note.md")),
            1_000L
        )

        env.controller.prepareImport { backup }
        advanceUntilIdle()

        val plan = (env.state.value as ReadingTraceBackupState.Planned).plan
        assertEquals(1, plan.added)
        assertEquals(1, plan.merged)
        assertEquals(0, env.persistence.saveCount)
    }

    /** **失われる返事の件数を確定前に数える。** ここが「不可逆の予告」の実体。 */
    @Test
    fun `返事が置き換わる件数を下見で数える`() = runTest {
        val env = Env(this)
        env.persistence.put(
            trace("ideas/habit.md").copy(reflection = reflection("問い", 100L, "端末側の返事", 200L))
        )
        val backup = ReadingTraceBackupJson.encode(
            listOf(trace("ideas/habit.md").copy(reflection = reflection("問い", 300L, "退避側の返事", 400L))),
            1_000L
        )

        env.controller.prepareImport { backup }
        advanceUntilIdle()

        assertEquals(1, (env.state.value as ReadingTraceBackupState.Planned).plan.replyReplaced)
    }

    @Test
    fun `読めない版の退避ファイルは下見の時点で止める`() = runTest {
        val env = Env(this)
        val root = org.json.JSONObject(
            String(ReadingTraceBackupJson.encode(listOf(trace("ideas/habit.md")), 1_000L), Charsets.UTF_8)
        ).put("backupVersion", 99)

        env.controller.prepareImport { root.toString().toByteArray(Charsets.UTF_8) }
        advanceUntilIdle()

        assertTrue(env.state.value is ReadingTraceBackupState.Error)
        assertEquals(0, env.persistence.saveCount)
    }

    // 手で結合された退避ファイルは同じノートを2件持ちうる。片方を落とすと返事を失う。
    @Test
    fun `退避ファイル内の重複は畳んで1件にする`() = runTest {
        val env = Env(this)
        val backup = ReadingTraceBackupJson.encode(
            listOf(
                trace("ideas/habit.md").copy(reflection = reflection("問い", 100L)),
                trace("ideas/habit.md").copy(reflection = reflection("問い", 100L, "返事", 200L))
            ),
            1_000L
        )

        env.controller.prepareImport { backup }
        advanceUntilIdle()
        env.controller.applyImport()
        advanceUntilIdle()

        val imported = env.state.value as ReadingTraceBackupState.Imported
        assertEquals(1, imported.added)
        assertEquals(
            "返事",
            env.persistence.stored("ideas/habit.md")?.reflection?.reply
        )
    }

    // ── 読み戻しの適用 ────────────────────────────────────────────────────

    @Test
    fun `確定すると突き合わせた結果を書き込む`() = runTest {
        val env = Env(this)
        env.persistence.put(trace("ideas/habit.md").copy(totalVisitCount = 3))
        val backup = ReadingTraceBackupJson.encode(
            listOf(trace("ideas/habit.md").copy(totalVisitCount = 12), trace("new/note.md")),
            1_000L
        )

        env.controller.prepareImport { backup }
        advanceUntilIdle()
        env.controller.applyImport()
        advanceUntilIdle()

        val imported = env.state.value as ReadingTraceBackupState.Imported
        assertEquals(1, imported.added)
        assertEquals(1, imported.merged)
        assertEquals(12, env.persistence.stored("ideas/habit.md")?.totalVisitCount)
        assertEquals("new/note.md", env.persistence.stored("new/note.md")?.vaultRelativePath)
    }

    @Test
    fun `下見を経ていない確定は何もしない`() = runTest {
        val env = Env(this)

        env.controller.applyImport()
        advanceUntilIdle()

        assertEquals(0, env.persistence.saveCount)
    }

    @Test
    fun `書き込めなかった1件は保留として報告する`() = runTest {
        val env = Env(this)
        env.persistence.unwritablePaths += "new/note.md"
        val backup = ReadingTraceBackupJson.encode(listOf(trace("new/note.md")), 1_000L)

        env.controller.prepareImport { backup }
        advanceUntilIdle()
        env.controller.applyImport()
        advanceUntilIdle()

        val imported = env.state.value as ReadingTraceBackupState.Imported
        assertEquals(0, imported.added)
        assertEquals(
            listOf(ReadingTraceImportWithholdReason.SAVE_FAILED),
            imported.withheld.map { it.reason }
        )
    }

    /**
     * 下見と確定のあいだに Vault が切り替わったら書かない。
     *
     * **痕跡のキーは相対パスのハッシュなので、別Vaultに同じ相対パスのノートがあれば
     * キーも一致する。** 読み直すと無関係な痕跡を上書きし得る（整理側と同じ規律）。
     */
    @Test
    fun `下見のあとにVaultが変わったら書き込まない`() = runTest {
        val env = Env(this)
        val backup = ReadingTraceBackupJson.encode(listOf(trace("ideas/habit.md")), 1_000L)

        env.controller.prepareImport { backup }
        advanceUntilIdle()
        env.vaultKey = "content://another-vault"
        env.controller.applyImport()
        advanceUntilIdle()

        assertTrue(env.state.value is ReadingTraceBackupState.Error)
        assertEquals(0, env.persistence.saveCount)
    }

    @Test
    fun `走行中にVaultが切り替わったら結果を捨てる`() = runTest {
        val env = Env(this)
        env.persistence.put(trace("ideas/habit.md"))
        // 列挙から戻る途中で Vault が切り替わる。
        env.persistence.beforeLoad = { env.generation++ }

        env.controller.export { bytes -> env.written = bytes }
        advanceUntilIdle()

        assertTrue(
            "旧Vaultの結果が新Vaultの画面へ出た: ${env.state.value}",
            env.state.value is ReadingTraceBackupState.Working
        )
        assertNull(env.written)
    }

    @Test
    fun `Vault未選択なら何もしない`() = runTest {
        val env = Env(this)
        env.vaultKey = null

        env.controller.export { bytes -> env.written = bytes }
        advanceUntilIdle()

        assertTrue(env.state.value is ReadingTraceBackupState.Error)
        assertNull(env.written)
    }

    // ── 訪問の追記との直列化 ────────────────────────────────────────────────

    /**
     * **痕跡の read-modify-write は訪問の追記と同じ錠で直列化する。**
     *
     * 読み戻しは「端末側を読む → 突き合わせる → 書く」で、訪問の追記とまったく同じ形をしている。
     * 錠を共有しないと、**保存先を選ぶあいだにアプリが背面へ回って訪問が書き出された**とき
     * （実際に起こる順序）に読み取りが古いまま上書きし、そのノートの読み戻しが黙って効かなくなる。
     */
    @Test
    fun `訪問の追記が錠を握っている間は書き込まない`() = runTest {
        val env = Env(this)
        val backup = ReadingTraceBackupJson.encode(listOf(trace("ideas/habit.md")), 1_000L)
        env.controller.prepareImport { backup }
        advanceUntilIdle()

        env.writeMutex.lock()
        env.controller.applyImport()
        advanceUntilIdle()
        assertEquals("錠を無視して書き込んだ", 0, env.persistence.saveCount)

        env.writeMutex.unlock()
        advanceUntilIdle()
        assertEquals(1, env.persistence.saveCount)
    }

    // ── 中断 ──────────────────────────────────────────────────────────────

    // 適用の途中で止めた分は**既に書かれている**。「やめました」だけでは足りない。
    @Test
    fun `適用の中断はどこまで適用したかを結果として残す`() = runTest {
        val env = Env(this)
        val backup = ReadingTraceBackupJson.encode(
            (1..60).map { trace("notes/$it.md") },
            1_000L
        )
        env.controller.prepareImport { backup }
        advanceUntilIdle()
        // 25件（IOの1まとまり）を書き終えた時点で中止する。
        env.persistence.afterSave = { if (env.persistence.saveCount == 25) env.controller.cancel() }

        env.controller.applyImport()
        advanceUntilIdle()

        val imported = env.state.value as ReadingTraceBackupState.Imported
        assertTrue("中断したことが結果に出ていない", imported.interrupted)
        assertEquals(25, env.persistence.saveCount)
        // **書いた件数と報告が食い違わないこと。** まとまりを書き終えてから数えると
        // ここが 0 になり、実際には25件書いたのに「何も適用されなかった」と誤解させる。
        //
        // 中断は非同期なので、ずれは**最大1件**（数えるのは書いた後なので、
        // 書けていないものを「適用した」と言うことはない）。
        assertTrue(
            "書いた件数と報告が食い違う: 書き込み ${env.persistence.saveCount} / 報告 ${imported.added}",
            imported.added in env.persistence.saveCount - 1..env.persistence.saveCount
        )
    }

    // 書き出しは束ね終えた後にしか書かないので、中断しても保存先は汚れない。
    @Test
    fun `書き出しの中断は待機へ戻すだけ`() = runTest {
        val env = Env(this)
        env.persistence.put(trace("ideas/habit.md"))
        env.persistence.beforeLoad = { env.controller.cancel() }

        env.controller.export { bytes -> env.written = bytes }
        advanceUntilIdle()

        assertEquals(ReadingTraceBackupState.Idle, env.state.value)
        assertNull(env.written)
    }

    private class Env(scope: kotlinx.coroutines.test.TestScope) {
        val persistence = FakeBackupPersistence()
        var generation = 0L
        var vaultKey: String? = VAULT
        var written: ByteArray? = null
        val state = RecordingWriter()

        /** 本番では `ReadingTraceController` と共有する錠。訪問の追記が握っている状況を作る。 */
        val writeMutex = Mutex()

        val controller = ReadingTraceBackupController(
            scope = scope,
            persistence = persistence,
            state = state,
            currentVaultKey = { vaultKey },
            vaultGeneration = { generation },
            clock = { 1_000L },
            // Dispatchers.IO はテストスケジューラの管理外なので差し替える。
            ioDispatcher = StandardTestDispatcher(scope.testScheduler),
            writeMutex = writeMutex
        )
    }

    private class RecordingWriter : ReadingTraceBackupStateWriter {
        var value: ReadingTraceBackupState = ReadingTraceBackupState.Idle
            private set

        override val current: ReadingTraceBackupState get() = value

        override fun set(state: ReadingTraceBackupState) {
            value = state
        }
    }
}

private const val VAULT = "content://vault"

private fun trace(path: String) = ReadingTrace(
    vaultRelativePath = path,
    noteTitle = path.substringAfterLast('/'),
    documentId = null,
    visits = listOf(ReadingVisit(1_000L, null, 50)),
    totalVisitCount = 1
)

private fun reflection(
    remark: String,
    remarkedAt: Long,
    reply: String? = null,
    repliedAt: Long? = null
) = Reflection(remark, remarkedAt, reply, repliedAt)

private class FakeBackupPersistence : ReadingTracePersistence {
    private val traces = mutableMapOf<String, ReadingTrace>()
    val corruptKeys = mutableSetOf<String>()
    val unwritablePaths = mutableSetOf<String>()
    var listingUnavailable = false
    var saveCount = 0
        private set

    /** 読み出しの直前に差し込むフック（走行中のVault切替・中断を作る）。 */
    var beforeLoad: (() -> Unit)? = null

    /** 書き込みの直後に差し込むフック。 */
    var afterSave: (() -> Unit)? = null

    fun put(trace: ReadingTrace) {
        traces[ReadingTraceStore.keyFor(trace.vaultRelativePath)] = trace
    }

    fun stored(path: String): ReadingTrace? = traces[ReadingTraceStore.keyFor(path)]

    override fun folderStatus() = ReadingTraceFolderStatus.Ready

    override fun load(vaultRelativePath: String, vaultKey: String) =
        loadByKey(ReadingTraceStore.keyFor(vaultRelativePath), vaultKey)

    override fun save(trace: ReadingTrace, vaultKey: String): ReadingTraceSaveResult {
        if (vaultKey != VAULT) return ReadingTraceSaveResult.Failure("Vault が違います")
        if (trace.vaultRelativePath in unwritablePaths) {
            return ReadingTraceSaveResult.Failure("書き込めませんでした")
        }
        put(trace)
        saveCount++
        afterSave?.invoke()
        return ReadingTraceSaveResult.Success
    }

    override fun listKeys(vaultKey: String): ReadingTraceKeyListing = when {
        listingUnavailable -> ReadingTraceKeyListing.Unavailable("読み取れませんでした")
        vaultKey != VAULT -> ReadingTraceKeyListing.Unavailable("Vault が違います")
        else -> ReadingTraceKeyListing.Available(traces.keys.toSet())
    }

    override fun loadByKey(key: String, vaultKey: String): ReadingTraceReadResult {
        beforeLoad?.invoke()
        if (key in corruptKeys) return ReadingTraceReadResult.Corrupt("壊れています")
        return traces[key]?.let { ReadingTraceReadResult.Valid(it) } ?: ReadingTraceReadResult.None
    }

    override fun deleteByKey(key: String, vaultKey: String): Boolean = traces.remove(key) != null
}
