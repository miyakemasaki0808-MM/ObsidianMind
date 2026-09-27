package com.example.newproject

import com.example.newproject.ai.AiAvailability
import com.example.newproject.controller.CrystalController
import com.example.newproject.fakes.FakeAiClient
import com.example.newproject.fakes.InMemoryCrystalMaterials
import com.example.newproject.fakes.InMemoryCrystalStore
import com.example.newproject.model.CrystalMaterial
import com.example.newproject.model.CrystalMaterialLog
import com.example.newproject.model.NoteUiStateStore
import com.example.newproject.model.state.CrystalLogState
import java.time.ZoneOffset
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 結晶の Controller（→ `docs/dev/features/reflect_crystal.md` §10）。
 *
 * 保存の**書き込みの途中**で止める交錯は単一スレッドのスケジューラでは作れないので、
 * `CrystalSaveInterleavingTest` が実スレッドで見る。ここは生成側の両方向と、試行の数え方を見る。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CrystalControllerTest {

    @Test
    fun `条件がそろうと1回生成し、保存して一覧へ足す`() = runTest {
        val env = Env(this)
        env.seedOthers("b.md", "c.md")
        val controller = env.controller()

        controller.ensureLogLoaded()
        controller.onNoteShown("a.md", "A")
        advanceUntilIdle()

        assertEquals(1, env.ai.generateCalls)
        val stored = env.store.stored(VAULT_A).single()
        assertEquals("a.md", stored.sources.first().vaultRelativePath)
        assertEquals(2, stored.sources.size)
        val log = env.uiStore.value.crystalLog as CrystalLogState.Loaded
        assertEquals(listOf(stored.fileName), log.crystals.map { it.fileName })
        val materials = env.materials.load(VAULT_A)
        assertEquals(env.now, materials.lastAttemptAt)
        assertEquals("渡した候補すべての指紋を記録する", 3, materials.presented.size)
    }

    @Test
    fun `ノートを表示すると絞り込みのパスが入る`() = runTest {
        val env = Env(this)
        env.controller().onNoteShown("a.md", "A")
        assertEquals("a.md", env.uiStore.value.crystalNotePath)
    }

    // ── 取り消しの両方向 ─────────────────────────────────────────────────────

    @Test
    fun `門番を待っている間に切り替えると控えも試行もしない`() = runTest {
        val env = Env(this)
        env.seedOthers("b.md", "c.md")
        val dwell = CompletableDeferred<Unit>()
        env.dwell = { dwell.await() }
        val controller = env.controller()

        controller.onNoteShown("a.md", "A")
        runCurrent()
        controller.cancelAndClear()
        dwell.complete(Unit)
        advanceUntilIdle()

        assertEquals(0, env.ai.generateCalls)
        assertTrue(env.materials.load(VAULT_A).entries.none { it.vaultRelativePath == "a.md" })
        assertNull(env.materials.load(VAULT_A).lastAttemptAt)
    }

    @Test
    fun `要約を待っている間に切り替えると控えも試行もしない`() = runTest {
        val env = Env(this)
        env.seedOthers("b.md", "c.md")
        val summary = CompletableDeferred<String?>()
        env.summary = { summary.await() }
        val controller = env.controller()

        controller.onNoteShown("a.md", "A")
        runCurrent()
        controller.cancelAndClear()
        summary.complete("要約。")
        advanceUntilIdle()

        assertEquals(0, env.ai.generateCalls)
        assertTrue(env.materials.load(VAULT_A).entries.none { it.vaultRelativePath == "a.md" })
    }

    @Test
    fun `生成中に切り替えると書かず、試行にも数えない`() = runTest {
        val env = Env(this, ai = FakeAiClient.deferred())
        env.seedOthers("b.md", "c.md")
        val controller = env.controller()

        controller.onNoteShown("a.md", "A")
        advanceUntilIdle()
        assertEquals(1, env.ai.generateCalls)
        controller.cancelAndClear()
        env.ai.completeAll(ANSWER)
        advanceUntilIdle()

        assertEquals(0, env.store.appendCalls)
        assertNull("キャンセルは試行に数えない", env.materials.load(VAULT_A).lastAttemptAt)
        assertTrue(env.materials.load(VAULT_A).presented.isEmpty())
    }

    @Test
    fun `生成中にVaultを切り替えると新しいVaultに書かない`() = runTest {
        val env = Env(this, ai = FakeAiClient.deferred())
        env.seedOthers("b.md", "c.md")
        val controller = env.controller()

        controller.onNoteShown("a.md", "A")
        advanceUntilIdle()
        env.switchVault(controller, VAULT_B)
        env.ai.completeAll(ANSWER)
        advanceUntilIdle()

        assertEquals(0, env.store.appendCalls)
        assertTrue(env.store.stored(VAULT_B).isEmpty())
    }

    // ── 試行の数え方（判断5）────────────────────────────────────────────────

    @Test
    fun `失敗・空振り・不正はどれも試行に数え、何も書かない`() = runTest {
        val outcomes = listOf(
            FakeAiClient.failingGeneration { IllegalStateException("生成に失敗") },
            FakeAiClient.returning("NONE"),
            FakeAiClient.returning("でたらめな応答")
        )
        outcomes.forEach { ai ->
            val env = Env(this, ai = ai)
            env.seedOthers("b.md", "c.md")
            env.controller().onNoteShown("a.md", "A")
            advanceUntilIdle()

            assertEquals(0, env.store.appendCalls)
            assertEquals(env.now, env.materials.load(VAULT_A).lastAttemptAt)
            assertEquals(3, env.materials.load(VAULT_A).presented.size)
        }
    }

    @Test
    fun `Readyでなければ生成も記録もせず、ダウンロードも始めない`() = runTest {
        val env = Env(this, ai = FakeAiClient.returning(ANSWER, availability = AiAvailability.NeedsDownload))
        env.seedOthers("b.md", "c.md")
        env.controller().onNoteShown("a.md", "A")
        advanceUntilIdle()

        assertEquals(0, env.ai.generateCalls)
        assertEquals(0, env.ai.downloadCalls)
        assertNull(env.materials.load(VAULT_A).lastAttemptAt)
        assertTrue("材料は控える", env.materials.load(VAULT_A).entries.any { it.vaultRelativePath == "a.md" })
    }

    @Test
    fun `要約が無ければ控えない`() = runTest {
        val env = Env(this)
        env.summary = { null }
        env.controller().onNoteShown("a.md", "A")
        advanceUntilIdle()
        assertTrue(env.materials.load(VAULT_A).entries.isEmpty())
    }

    @Test
    fun `同じ日の2回目は試さない`() = runTest {
        val env = Env(this)
        env.seedOthers("b.md", "c.md", "d.md", "e.md")
        val controller = env.controller()
        controller.onNoteShown("a.md", "A")
        advanceUntilIdle()
        env.now += 60_000
        controller.onNoteShown("f.md", "F")
        advanceUntilIdle()
        assertEquals(1, env.ai.generateCalls)
    }

    @Test
    fun `再会カードの照合が終わるまで生成しない`() = runTest {
        val env = Env(this)
        env.seedOthers("b.md", "c.md")
        val reunion = CompletableDeferred<Unit>()
        env.reunion = { reunion.await() }
        val controller = env.controller()

        controller.onNoteShown("a.md", "A")
        advanceUntilIdle()
        assertEquals(0, env.ai.generateCalls)
        assertTrue("材料を控えるのは待たない", env.materials.load(VAULT_A).entries.any { it.vaultRelativePath == "a.md" })

        reunion.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, env.ai.generateCalls)
    }

    // ── 一覧 ────────────────────────────────────────────────────────────────

    @Test
    fun `一覧が読めなければ未読込へ戻し、次に読み直す`() = runTest {
        val env = Env(this)
        env.store.unavailable = true
        val controller = env.controller()
        controller.ensureLogLoaded()
        advanceUntilIdle()
        assertEquals(CrystalLogState.NotLoaded, env.uiStore.value.crystalLog)

        env.store.unavailable = false
        controller.ensureLogLoaded()
        advanceUntilIdle()
        assertEquals(CrystalLogState.Loaded(emptyList()), env.uiStore.value.crystalLog)
    }

    @Test
    fun `保存が失敗しても一覧に足さず、走行中の印も残らない`() = runTest {
        val env = Env(this)
        env.seedOthers("b.md", "c.md")
        env.store.failAppend = true
        val controller = env.controller()
        controller.ensureLogLoaded()
        controller.onNoteShown("a.md", "A")
        advanceUntilIdle()
        assertEquals(1, env.store.appendCalls)
        assertEquals(CrystalLogState.Loaded(emptyList()), env.uiStore.value.crystalLog)

        // 走行中の保存の数が残っていると、次の読み込みがその Vault の保存を待ち続ける。
        env.switchVault(controller, VAULT_A)
        controller.ensureLogLoaded()
        advanceUntilIdle()
        assertEquals(CrystalLogState.Loaded(emptyList()), env.uiStore.value.crystalLog)
    }

    private class Env(val scope: TestScope, val ai: FakeAiClient = FakeAiClient.returning(ANSWER)) {
        val store = InMemoryCrystalStore()
        val materials = InMemoryCrystalMaterials()
        val uiStore = NoteUiStateStore()
        var vaultKey: String? = VAULT_A
        var generation = 0L
        var now = 1_000_000L
        var dwell: suspend () -> Unit = {}
        var summary: suspend () -> String? = { "今のノートの要約。" }
        var reunion: suspend () -> Unit = {}

        fun controller() = CrystalController(
            scope = scope,
            aiClient = ai,
            awaitDwell = { dwell() },
            awaitSummary = { summary() },
            awaitReunionSettled = { reunion() },
            state = uiStore.crystalWriter,
            store = store,
            materials = materials,
            currentVaultKey = { vaultKey },
            vaultGeneration = { generation },
            clock = { now },
            zone = ZoneOffset.UTC,
            ioDispatcher = StandardTestDispatcher(scope.testScheduler)
        )

        /** まだAIへ渡していない他のノートを控えておく。今のノートと合わせて3件になる。 */
        fun seedOthers(vararg paths: String) {
            materials.save(
                VAULT_A,
                CrystalMaterialLog(
                    entries = paths.mapIndexed { index, path ->
                        CrystalMaterial(path, path, "${path}の要約。", lastSeenAt = index.toLong())
                    }
                )
            )
        }

        /** Coordinator の Vault 切替と同じ順 — 世代を進め、Controller を止め、状態を落とす。 */
        fun switchVault(controller: CrystalController, next: String) {
            generation++
            vaultKey = next
            controller.clearVaultScoped()
            uiStore.resetVaultScoped()
        }
    }

    private companion object {
        const val VAULT_A = "vault-a"
        const val VAULT_B = "vault-b"
        const val ANSWER = "選択: N1, N2\n結晶: どちらも習慣を小さく始める工夫を扱っている。"
    }
}
