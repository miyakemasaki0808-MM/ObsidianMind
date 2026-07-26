package com.example.newproject.controller

import com.example.newproject.ai.AiClient
import com.example.newproject.domain.SummarizeUseCase
import com.example.newproject.domain.SummaryResult
import com.example.newproject.model.NoteUiState
import com.example.newproject.model.state.SummaryState
import com.google.mlkit.genai.common.DownloadStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * ノート本文の要約（モデルDL待ち込み）を担当する。summaryState の更新のみを行う。
 *
 * Quiz・Annotation・Distill と同じく requestId ＋ Job 追跡でノート切替に備える。
 * 元は [com.example.newproject.NoteViewModel] に直書きされており、この1本だけが
 * Controller 化から取り残されていた（DL完了が切替をすり抜け、旧ノートの本文で
 * 要約と関連ノートを再実行して新しいノートの画面へ書き戻していた）。
 *
 * 関連ノートをここへ持ち込まないのは、候補が `NoteFile`（＝SAFのUri）に依存し、
 * ViewModel 側の走査キャッシュから引く必要があるため。DL完了後の再実行は
 * [onModelReady] で呼び戻す。結果としてこのクラスは Android 依存を持たず、
 * 素のJVMテストで切替時の挙動を固定できる。
 */
class SummaryController(
    private val scope: CoroutineScope,
    private val summarizeUseCase: SummarizeUseCase,
    private val aiClient: AiClient,
    private val uiState: MutableStateFlow<NoteUiState>,
    private val onModelReady: (title: String, content: String) -> Unit
) {
    // モデルDL完了後に要約を再開するために保持
    private var pending: PendingSummary? = null
    private var summaryJob: Job? = null
    private var downloadJob: Job? = null
    private var activeRequestId = 0L

    fun fetch(title: String, content: String) {
        val requestId = ++activeRequestId
        summaryJob?.cancel()
        summaryJob = scope.launch {
            setStateIfCurrent(requestId, SummaryState.Loading)
            when (val result = summarizeUseCase.summarize(title, content)) {
                is SummaryResult.Success ->
                    setStateIfCurrent(requestId, SummaryState.Success(result.summary))
                is SummaryResult.AiUnavailable ->
                    setStateIfCurrent(requestId, SummaryState.AiUnavailable)
                is SummaryResult.AiNeedsDownload -> {
                    // モデル未DL → 自動でダウンロード開始し、完了後に要約を再開する
                    if (!isCurrent(requestId)) return@launch
                    pending = PendingSummary(requestId, title, content)
                    startModelDownload()
                }
                is SummaryResult.Error ->
                    setStateIfCurrent(requestId, SummaryState.Error(result.message))
            }
        }
    }

    /** ノート・Vault切替時に要約とDL待ちを止め、旧ノートの結果が後から混入するのを防ぐ。 */
    fun cancelAndClear() {
        activeRequestId++
        summaryJob?.cancel()
        downloadJob?.cancel()
        summaryJob = null
        downloadJob = null
        pending = null
        uiState.update { current -> current.copy(summaryState = SummaryState.Idle) }
    }

    private fun startModelDownload() {
        val request = pending ?: return
        downloadJob?.cancel()
        downloadJob = scope.launch {
            setStateIfCurrent(request.requestId, SummaryState.Downloading(downloaded = -1L, total = 0L))
            try {
                aiClient.downloadModel().collect { status ->
                    when (status) {
                        is DownloadStatus.DownloadStarted ->
                            setStateIfCurrent(
                                request.requestId,
                                SummaryState.Downloading(0L, status.bytesToDownload)
                            )
                        is DownloadStatus.DownloadProgress -> {
                            // total は直前の Downloading から引き継ぐ。照合は
                            // setStateIfCurrent が一手に引き受ける（ここで重ねない）。
                            val total = (uiState.value.summaryState as? SummaryState.Downloading)?.total ?: 0L
                            setStateIfCurrent(
                                request.requestId,
                                SummaryState.Downloading(status.totalBytesDownloaded, total)
                            )
                        }
                        is DownloadStatus.DownloadCompleted -> {
                            // 切替済みなら再開しない。ここを素通りさせると旧ノートの本文で
                            // 要約と関連ノートが走り、新しいノートの画面へ書き戻される。
                            if (!isCurrent(request.requestId)) return@collect
                            pending = null
                            fetch(request.title, request.content)
                            onModelReady(request.title, request.content)
                        }
                        is DownloadStatus.DownloadFailed -> {
                            pending = null
                            setStateIfCurrent(
                                request.requestId,
                                SummaryState.Error("モデルのダウンロードに失敗しました: ${status.e.message}")
                            )
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                pending = null
                setStateIfCurrent(
                    request.requestId,
                    SummaryState.Error("ダウンロードエラー: ${e.message}")
                )
            }
        }
    }

    private fun isCurrent(requestId: Long): Boolean = activeRequestId == requestId

    /**
     * 最後の要求だけが summaryState を更新できるようにする。
     *
     * DLの進捗にも掛けているのが要点。以前は Downloading を無条件に書き続けたため、
     * ノートを切り替えても新しいノートの要約欄がDL進捗で上書きされていた。
     */
    private fun setStateIfCurrent(requestId: Long, state: SummaryState) {
        if (!isCurrent(requestId)) return
        uiState.update { current -> current.copy(summaryState = state) }
    }

    private data class PendingSummary(
        val requestId: Long,
        val title: String,
        val content: String
    )
}
