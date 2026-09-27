package com.example.newproject

import com.example.newproject.controller.CrystalController
import com.example.newproject.fakes.FakeAiClient
import com.example.newproject.fakes.InMemoryCrystalMaterials
import com.example.newproject.fakes.InMemoryCrystalStore
import com.example.newproject.model.CrystalMaterial
import com.example.newproject.model.CrystalMaterialLog
import com.example.newproject.model.NoteUiStateStore
import com.example.newproject.model.state.CrystalLogState
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **保存の書き込みの途中で止める**（→ `docs/dev/features/reflect_crystal.md` 判断9）。
 *
 * SAF の書き込みはブロッキングI/Oで、取消を付けても書き終わりうる。この交錯は単一スレッドの
 * スケジューラでは作れないので、画面側と I/O 側を別スレッドにし、偽の置き場を書き込みの中で
 * ラッチに待たせる（→ `docs/dev/lessons/L49.md`）。
 */
class CrystalSaveInterleavingTest {

    private val mainExecutor = Executors.newSingleThreadExecutor()
    private val main: ExecutorCoroutineDispatcher = mainExecutor.asCoroutineDispatcher()
    private val io: ExecutorCoroutineDispatcher = Executors.newFixedThreadPool(2).asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + main)

    private val store = InMemoryCrystalStore()
    private val materials = InMemoryCrystalMaterials()
    private val uiStore = NoteUiStateStore()

    @Volatile private var vaultKey: String = VAULT_A
    @Volatile private var generation = 0L

    private val controller = CrystalController(
        scope = scope,
        aiClient = FakeAiClient.returning(ANSWER),
        awaitDwell = {},
        awaitSummary = { "今のノートの要約。" },
        awaitReunionSettled = {},
        state = uiStore.crystalWriter,
        store = store,
        materials = materials,
        currentVaultKey = { vaultKey },
        vaultGeneration = { generation },
        clock = { 1_000_000L },
        zone = ZoneOffset.UTC,
        ioDispatcher = io
    )

    private val writeStarted = CountDownLatch(1)
    private val writeRelease = CountDownLatch(1)

    @After
    fun tearDown() {
        writeRelease.countDown()
        scope.cancel()
        main.close()
        io.close()
    }

    @Test
    fun `同じVaultでノートを切り替えてから書き終えると、再起動なしで一覧に1回だけ出る`() {
        seedOthers()
        holdWrite()
        onMain { controller.ensureLogLoaded() }
        awaitLog { it is CrystalLogState.Loaded }

        onMain { controller.onNoteShown("a.md", "A") }
        assertTrue("書き込みが始まらない", writeStarted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        onMain { controller.cancelAndClear() }
        writeRelease.countDown()

        val log = awaitLog { it is CrystalLogState.Loaded && it.crystals.isNotEmpty() } as CrystalLogState.Loaded
        onMain { }
        assertEquals(1, (uiStore.value.crystalLog as CrystalLogState.Loaded).crystals.size)
        assertEquals(store.stored(VAULT_A).map { it.fileName }, log.crystals.map { it.fileName })
    }

    @Test
    fun `書き込み中にVaultをBへ切り替えると、Bには書かずBの一覧にも出ない`() {
        seedOthers()
        holdWrite()
        onMain { controller.ensureLogLoaded() }
        awaitLog { it is CrystalLogState.Loaded }

        onMain { controller.onNoteShown("a.md", "A") }
        assertTrue(writeStarted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        onMain { switchVault(VAULT_B) }
        onMain { controller.ensureLogLoaded() }
        awaitLog { it is CrystalLogState.Loaded }
        writeRelease.countDown()

        awaitCondition { store.stored(VAULT_A).size == 1 }
        onMain { }
        assertTrue(store.stored(VAULT_B).isEmpty())
        assertEquals(CrystalLogState.Loaded(emptyList()), uiStore.value.crystalLog)
    }

    @Test
    fun `書き込み中にA→B→Aと切り替えると、Aへ戻った一覧は実ファイルと一致する`() {
        seedOthers()
        holdWrite()
        onMain { controller.ensureLogLoaded() }
        awaitLog { it is CrystalLogState.Loaded }

        onMain { controller.onNoteShown("a.md", "A") }
        assertTrue(writeStarted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        val listedBefore = store.readAllCalls
        onMain {
            switchVault(VAULT_B)
            switchVault(VAULT_A)
            // Aの読み込みは、Aへ走っている保存が終わるのを待ってから列挙する。
            controller.ensureLogLoaded()
        }
        // **待たずに列挙する実装は、ここで列挙を始める。** 始まったら落とす（時間で打ち切る否定の確認）。
        assertTrue(
            "Aへの保存が終わる前に、Aの一覧を列挙した",
            !happensWithin(NEGATIVE_WAIT_MILLIS) { store.readAllCalls > listedBefore }
        )
        assertEquals(CrystalLogState.Loading, uiStore.value.crystalLog)
        writeRelease.countDown()

        val log = awaitLog { it is CrystalLogState.Loaded } as CrystalLogState.Loaded
        onMain { }
        assertEquals(store.stored(VAULT_A).map { it.fileName }, log.crystals.map { it.fileName })
        assertEquals(1, (uiStore.value.crystalLog as CrystalLogState.Loaded).crystals.size)
    }

    @Test
    fun `書き込みが失敗したら一覧に架空の1件を足さない`() {
        seedOthers()
        holdWrite()
        store.failAppend = true
        onMain { controller.ensureLogLoaded() }
        awaitLog { it is CrystalLogState.Loaded }

        onMain { controller.onNoteShown("a.md", "A") }
        assertTrue(writeStarted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        onMain { controller.cancelAndClear() }
        writeRelease.countDown()

        // 保存が終わった後の読み込みが待たされずに進む＝走行中の保存の数が戻っている。
        onMain { switchVault(VAULT_A); controller.ensureLogLoaded() }
        awaitLog { it is CrystalLogState.Loaded }
        assertEquals(CrystalLogState.Loaded(emptyList()), uiStore.value.crystalLog)
    }

    @Test
    fun `一覧の読み込み中に保存が終わっても、読み込みの完了後に1回だけ出る`() {
        seedOthers()
        val listed = CountDownLatch(1)
        val listRelease = CountDownLatch(1)
        store.afterSnapshot = {
            listed.countDown()
            listRelease.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }
        onMain { controller.ensureLogLoaded() }
        assertTrue("列挙が始まらない", listed.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))

        onMain { controller.onNoteShown("a.md", "A") }
        awaitCondition { store.stored(VAULT_A).size == 1 }
        onMain { }
        assertEquals(CrystalLogState.Loading, uiStore.value.crystalLog)
        listRelease.countDown()

        val log = awaitLog { it is CrystalLogState.Loaded } as CrystalLogState.Loaded
        assertEquals(store.stored(VAULT_A).map { it.fileName }, log.crystals.map { it.fileName })
    }

    private fun holdWrite() {
        store.beforeAppend = {
            writeStarted.countDown()
            writeRelease.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }
    }

    private fun seedOthers() {
        materials.save(
            VAULT_A,
            CrystalMaterialLog(
                entries = listOf(
                    CrystalMaterial("b.md", "B", "Bの要約。", lastSeenAt = 1),
                    CrystalMaterial("c.md", "C", "Cの要約。", lastSeenAt = 2)
                )
            )
        )
    }

    /** Coordinator の Vault 切替と同じ順。**画面側のスレッドで呼ぶ。** */
    private fun switchVault(next: String) {
        generation++
        vaultKey = next
        controller.clearVaultScoped()
        uiStore.resetVaultScoped()
    }

    /** 画面側のスレッドで実行し、終わるまで待つ。空のブロックは、先に積まれた処理を流し切る合図に使う。 */
    private fun onMain(block: () -> Unit) = runBlocking(main) { block() }

    private fun awaitLog(predicate: (CrystalLogState) -> Boolean): CrystalLogState = runBlocking {
        withTimeout(TIMEOUT_SECONDS * 1_000) { uiStore.uiState.first { predicate(it.crystalLog) }.crystalLog }
    }

    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS)
        while (!condition()) {
            check(System.nanoTime() < deadline) { "条件が時間内に満たされない" }
            Thread.sleep(5)
        }
    }

    /** [condition] が [millis] 以内に成り立つか。**成り立たないことを確かめる側**で使う。 */
    private fun happensWithin(millis: Long, condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis)
        while (System.nanoTime() < deadline) {
            if (condition()) return true
            Thread.sleep(5)
        }
        return condition()
    }

    private companion object {
        /** 否定の確認で待つ時間。待たない実装は数ミリ秒で列挙を始めるので、これで足りる。 */
        const val NEGATIVE_WAIT_MILLIS = 300L
        const val VAULT_A = "vault-a"
        const val VAULT_B = "vault-b"
        const val TIMEOUT_SECONDS = 5L
        const val ANSWER = "選択: N1, N2\n結晶: どちらも習慣を小さく始める工夫を扱っている。"
    }
}
