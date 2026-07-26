package com.example.newproject

import com.example.newproject.ai.AiAvailability
import com.example.newproject.ai.AiClient
import com.example.newproject.controller.SummaryController
import com.example.newproject.domain.SummarizeUseCase
import com.example.newproject.model.NoteUiState
import com.example.newproject.model.state.SummaryState
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.GenAiException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import com.example.newproject.model.NoteUiStateStore
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * モデルDL待ちの要約がノート・Vault切替をすり抜けないことを固定する。
 *
 * これは 2026-07-26 時点で唯一「ユーザーに見える誤情報」まで到達していた経路。
 * ノートAのDL中にノートBへ切り替えると、DL完了時に**ノートAの本文で要約が走り、
 * ノートBの画面へ書き戻されていた**。Uri に触れないので素のJVMテストで固定できる。
 */
class SummaryControllerTest {

    @Test
    fun `DL中にノートを切り替えると完了後の要約が書き戻されない`() = runTest {
        val downloads = Channel<DownloadStatus>(Channel.UNLIMITED)
        val ai = FakeAiClient(AiAvailability.NeedsDownload, downloads)
        val state = NoteUiStateStore(NoteUiState())
        var modelReadyCalls = 0
        val controller = controller(state, ai) { _, _ -> modelReadyCalls++ }

        controller.fetch("ノートA", "Aの本文")
        advanceUntilIdle()
        assertTrue(state.value.summaryState is SummaryState.Downloading)

        // ノートBへ切替
        controller.cancelAndClear()
        advanceUntilIdle()

        // 切替後にDLが完了しても、旧ノートの要約は走らない
        ai.availability = AiAvailability.Available
        downloads.send(DownloadStatus.DownloadCompleted)
        advanceUntilIdle()

        assertTrue(state.value.summaryState is SummaryState.Idle)
        assertEquals(0, modelReadyCalls)
        assertEquals(0, ai.generateCount)
    }

    /**
     * requestId ガード単体の検証。
     *
     * 上のテストは `cancelAndClear()` が `downloadJob` を止めることで通るため、
     * ガードを外しても落ちない（＝ガードを検証していない）。DLを止めずに
     * 次の要求だけを進めることで、`isCurrent()` だけが防いでいる状態を作る。
     * CLAUDE.md の「キャンセルがすり抜ける経路には requestId を併用する」が効くのはここ。
     */
    @Test
    fun `DL中に次の要約が始まると完了後に前の入力で走らない`() = runTest {
        val downloads = Channel<DownloadStatus>(Channel.UNLIMITED)
        val ai = FakeAiClient(AiAvailability.NeedsDownload, downloads)
        val state = NoteUiStateStore(NoteUiState())
        val readyWith = mutableListOf<Pair<String, String>>()
        val controller = controller(state, ai) { title, content -> readyWith += title to content }

        controller.fetch("ノートA", "Aの本文")
        advanceUntilIdle()

        // DLジョブは止めずに次の要求だけを進める（cancelAndClear は呼ばない）
        ai.availability = AiAvailability.Available
        controller.fetch("ノートB", "Bの本文")
        advanceUntilIdle()

        downloads.send(DownloadStatus.DownloadCompleted)
        advanceUntilIdle()

        // Aの本文で要約も関連ノートも走らない
        assertEquals(emptyList<Pair<String, String>>(), readyWith)
        assertEquals(1, ai.prompts.size)
        assertTrue(ai.prompts.single().contains("Bの本文"))
    }

    /**
     * DL進捗の書き込みにも照合が掛かっていることの検証。
     *
     * 修正前は Downloading を無条件に書き続けていたため、切替後のノートが
     * 自分の要約を出していても、旧ノートのDL進捗がそれを上書きしていた。
     * ここも `cancelAndClear()` を使うと DL ごと止まって照合に到達しないので、
     * DLは生かしたまま次の要求だけを進める。
     */
    @Test
    fun `DL中に次の要約が始まると進捗で新しい要約が上書きされない`() = runTest {
        val downloads = Channel<DownloadStatus>(Channel.UNLIMITED)
        val ai = FakeAiClient(AiAvailability.NeedsDownload, downloads)
        val state = NoteUiStateStore(NoteUiState())
        val controller = controller(state, ai)

        controller.fetch("ノートA", "Aの本文")
        advanceUntilIdle()

        ai.availability = AiAvailability.Available
        controller.fetch("ノートB", "Bの本文")
        advanceUntilIdle()
        assertTrue(state.value.summaryState is SummaryState.Success)

        downloads.send(DownloadStatus.DownloadProgress(1_000L))
        advanceUntilIdle()

        assertTrue(state.value.summaryState is SummaryState.Success)
    }

    @Test
    fun `DL完了で要約と関連ノートが再開される`() = runTest {
        val downloads = Channel<DownloadStatus>(Channel.UNLIMITED)
        val ai = FakeAiClient(AiAvailability.NeedsDownload, downloads)
        val state = NoteUiStateStore(NoteUiState())
        val readyWith = mutableListOf<Pair<String, String>>()
        val controller = controller(state, ai) { title, content -> readyWith += title to content }

        controller.fetch("ノートA", "Aの本文")
        advanceUntilIdle()

        ai.availability = AiAvailability.Available
        downloads.send(DownloadStatus.DownloadCompleted)
        advanceUntilIdle()

        assertEquals("要約結果", (state.value.summaryState as SummaryState.Success).summary)
        assertEquals(listOf("ノートA" to "Aの本文"), readyWith)
    }

    @Test
    fun `DL失敗はエラーとして表示される`() = runTest {
        val downloads = Channel<DownloadStatus>(Channel.UNLIMITED)
        val ai = FakeAiClient(AiAvailability.NeedsDownload, downloads)
        val state = NoteUiStateStore(NoteUiState())
        val controller = controller(state, ai)

        controller.fetch("ノートA", "Aの本文")
        advanceUntilIdle()

        downloads.send(DownloadStatus.DownloadFailed(GenAiException(RuntimeException("回線エラー"), 0)))
        advanceUntilIdle()

        assertTrue(state.value.summaryState is SummaryState.Error)
    }

    @Test
    fun `モデルDL済みならそのまま要約が出る`() = runTest {
        val ai = FakeAiClient(AiAvailability.Available, Channel())
        val state = NoteUiStateStore(NoteUiState())
        val controller = controller(state, ai)

        controller.fetch("ノートA", "Aの本文")
        advanceUntilIdle()

        assertEquals("要約結果", (state.value.summaryState as SummaryState.Success).summary)
    }

    private fun kotlinx.coroutines.test.TestScope.controller(
        state: NoteUiStateStore,
        ai: FakeAiClient,
        onModelReady: (String, String) -> Unit = { _, _ -> }
    ) = SummaryController(
        scope = CoroutineScope(StandardTestDispatcher(testScheduler)),
        summarizeUseCase = SummarizeUseCase(ai),
        aiClient = ai,
        state = state.summaryWriter,
        onModelReady = onModelReady
    )

    private class FakeAiClient(
        var availability: AiAvailability,
        private val downloads: Channel<DownloadStatus>
    ) : AiClient {
        // どの本文で生成が走ったかを見るために、プロンプトをそのまま控える。
        val prompts = mutableListOf<String>()
        val generateCount: Int get() = prompts.size

        override suspend fun checkAvailability(): AiAvailability = availability

        override suspend fun generate(prompt: String): String {
            prompts += prompt
            return "要約結果"
        }

        override fun downloadModel(): Flow<DownloadStatus> = downloads.receiveAsFlow()
    }
}
