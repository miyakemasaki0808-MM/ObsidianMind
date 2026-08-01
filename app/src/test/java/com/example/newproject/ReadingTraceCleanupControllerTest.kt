package com.example.newproject

import com.example.newproject.controller.ReadingTraceCleanupController
import com.example.newproject.data.ReadingTraceFolderStatus
import com.example.newproject.data.ReadingTraceKeyListing
import com.example.newproject.data.ReadingTracePersistence
import com.example.newproject.data.ReadingTraceReadResult
import com.example.newproject.data.ReadingTraceSaveResult
import com.example.newproject.data.ReadingTraceStore
import com.example.newproject.model.DocumentRef
import com.example.newproject.model.NoteFile
import com.example.newproject.model.OrphanBlockReason
import com.example.newproject.model.OrphanLimits
import com.example.newproject.model.OrphanWithholdReason
import com.example.newproject.model.ReadingTrace
import com.example.newproject.model.ReadingTraceCleanupStateWriter
import com.example.newproject.model.ReadingVisit
import com.example.newproject.model.VaultScan
import com.example.newproject.model.state.ReadingTraceCleanupState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 整理画面の洗い出しを固定する。
 *
 * 判定そのものは `ReadingTraceOrphansTest` が持つので、ここで見るのは**結線**:
 * 列挙できなかったときに候補ゼロへ落とさないこと、Vault切替で旧Vaultの候補が
 * 新Vaultの画面へ出ないこと、そして**現存ノートの痕跡ファイルを開かない**こと。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReadingTraceCleanupControllerTest {

    @Test
    fun `offers traces whose notes are gone`() = runTest {
        val env = Env(this)
        env.persistence.put(trace("ideas/habit.md"))
        env.persistence.put(trace("journal/2026.md"))
        env.vault.handle!!.vaultScan = VaultScan(listOf(note("journal/2026.md")))

        env.controller.assess()
        advanceUntilIdle()

        val success = env.state.value as ReadingTraceCleanupState.Success
        assertEquals(listOf("ideas/habit.md"), success.orphans.map { it.vaultRelativePath })
    }

    // 集合差で落ちる分は開かない。遠いプロバイダでは読み込み回数がそのまま体感になる。
    @Test
    fun `does not open trace files for notes that still exist`() = runTest {
        val env = Env(this)
        env.persistence.put(trace("ideas/habit.md"))
        env.vault.handle!!.vaultScan = VaultScan(listOf(note("ideas/habit.md")))

        env.controller.assess()
        advanceUntilIdle()

        assertEquals(0, env.persistence.loadByKeyCount)
    }

    // 列挙の失敗を候補ゼロへ落とすと、逆に「掃除するものが無い」と見える。
    @Test
    fun `reports an error instead of an empty result when the listing fails`() = runTest {
        val env = Env(this)
        env.persistence.put(trace("ideas/habit.md"))
        env.persistence.listingUnavailable = true

        env.controller.assess()
        advanceUntilIdle()

        assertTrue(env.state.value is ReadingTraceCleanupState.Error)
    }

    @Test
    fun `blocks when the vault root could not be listed`() = runTest {
        val env = Env(this)
        env.persistence.put(trace("ideas/habit.md"))
        // ルートが読めていない走査。全ノートが不在に見える。
        env.vault.handle!!.vaultScan = VaultScan(emptyList(), unreadableFolderPaths = setOf(""))

        env.controller.assess()
        advanceUntilIdle()

        assertEquals(
            OrphanBlockReason.VAULT_ROOT_UNREADABLE,
            (env.state.value as ReadingTraceCleanupState.Blocked).reason
        )
    }

    @Test
    fun `withholds a folder whose notes all went missing at once`() = runTest {
        val env = Env(this)
        env.persistence.put(trace("ideas/a.md"))
        env.persistence.put(trace("ideas/b.md"))
        env.vault.handle!!.vaultScan = VaultScan(emptyList())

        env.controller.assess()
        advanceUntilIdle()

        val success = env.state.value as ReadingTraceCleanupState.Success
        assertTrue(success.orphans.isEmpty())
        assertEquals(OrphanWithholdReason.FOLDER_WIDE_ABSENCE, success.withheld.single().reason)
    }

    @Test
    fun `withholds a candidate whose trace file is corrupt`() = runTest {
        val env = Env(this)
        env.persistence.put(trace("ideas/habit.md"))
        env.persistence.corruptKeys += ReadingTraceStore.keyFor("ideas/habit.md")
        env.vault.handle!!.vaultScan = VaultScan(emptyList())

        env.controller.assess()
        advanceUntilIdle()

        val success = env.state.value as ReadingTraceCleanupState.Success
        assertTrue(success.orphans.isEmpty())
        assertEquals(OrphanWithholdReason.UNRESOLVABLE, success.withheld.single().reason)
    }

    // 切替直後に「別Vaultのノートを消しませんか」と尋ねないための世代照合。
    @Test
    fun `drops a result that arrives after the vault changed`() = runTest {
        val env = Env(this)
        env.persistence.put(trace("ideas/habit.md"))
        env.vault.handle!!.vaultScan = VaultScan(emptyList())
        // 走査から戻る直前にVaultが切り替わる状況を作る。
        env.vault.handle!!.beforeEachCall = { env.generation++ }

        env.controller.assess()
        advanceUntilIdle()

        // Loading のまま。旧Vaultの候補で上書きされない。
        assertTrue(env.state.value is ReadingTraceCleanupState.Loading)
    }

    @Test
    fun `reports an error when no vault is selected`() = runTest {
        val env = Env(this)
        env.vault.handle = null

        env.controller.assess()
        advanceUntilIdle()

        assertTrue(env.state.value is ReadingTraceCleanupState.Error)
    }

    private class Env(scope: kotlinx.coroutines.test.TestScope) {
        val vault = FakeVaultBrowser()
        val persistence = FakeCleanupPersistence()
        var generation = 0L
        val state = RecordingWriter()
        val controller = ReadingTraceCleanupController(
            scope = scope,
            vault = vault,
            persistence = persistence,
            state = state,
            currentVaultKey = { VAULT },
            vaultGeneration = { generation },
            limits = OrphanLimits()
        )
    }

    private class RecordingWriter : ReadingTraceCleanupStateWriter {
        var value: ReadingTraceCleanupState = ReadingTraceCleanupState.Idle
            private set

        override fun set(state: ReadingTraceCleanupState) {
            value = state
        }
    }
}

private const val VAULT = "content://vault"

private fun trace(path: String) = ReadingTrace(
    vaultRelativePath = path,
    noteTitle = path.substringAfterLast('/'),
    documentId = null,
    visits = listOf(ReadingVisit(1_000L, null, 50))
)

private fun note(path: String) = NoteFile(
    name = path.substringAfterLast('/'),
    ref = DocumentRef("content://doc/$path"),
    vaultRelativePath = path
)

private class FakeCleanupPersistence : ReadingTracePersistence {
    private val traces = mutableMapOf<String, ReadingTrace>()
    val corruptKeys = mutableSetOf<String>()
    var listingUnavailable = false
    var loadByKeyCount = 0
        private set

    fun put(trace: ReadingTrace) {
        traces[ReadingTraceStore.keyFor(trace.vaultRelativePath)] = trace
    }

    override fun folderStatus() = ReadingTraceFolderStatus.Ready

    override fun load(vaultRelativePath: String, vaultKey: String) =
        loadByKey(ReadingTraceStore.keyFor(vaultRelativePath), vaultKey)

    override fun save(trace: ReadingTrace, vaultKey: String): ReadingTraceSaveResult {
        put(trace)
        return ReadingTraceSaveResult.Success
    }

    override fun listKeys(vaultKey: String): ReadingTraceKeyListing = when {
        listingUnavailable -> ReadingTraceKeyListing.Unavailable("読み取れませんでした")
        vaultKey != VAULT -> ReadingTraceKeyListing.Unavailable("Vault が違います")
        else -> ReadingTraceKeyListing.Available(traces.keys.toSet())
    }

    override fun loadByKey(key: String, vaultKey: String): ReadingTraceReadResult {
        loadByKeyCount++
        if (key in corruptKeys) return ReadingTraceReadResult.Corrupt("壊れています")
        return traces[key]?.let { ReadingTraceReadResult.Valid(it) } ?: ReadingTraceReadResult.None
    }
}
