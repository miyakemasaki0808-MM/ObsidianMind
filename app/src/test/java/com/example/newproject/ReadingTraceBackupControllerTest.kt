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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
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
    fun `端末側の返事が置き換わる件数を下見で数える`() = runTest {
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

        val plan = (env.state.value as ReadingTraceBackupState.Planned).plan
        assertEquals(1, plan.localReplyReplaced)
        assertEquals(0, plan.importedReplyDropped)
    }

    /**
     * **通常の往復では失われるのは退避側。** 書き出したあとに返事を書き足すと
     * 端末側が新しくなるので、規則どおり端末側が残る。ここを1つの件数へまとめると
     * 「あなたの返事が置き換わります」という逆の告知になる。
     */
    @Test
    fun `退避側の返事が使われない件数を下見で分けて数える`() = runTest {
        val env = Env(this)
        env.persistence.put(
            trace("ideas/habit.md").copy(reflection = reflection("問い", 900L, "端末側の新しい返事", 1_000L))
        )
        val backup = ReadingTraceBackupJson.encode(
            listOf(trace("ideas/habit.md").copy(reflection = reflection("問い", 100L, "退避側の古い返事", 200L))),
            1_000L
        )

        env.controller.prepareImport { backup }
        advanceUntilIdle()

        val plan = (env.state.value as ReadingTraceBackupState.Planned).plan
        assertEquals(0, plan.localReplyReplaced)
        assertEquals(1, plan.importedReplyDropped)

        env.controller.applyImport()
        advanceUntilIdle()
        assertEquals(
            "端末側の新しい返事",
            env.persistence.stored("ideas/habit.md")?.reflection?.reply
        )
    }

    // ── 端末側を読み取れないとき ────────────────────────────────────────────

    /**
     * **「読めなかった」を「無い」へ畳まない。**
     *
     * 畳むと退避側を新規として丸ごと書き、**読めなかっただけの端末側の返事が
     * 警告も保留もなく消える**。SAF の一時的な読取失敗で成立し、書込み自体は成功するので
     * 保留にも残らない — この機能で最も見つけにくい壊れ方。
     */
    @Test
    fun `端末側を読み取れない痕跡は新規扱いにせず保留する`() = runTest {
        val env = Env(this)
        env.persistence.put(
            trace("ideas/habit.md").copy(reflection = reflection("問い", 100L, "端末側の返事", 200L))
        )
        env.persistence.unreadableKeys += ReadingTraceStore.keyFor("ideas/habit.md")
        val backup = ReadingTraceBackupJson.encode(
            listOf(trace("ideas/habit.md").copy(reflection = reflection("問い", 900L, "退避側の返事", 1_000L))),
            1_000L
        )

        env.controller.prepareImport { backup }
        advanceUntilIdle()

        val plan = (env.state.value as ReadingTraceBackupState.Planned).plan
        assertEquals("読めなかった痕跡を新規として数えた", 0, plan.added)
        assertEquals(
            listOf(ReadingTraceImportWithholdReason.LOCAL_UNREADABLE),
            plan.withheld.map { it.reason }
        )

        env.controller.applyImport()
        advanceUntilIdle()

        assertEquals("読めなかった痕跡を上書きした", 0, env.persistence.saveCount)
        assertEquals(
            "端末側の返事",
            env.persistence.stored("ideas/habit.md")?.reflection?.reply
        )
    }

    // 実在しない痕跡は従来どおり新規として受け入れる（畳まない＝何も足せない、ではない）。
    @Test
    fun `実在しない痕跡は従来どおり新規として受け入れる`() = runTest {
        val env = Env(this)
        val backup = ReadingTraceBackupJson.encode(listOf(trace("new/note.md")), 1_000L)

        env.controller.prepareImport { backup }
        advanceUntilIdle()
        env.controller.applyImport()
        advanceUntilIdle()

        assertEquals(1, (env.state.value as ReadingTraceBackupState.Imported).added)
        assertEquals("new/note.md", env.persistence.stored("new/note.md")?.vaultRelativePath)
    }

    // 置き場を列挙できないなら不在を根拠にできない。読み戻しそのものを始めない。
    @Test
    fun `置き場を列挙できないときは読み戻さない`() = runTest {
        val env = Env(this)
        val backup = ReadingTraceBackupJson.encode(listOf(trace("ideas/habit.md")), 1_000L)
        env.persistence.listingUnavailable = true

        env.controller.prepareImport { backup }
        advanceUntilIdle()

        assertTrue(env.state.value is ReadingTraceBackupState.Error)
        assertEquals(0, env.persistence.saveCount)
    }

    @Test
    fun `下見の後に端末側が読めなくなったら書き込まない`() = runTest {
        val env = Env(this)
        env.persistence.put(
            trace("ideas/habit.md").copy(reflection = reflection("問い", 100L, "端末側の返事", 200L))
        )
        val backup = ReadingTraceBackupJson.encode(
            listOf(trace("ideas/habit.md").copy(reflection = reflection("問い", 900L, "退避側の返事", 1_000L))),
            1_000L
        )
        env.controller.prepareImport { backup }
        advanceUntilIdle()

        env.persistence.unreadableKeys += ReadingTraceStore.keyFor("ideas/habit.md")
        env.controller.applyImport()
        advanceUntilIdle()

        assertEquals(0, env.persistence.saveCount)
        val revised = env.state.value as ReadingTraceBackupState.Planned
        assertEquals(
            listOf(ReadingTraceImportWithholdReason.LOCAL_UNREADABLE),
            revised.plan.withheld.map { it.reason }
        )
    }

    // ── 下見と確定のあいだの変化 ────────────────────────────────────────────

    /**
     * **不可逆な操作は、画面に出した内容だけを書く。**
     *
     * 「失われる返事はありません」と見せた後に端末側へ返事が付いた場合、
     * そのまま適用すると**利用者が承認していない損失**が起きる。
     * 最初の確定では1件も書かず、計画を作り直して二度目の確定を求める。
     */
    @Test
    fun `下見のあとに端末側へ返事が付いたら最初の確定では書かない`() = runTest {
        val env = Env(this)
        env.persistence.put(trace("ideas/habit.md"))
        val backup = ReadingTraceBackupJson.encode(
            listOf(trace("ideas/habit.md").copy(reflection = reflection("問い", 900L, "退避側の返事", 1_000L))),
            1_000L
        )
        env.controller.prepareImport { backup }
        advanceUntilIdle()
        assertEquals(
            0,
            (env.state.value as ReadingTraceBackupState.Planned).plan.localReplyReplaced
        )

        // 確定前に、同じノートへ返事が保存される。
        env.persistence.put(
            trace("ideas/habit.md").copy(reflection = reflection("問い", 50L, "後から書いた返事", 60L))
        )

        env.controller.applyImport()
        advanceUntilIdle()

        val revised = env.state.value as ReadingTraceBackupState.Planned
        assertTrue("作り直したことが画面に出ていない", revised.revised)
        assertEquals("承認していない損失を確定した", 0, env.persistence.saveCount)
        assertEquals(1, revised.plan.localReplyReplaced)

        // 作り直した計画を承認すれば、規則どおりマージする。
        env.controller.applyImport()
        advanceUntilIdle()

        assertTrue(env.state.value is ReadingTraceBackupState.Imported)
        assertEquals("退避側の返事", env.persistence.stored("ideas/habit.md")?.reflection?.reply)
    }

    // 返事だけでなく**訪問が増えただけ**でも作り直す。古い下見の値で上書きしないため。
    @Test
    fun `下見のあとに訪問が増えたら最初の確定では書かない`() = runTest {
        val env = Env(this)
        env.persistence.put(trace("ideas/habit.md"))
        val backup = ReadingTraceBackupJson.encode(listOf(trace("ideas/habit.md")), 1_000L)
        env.controller.prepareImport { backup }
        advanceUntilIdle()

        env.persistence.put(
            trace("ideas/habit.md").copy(
                visits = listOf(ReadingVisit(1_000L, null, 50), ReadingVisit(2_000L, null, 80)),
                totalVisitCount = 2
            )
        )

        env.controller.applyImport()
        advanceUntilIdle()
        assertEquals(0, env.persistence.saveCount)
        assertTrue((env.state.value as ReadingTraceBackupState.Planned).revised)

        env.controller.applyImport()
        advanceUntilIdle()

        // 後から着いた訪問を落とさずマージしている。
        assertEquals(2, env.persistence.stored("ideas/habit.md")?.visits?.size)
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

    // ── Main を占有しない ──────────────────────────────────────────────────

    /**
     * **退避ファイルの組み立てと解析は cpuDispatcher へ渡る。**
     *
     * 上限は8MB・5,000件で、JSONの組み立ても解析も入力サイズに比例する。
     * `scope` は本番では `viewModelScope`（Main）なので、ここを外すと
     * 上限近傍で進捗表示も中止ボタンも止まる。
     *
     * **何がその中で走るか**は `ReadingTraceBackupThreadingTest` がソース走査で見る。
     * こちらは**差し替え口が本当に使われている**ことを見る。
     */
    @Test
    fun `書き出しと読み戻しはcpuDispatcherを経由する`() = runTest {
        val env = Env(this)
        env.persistence.put(trace("ideas/habit.md"))

        env.controller.export { bytes -> env.written = bytes }
        advanceUntilIdle()
        assertTrue("書き出しが cpuDispatcher を経由していない", env.cpuDispatcher.dispatches > 0)

        val before = env.cpuDispatcher.dispatches
        env.controller.prepareImport { env.written!! }
        advanceUntilIdle()
        assertTrue(
            "読み戻しの解析が cpuDispatcher を経由していない",
            env.cpuDispatcher.dispatches > before
        )
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
    //
    // **まとまりの境界ではなく途中で止める。** 25件ちょうどで中止するテストは、
    // 「中止を受けてもまとまりを走り切る」欠陥をそのまま通してしまう
    // （実機では表示55件に対し75件が保存された）。
    @Test
    fun `適用の中断はまとまりの途中でも報告と実保存が一致する`() = runTest {
        val env = Env(this)
        val backup = ReadingTraceBackupJson.encode(
            (1..60).map { trace("notes/$it.md") },
            1_000L
        )
        env.controller.prepareImport { backup }
        advanceUntilIdle()
        env.persistence.afterSave = { if (env.persistence.saveCount == 10) env.controller.cancel() }

        env.controller.applyImport()
        advanceUntilIdle()

        val imported = env.state.value as ReadingTraceBackupState.Imported
        assertTrue("中断したことが結果に出ていない", imported.interrupted)
        assertEquals("中止を受けてもまとまりを走り切っている", 10, env.persistence.saveCount)
        assertEquals("報告が実保存と食い違う", 10, imported.added)
    }

    // 結果を確定して見せた後は、**画面に出ていない書き込みが1件も起きない。**
    @Test
    fun `中断の結果を出した後は保存が増えない`() = runTest {
        val env = Env(this)
        val backup = ReadingTraceBackupJson.encode(
            (1..60).map { trace("notes/$it.md") },
            1_000L
        )
        env.controller.prepareImport { backup }
        advanceUntilIdle()
        env.persistence.afterSave = { if (env.persistence.saveCount == 10) env.controller.cancel() }

        env.controller.applyImport()
        advanceUntilIdle()

        val reported = (env.state.value as ReadingTraceBackupState.Imported).added
        val settled = env.persistence.saveCount
        advanceUntilIdle()
        assertEquals("結果を出した後も保存が続いている", settled, env.persistence.saveCount)
        assertEquals("報告が実保存と食い違う", reported, env.persistence.saveCount)
    }

    // 追加だけでなく**既存痕跡との結合**でも同じ性質を保つ。
    // 結合は端末側の返事を置き換え得るので、報告から漏れると損失に気づけない。
    @Test
    fun `結合を含む適用の中断も報告と実保存が一致する`() = runTest {
        val env = Env(this)
        val paths = (1..60).map { "notes/$it.md" }
        paths.forEach { path ->
            env.persistence.put(
                trace(path).copy(reflection = reflection("ひとこと", 1_000L, "端末側の返事", 2_000L))
            )
        }
        val backup = ReadingTraceBackupJson.encode(
            paths.map {
                trace(it).copy(reflection = reflection("ひとこと", 1_000L, "退避側の返事", 3_000L))
            },
            1_000L
        )
        env.controller.prepareImport { backup }
        advanceUntilIdle()
        env.persistence.afterSave = { if (env.persistence.saveCount == 10) env.controller.cancel() }

        env.controller.applyImport()
        advanceUntilIdle()

        val imported = env.state.value as ReadingTraceBackupState.Imported
        assertTrue("中断したことが結果に出ていない", imported.interrupted)
        assertEquals("中止を受けてもまとまりを走り切っている", 10, env.persistence.saveCount)
        assertEquals("結合の報告が実保存と食い違う", 10, imported.merged)
        assertEquals(0, imported.added)
    }

    /**
     * **停止待ちの再入。** 実機と同じ「別スレッドで走る同期I/Oを、Main から2回止める」順序を作る。
     *
     * 単一スレッドのテストスケジューラでは作れない — そちらの中止は書き手自身のスタックから
     * 呼ばれるので、`cancel()` が戻った時点で書き手はもう進んでいない。実機では
     * **1回目の停止を待つあいだ画面は `Working` のままで中止ボタンも残る**ため、
     * 2度目の中止が入り得る。そこで結果を確定すると、処理中だった1件が
     * **表示に含まれないまま保存される**（前回P1と同じ壊れ方）。
     */
    @Test
    fun `停止待ち中の再タップは停止前の件数を確定しない`() = assertReentrantCancelKeepsCountsExact(merging = false)

    /** 結合（端末側の返事が置き換わる側）でも同じ性質を保つ。 */
    @Test
    fun `停止待ち中の再タップは結合でも件数を確定しない`() = assertReentrantCancelKeepsCountsExact(merging = true)

    private fun assertReentrantCancelKeepsCountsExact(merging: Boolean) {
        val mainLike = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "main-like") }
        val ioLike = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "io-like") }
        try {
            val main = mainLike.asCoroutineDispatcher()
            val env = Env(CoroutineScope(main), ioLike.asCoroutineDispatcher(), CountingDispatcher(main))
            val paths = (1..60).map { "notes/$it.md" }
            if (merging) {
                paths.forEach { path ->
                    env.persistence.put(
                        trace(path).copy(reflection = reflection("ひとこと", 1_000L, "端末側の返事", 2_000L))
                    )
                }
            }
            val backup = ReadingTraceBackupJson.encode(
                paths.map { path ->
                    if (merging) {
                        trace(path).copy(reflection = reflection("ひとこと", 1_000L, "退避側の返事", 3_000L))
                    } else {
                        trace(path)
                    }
                },
                1_000L
            )
            runBlocking {
                withContext(main) { env.controller.prepareImport { backup } }
                awaitState(env) { it is ReadingTraceBackupState.Planned }

                // 10件目の `save` の**内側**で書き手を止める。この時点で保存は済み、
                // 集計はまだ増えていない — 実機で中止が刺さるのと同じ位置。
                val reachedTenth = CountDownLatch(1)
                val releaseSave = CountDownLatch(1)
                env.persistence.afterSave = {
                    if (env.persistence.saveCount == 10) {
                        reachedTenth.countDown()
                        releaseSave.await()
                    }
                }
                withContext(main) { env.controller.applyImport() }
                assertTrue("10件目まで進まなかった", reachedTenth.await(10, TimeUnit.SECONDS))

                withContext(main) { env.controller.cancel() }
                withContext(main) { env.controller.cancel() }
                // 中止が積んだコルーチンを走らせ切ってから、止めていた save を解放する。
                withContext(main) { }

                assertTrue(
                    "書き手が止まる前に結果を確定した: ${env.state.value}",
                    env.state.value is ReadingTraceBackupState.Working
                )
                releaseSave.countDown()
                awaitState(env) { it is ReadingTraceBackupState.Imported }

                val imported = env.state.value as ReadingTraceBackupState.Imported
                assertTrue("中断したことが結果に出ていない", imported.interrupted)
                assertEquals("中止を受けてもまとまりを走り切っている", 10, env.persistence.saveCount)
                assertEquals(
                    "報告が実保存と食い違う",
                    env.persistence.saveCount,
                    if (merging) imported.merged else imported.added
                )
            }
        } finally {
            mainLike.shutdownNow()
            ioLike.shutdownNow()
        }
    }

    /** 実スレッドの完了を待つ。仮想時間が無いので、状態そのものを待つ。 */
    private fun awaitState(env: Env, predicate: (ReadingTraceBackupState) -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            if (predicate(env.state.value)) return
            Thread.sleep(2)
        }
        throw AssertionError("待っていた状態にならなかった: ${env.state.value}")
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

    private class Env(
        scope: CoroutineScope,
        ioDispatcher: CoroutineDispatcher,
        /** 差し替え口が実際に使われていることを数える。 */
        val cpuDispatcher: CountingDispatcher
    ) {
        /**
         * 既定は単一スレッドのテストスケジューラ。
         * **実スレッドの交錯を作る中断テストだけ**が別のディスパッチャを渡す。
         */
        constructor(scope: kotlinx.coroutines.test.TestScope) : this(
            scope,
            // Dispatchers.IO / Default はテストスケジューラの管理外なので差し替える。
            StandardTestDispatcher(scope.testScheduler),
            CountingDispatcher(StandardTestDispatcher(scope.testScheduler))
        )

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
            ioDispatcher = ioDispatcher,
            cpuDispatcher = cpuDispatcher,
            writeMutex = writeMutex
        )
    }

    /** 委譲しつつディスパッチ回数を数えるだけの入れ物。 */
    private class CountingDispatcher(
        private val delegate: CoroutineDispatcher
    ) : CoroutineDispatcher() {
        var dispatches = 0
            private set

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            dispatches++
            delegate.dispatch(context, block)
        }
    }

    private class RecordingWriter : ReadingTraceBackupStateWriter {
        /** 実スレッドの中断テストが別スレッドから読むので可視性を持たせる。 */
        @Volatile
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

    /** 置き場の一覧には出るのに読み出せないキー。SAF の一時的な読取失敗を作る。 */
    val unreadableKeys = mutableSetOf<String>()
    val unwritablePaths = mutableSetOf<String>()
    var listingUnavailable = false
    @Volatile
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
        // **一覧には出るのに読めない。** SAF の一時的な読取失敗はこの形になる
        // （Gateway が例外を null へ畳み、Store が `None` を返す）。
        if (key in unreadableKeys) return ReadingTraceReadResult.None
        return traces[key]?.let { ReadingTraceReadResult.Valid(it) } ?: ReadingTraceReadResult.None
    }

    override fun deleteByKey(key: String, vaultKey: String): Boolean = traces.remove(key) != null
}
