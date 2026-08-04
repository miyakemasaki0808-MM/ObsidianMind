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
import kotlinx.coroutines.test.StandardTestDispatcher
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

    /**
     * 走行中にキャンセルされたら、状態を書き換えずに終わる。
     *
     * `CancellationException` を一般エラーへ畳むと**偽のエラー表示**が出る。しかも
     * `state.set` は suspend しないので、キャンセル後も素通りして書き込まれてしまう
     * （→ CLAUDE.md「CancellationException は握りつぶさず再throwする」）。
     */
    @Test
    fun `a cancelled assessment does not write an error state`() = runTest {
        val env = Env(this)
        env.persistence.put(trace("ideas/a.md"))
        env.vault.handle!!.vaultScan = VaultScan(emptyList())
        // 走査から戻る途中で Vault が切り替わり、走行中のJobがキャンセルされる。
        env.vault.handle!!.beforeEachCall = { env.controller.onVaultChanged() }

        env.controller.assess()
        advanceUntilIdle()

        assertTrue(
            "キャンセルがエラーとして表示された: ${env.state.value}",
            env.state.value is ReadingTraceCleanupState.Loading
        )
    }

    // --- 削除（段階4）-----------------------------------------------------------

    @Test
    fun `deletes only the requested candidate`() = runTest {
        val env = Env(this)
        env.persistence.put(trace("ideas/a.md"))
        env.persistence.put(trace("journal/b.md"))
        env.vault.handle!!.vaultScan = VaultScan(emptyList())
        env.controller.assess()
        advanceUntilIdle()

        env.controller.delete(ReadingTraceStore.keyFor("ideas/a.md"))
        advanceUntilIdle()

        val success = env.state.value as ReadingTraceCleanupState.Success
        assertEquals(listOf("journal/b.md"), success.orphans.map { it.vaultRelativePath })
        assertTrue(env.persistence.stored(ReadingTraceStore.keyFor("journal/b.md")))
    }

    // SAFプロバイダは削除に失敗し得る。消えたことにして一覧から外すと再試行できない。
    @Test
    fun `keeps a candidate that could not be deleted and reports the failure`() = runTest {
        val env = Env(this)
        env.persistence.put(trace("ideas/a.md"))
        env.vault.handle!!.vaultScan = VaultScan(emptyList())
        env.controller.assess()
        advanceUntilIdle()
        env.persistence.undeletableKeys += ReadingTraceStore.keyFor("ideas/a.md")

        env.controller.delete(ReadingTraceStore.keyFor("ideas/a.md"))
        advanceUntilIdle()

        val success = env.state.value as ReadingTraceCleanupState.Success
        assertEquals(listOf("ideas/a.md"), success.orphans.map { it.vaultRelativePath })
        assertEquals(1, success.deleteFailureCount)
    }

    // 保留した分は候補一覧に入らないので、削除要求を出しても消えない。
    @Test
    fun `withheld traces cannot be deleted even if their key is passed`() = runTest {
        val env = Env(this)
        env.persistence.put(trace("ideas/a.md"))
        env.persistence.put(trace("ideas/b.md"))
        env.vault.handle!!.vaultScan = VaultScan(emptyList())
        env.controller.assess()
        advanceUntilIdle()

        env.controller.delete(ReadingTraceStore.keyFor("ideas/a.md"))
        advanceUntilIdle()

        // ideas/ の2件はフォルダ一括欠落で保留されており、候補ではない。
        assertTrue(env.persistence.stored(ReadingTraceStore.keyFor("ideas/a.md")))
        assertTrue(env.persistence.stored(ReadingTraceStore.keyFor("ideas/b.md")))
    }

    /**
     * 洗い出しと削除のあいだに同期が完了し、ノートが現れたら削除しない。
     * 判定結果は時間が経つほど古くなるので、消す瞬間に確かめ直す。
     */
    @Test
    fun `does not delete a trace whose note reappeared before the tap`() = runTest {
        val env = Env(this)
        env.persistence.put(trace("ideas/a.md"))
        env.vault.handle!!.vaultScan = VaultScan(emptyList())
        env.controller.assess()
        advanceUntilIdle()

        // 同期が終わってノートが現れた。
        env.vault.handle!!.vaultScan = VaultScan(listOf(note("ideas/a.md")))
        env.controller.delete(ReadingTraceStore.keyFor("ideas/a.md"))
        advanceUntilIdle()

        assertTrue(env.persistence.stored(ReadingTraceStore.keyFor("ideas/a.md")))
        // もう孤児ではないので候補からは外す。
        assertTrue((env.state.value as ReadingTraceCleanupState.Success).orphans.isEmpty())
    }

    // 削除直前の再走査でそのフォルダが読めなくなっていたら、消さない。
    @Test
    fun `does not delete when the folder became unreadable before the tap`() = runTest {
        val env = Env(this)
        env.persistence.put(trace("ideas/a.md"))
        env.vault.handle!!.vaultScan = VaultScan(emptyList())
        env.controller.assess()
        advanceUntilIdle()

        env.vault.handle!!.vaultScan = VaultScan(emptyList(), unreadableFolderPaths = setOf("ideas"))
        env.controller.delete(ReadingTraceStore.keyFor("ideas/a.md"))
        advanceUntilIdle()

        assertTrue(env.persistence.stored(ReadingTraceStore.keyFor("ideas/a.md")))
    }

    // **ルートが読めないときに、ネストした候補を消してしまう経路があった。**
    // `isUnderUnreadableFolder("ideas", setOf(""))` が偽を返し、削除直前だけ
    // 安全規則がすり抜けていた（洗い出し側は同じ状況を止められていた）。
    @Test
    fun `does not delete a nested candidate when the vault root became unreadable`() = runTest {
        val env = Env(this)
        env.persistence.put(trace("ideas/a.md"))
        env.vault.handle!!.vaultScan = VaultScan(emptyList())
        env.controller.assess()
        advanceUntilIdle()

        env.vault.handle!!.vaultScan = VaultScan(emptyList(), unreadableFolderPaths = setOf(""))
        env.controller.delete(ReadingTraceStore.keyFor("ideas/a.md"))
        advanceUntilIdle()

        assertTrue(env.persistence.stored(ReadingTraceStore.keyFor("ideas/a.md")))
    }

    // 確かめられなかった候補は**一覧に残す**。消すと再試行できない。
    @Test
    fun `keeps an unverifiable candidate in the list and reports it apart from failures`() = runTest {
        val env = Env(this)
        env.persistence.put(trace("ideas/a.md"))
        env.vault.handle!!.vaultScan = VaultScan(emptyList())
        env.controller.assess()
        advanceUntilIdle()

        env.vault.handle!!.vaultScan = VaultScan(emptyList(), unreadableFolderPaths = setOf("ideas"))
        env.controller.delete(ReadingTraceStore.keyFor("ideas/a.md"))
        advanceUntilIdle()

        val success = env.state.value as ReadingTraceCleanupState.Success
        assertEquals(listOf("ideas/a.md"), success.orphans.map { it.vaultRelativePath })
        // 「消せなかった」ではなく「確かめられなかった」として数える。
        assertEquals(1, success.unverifiedCount)
        assertEquals(0, success.deleteFailureCount)
    }

    // 生き返っていた場合だけ、消さずに候補から外す（確認できた結果なので残す必要がない）。
    @Test
    fun `removes a candidate from the list only when the note actually reappeared`() = runTest {
        val env = Env(this)
        env.persistence.put(trace("ideas/a.md"))
        env.vault.handle!!.vaultScan = VaultScan(emptyList())
        env.controller.assess()
        advanceUntilIdle()

        env.vault.handle!!.vaultScan = VaultScan(listOf(noteFile("a.md").copy(vaultRelativePath = "ideas/a.md")))
        env.controller.delete(ReadingTraceStore.keyFor("ideas/a.md"))
        advanceUntilIdle()

        val success = env.state.value as ReadingTraceCleanupState.Success
        assertTrue(success.orphans.isEmpty())
        assertEquals(0, success.unverifiedCount)
        assertTrue(env.persistence.stored(ReadingTraceStore.keyFor("ideas/a.md")))
    }

    // 画面に出ていないキーを渡されても消さない（一覧に固定する規律）。
    @Test
    fun `ignores keys that are not in the displayed candidate list`() = runTest {
        val env = Env(this)
        env.persistence.put(trace("ideas/a.md"))
        env.persistence.put(trace("journal/b.md"))
        env.vault.handle!!.vaultScan = VaultScan(listOf(note("journal/b.md")))
        env.controller.assess()
        advanceUntilIdle()

        env.controller.delete(ReadingTraceStore.keyFor("journal/b.md"))
        advanceUntilIdle()

        // journal/b.md は現存ノートなので候補ではない。消えてはいけない。
        assertTrue(env.persistence.stored(ReadingTraceStore.keyFor("journal/b.md")))
    }

    /**
     * 洗い出しと削除のあいだに Vault が切り替わったら削除しない。
     *
     * **キーは相対パスのハッシュなので、別のVaultに同じ相対パスのノートがあれば
     * キーも一致する。** 削除時に現在のVaultを読み直すと、旧Vaultの候補キーで
     * 新Vaultの**生きている痕跡**を消してしまう。
     */
    @Test
    fun `does not delete when the vault changed since the assessment`() = runTest {
        val env = Env(this)
        env.persistence.put(trace("ideas/a.md"))
        env.vault.handle!!.vaultScan = VaultScan(emptyList())
        env.controller.assess()
        advanceUntilIdle()
        val before = env.state.value

        env.vaultKey = "content://another-vault"
        env.controller.delete(ReadingTraceStore.keyFor("ideas/a.md"))
        advanceUntilIdle()

        assertTrue(env.persistence.stored(ReadingTraceStore.keyFor("ideas/a.md")))
        assertEquals(before, env.state.value)
    }

    private class Env(scope: kotlinx.coroutines.test.TestScope) {
        val vault = FakeVaultBrowser()
        val persistence = FakeCleanupPersistence()
        var generation = 0L
        var vaultKey: String? = VAULT
        val state = RecordingWriter()
        val controller = ReadingTraceCleanupController(
            scope = scope,
            vault = vault,
            persistence = persistence,
            state = state,
            currentVaultKey = { vaultKey },
            vaultGeneration = { generation },
            limits = OrphanLimits(),
            // Dispatchers.IO はテストスケジューラの管理外なので差し替える
            // （既存の ReadingTraceController テストと同じ形）。
            ioDispatcher = StandardTestDispatcher(scope.testScheduler)
        )
    }

    private class RecordingWriter : ReadingTraceCleanupStateWriter {
        var value: ReadingTraceCleanupState = ReadingTraceCleanupState.Idle
            private set

        override val current: ReadingTraceCleanupState get() = value

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

    /** 削除に失敗させるキー。SAFプロバイダが消せない状況を作る。 */
    val undeletableKeys = mutableSetOf<String>()

    fun stored(key: String): Boolean = traces.containsKey(key)

    override fun deleteByKey(key: String, vaultKey: String): Boolean {
        if (vaultKey != VAULT) return false
        if (key in undeletableKeys) return false
        return traces.remove(key) != null
    }
}
