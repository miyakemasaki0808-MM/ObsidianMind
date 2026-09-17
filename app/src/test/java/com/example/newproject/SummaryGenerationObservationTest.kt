package com.example.newproject

import com.example.newproject.ai.AiAvailability
import com.example.newproject.ai.GenerationRecordingAiClient
import com.example.newproject.data.FileSummaryCache
import com.example.newproject.data.sha256Hex
import com.example.newproject.domain.SummarizeUseCase
import com.example.newproject.domain.SummaryCache
import com.example.newproject.domain.SummaryResult
import com.example.newproject.fakes.FakeAiClient
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 実機ケースが数える「要約の生成の記録」が、**保存済みの当たりと同じ入力の再生成を区別できる**ことを固定する
 * （→ `docs/dev/features/note_summary.md` §10）。
 *
 * 本番では記録が logcat の1行になり、実機ケースはその行数で判定する。ここでは同じ記録を数で受ける。
 * **比べる相手は、保存を引かずに毎回生成し直す経路**である。その経路でも保存のファイル名と更新時刻は
 * 当たりの経路と同じになるので、ファイルを見る判定では毎回生成する回帰を合格にしてしまう。
 */
class SummaryGenerationObservationTest {
    private val root = Files.createTempDirectory("summary-observation-test").toFile()
    private var now = 1_000_000L

    @After
    fun cleanUp() {
        root.deleteRecursively()
    }

    @Test
    fun `生成の記録は保存済みの当たりと同じ入力の再生成を区別でき、ファイルでは区別できない`() = runTest {
        val cached = observe(File(root, "cached")) { it }
        val bypassed = observe(File(root, "bypassed")) { cache -> BypassingLookup(cache) }

        assertEquals("初めて開いたときは、どちらも1回生成する（記録が動いていることの対照）", 1, cached.firstGenerations)
        assertEquals(1, bypassed.firstGenerations)
        assertEquals("保存を引く経路は、開き直すと生成しない", 0, cached.secondGenerations)
        assertEquals("保存を引かない経路は、開き直すと生成し直す", 1, bypassed.secondGenerations)

        assertEquals("出る要約は同じ", cached.results, bypassed.results)
        assertEquals("保存のファイル名は同じ", cached.fileNames, bypassed.fileNames)
        assertEquals("2回目の後の更新時刻も同じ", cached.lastModified, bypassed.lastModified)
    }

    /**
     * **再起動の試行は、起動の前から数える**（実機ケース `SUMCACHE-05`）。
     *
     * 前提として、再起動の前に一時Vaultの全ノートを保存済みにしておく。すると起動から数えた記録は
     * **どのノートのものでも「再起動をまたいで保存を引けなかった」ことを意味する**ので、
     * 起動直後に何が表示されても、Aの保存が失われていれば1回以上になる。
     * **別ノートの行をAの当たり外れとして読まない** — その行は、そのノートの保存が再起動をまたがなかった証拠として不合格にする。
     */
    @Test
    fun `再起動の試行は起動の前から数えるので、どのノートが先に出ても失われた保存を見逃さない`() = runTest {
        assertEquals("保存が残れば0回", 0, restartTrial(lost = emptySet(), startup = null))
        assertEquals("Aの保存が失われれば1回", 1, restartTrial(lost = setOf(NOTE_A), startup = null))
        assertEquals("起動直後にBが出ても、保存が残れば0回", 0, restartTrial(lost = emptySet(), startup = NOTE_B))
        assertEquals("起動直後にBが出ても、Aの保存が失われれば1回", 1, restartTrial(lost = setOf(NOTE_A), startup = NOTE_B))
        assertEquals("起動直後にAが出ても、Aの保存が失われれば1回", 1, restartTrial(lost = setOf(NOTE_A), startup = NOTE_A))
        assertEquals("起動直後に出たBの保存が失われていても1回（Bの保存が残らなかった不合格）", 1, restartTrial(lost = setOf(NOTE_B), startup = NOTE_B))
    }

    /**
     * 起動直後の要約が出きってから数え始めると、**失われたAの保存を起動直後の生成が補った後**になる。
     * 開き直したAは当たるので0回になり、保存が再起動をまたがなくても合格してしまう。
     */
    @Test
    fun `起動直後の要約の後から数え始めると、失われた保存を補った生成を見逃す`() = runTest {
        assertEquals(
            0,
            restartTrial(lost = setOf(NOTE_A), startup = NOTE_A, countFromBeforeLaunch = false)
        )
    }

    /** 状態確認とDLは数えない。数えると、DLを挟んだ試行で「初めて開いたら1回」が崩れる。 */
    @Test
    fun `生成の記録は生成の呼び出しだけを数え、失敗した生成も1回に数える`() = runTest {
        var generations = 0
        val client = GenerationRecordingAiClient(
            FakeAiClient(AiAvailability.NeedsDownload) { throw IllegalStateException("生成に失敗") }
        ) { generations++ }

        client.checkAvailability()
        client.downloadModel().toList()
        assertEquals(0, generations)

        runCatching { client.generate("プロンプト") }
        assertEquals(1, generations)
    }

    private suspend fun observe(
        directory: File,
        wrap: (SummaryCache) -> SummaryCache
    ): Observation {
        var generations = 0
        val client = GenerationRecordingAiClient(FakeAiClient.returning("要約結果")) { generations++ }
        val useCase = SummarizeUseCase(client, wrap(fileCache(directory)), Dispatchers.Unconfined)

        now = 1_000_000L
        val first = useCase.summarize(TITLE, CONTENT)
        val firstGenerations = generations
        now = 2_000_000L
        val second = useCase.summarize(TITLE, CONTENT)

        val files = directory.listFiles().orEmpty().sortedBy { it.name }
        return Observation(
            firstGenerations = firstGenerations,
            secondGenerations = generations - firstGenerations,
            results = listOf(first, second),
            fileNames = files.map { it.name },
            lastModified = files.map { it.lastModified() }
        )
    }

    /**
     * AとBを保存済みにしてから再起動を模し、Aを開くまでに数えた生成の記録を返す。
     * 再起動は、同じ置き場を読む保存と記録を作り直すことで表す（プロセスの再起動そのものではない）。
     *
     * @param lost 再起動の間に保存を失わせるノート
     * @param startup 起動直後に表示されるノート。null なら何も表示しない
     * @param countFromBeforeLaunch false なら、起動直後の要約が出きってから数え始める
     */
    private suspend fun restartTrial(
        lost: Set<Note>,
        startup: Note?,
        countFromBeforeLaunch: Boolean = true
    ): Int {
        val directory = Files.createTempDirectory(root.toPath(), "restart").toFile()
        val beforeRestart = FakeAiClient.returning("要約結果")
        val prompts = listOf(NOTE_A, NOTE_B).associateWith { note ->
            SummarizeUseCase(beforeRestart, fileCache(directory), Dispatchers.Unconfined)
                .summarize(note.title, note.content)
            requireNotNull(beforeRestart.lastPrompt)
        }
        lost.forEach { note ->
            File(directory, sha256Hex(prompts.getValue(note).toByteArray(Charsets.UTF_8))).delete()
        }

        var generations = 0
        val client = GenerationRecordingAiClient(FakeAiClient.returning("要約結果")) { generations++ }
        val useCase = SummarizeUseCase(client, fileCache(directory), Dispatchers.Unconfined)
        startup?.let { useCase.summarize(it.title, it.content) }
        val countedFrom = if (countFromBeforeLaunch) 0 else generations
        useCase.summarize(NOTE_A.title, NOTE_A.content)
        return generations - countedFrom
    }

    private fun fileCache(directory: File) = FileSummaryCache(
        directory = directory,
        clock = { now },
        ioDispatcher = Dispatchers.Unconfined
    )

    /** 保存は書くが引かない。**保存を引く1行を落とした回帰**の代わり。 */
    private class BypassingLookup(private val delegate: SummaryCache) : SummaryCache {
        override suspend fun find(prompt: String): String? = null
        override suspend fun save(prompt: String, summary: String) = delegate.save(prompt, summary)
    }

    private data class Note(val title: String, val content: String)

    private data class Observation(
        val firstGenerations: Int,
        val secondGenerations: Int,
        val results: List<SummaryResult>,
        val fileNames: List<String>,
        val lastModified: List<Long>
    )

    private companion object {
        const val TITLE = "ノートA"
        const val CONTENT = "Aの本文"
        val NOTE_A = Note(TITLE, CONTENT)
        val NOTE_B = Note("ノートB", "Bの本文")
    }
}
