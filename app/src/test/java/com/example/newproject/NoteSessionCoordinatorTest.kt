package com.example.newproject

import com.example.newproject.ai.AiAvailability
import com.example.newproject.ai.AiClient
import com.example.newproject.controller.NoteSessionCoordinator
import com.example.newproject.data.DistillPersistence
import com.example.newproject.data.DistillRecoveryAssessment
import com.example.newproject.data.DistillRecoveryResolutionResult
import com.example.newproject.data.DistillWriteRequest
import com.example.newproject.data.DistillWriteResult
import com.example.newproject.data.HistoryEntry
import com.example.newproject.data.HistoryStore
import com.example.newproject.data.NoteFolder
import com.example.newproject.data.NoteRepository
import com.example.newproject.data.PendingDistillOriginal
import com.example.newproject.data.ReadingTraceFolderStatus
import com.example.newproject.data.ReadingTracePersistence
import com.example.newproject.data.ReadingTraceReadResult
import com.example.newproject.data.ReadingTraceSaveResult
import com.example.newproject.data.sha256Hex
import com.example.newproject.domain.SearchPickerUseCase
import com.example.newproject.domain.SummarizeUseCase
import com.example.newproject.domain.markdown.NoteSection
import com.example.newproject.model.AnnotationListState
import com.example.newproject.model.AnnotationState
import com.example.newproject.model.DistillState
import com.example.newproject.model.NoteState
import com.example.newproject.model.NoteUiState
import com.example.newproject.model.QuizState
import com.example.newproject.model.ReadingTrace
import com.example.newproject.model.ReadingTraceCard
import com.example.newproject.model.RelatedNotesState
import com.example.newproject.model.SearchState
import com.example.newproject.model.SectionChatState
import com.example.newproject.model.SummaryState
import com.example.newproject.model.resetNoteScopedStates
import com.example.newproject.model.resetVaultScopedStates
import android.net.Uri
import com.google.mlkit.genai.common.DownloadStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier

/**
 * Vault切替・ノート切替の一斉初期化を固定する。
 *
 * この調停は「リセット漏れ→旧状態の残留」という最も再発しやすいバグの発生源だが、
 * [NoteViewModel] は `Uri` / `SharedPreferences` / `ContentResolver` に依存するため
 * 素のJVMテストではインスタンスを作れず、410件のテストが1件も通っていなかった。
 * Android APIを呼ばない [NoteSessionCoordinator] へ調停を出したことで、ここが検証できる。
 *
 * テストは2層に分けている。
 *  - **状態変換の網羅**（[resetNoteScopedStates] / [resetVaultScopedStates]）:
 *    全17フィールドを埋めた状態から、何が消えて何が残るかをリフレクションで漏れなく突き合わせる。
 *    `Uri` を持つフィールド（補記の保存先・履歴・候補ノート）は空リスト等で代用する。
 *  - **調停の結線**: 7 Controller を実物のまま組み立て、切替で実際に一斉初期化されること。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NoteSessionCoordinatorTest {

    // ── 状態変換の網羅 ──────────────────────────────────────────────────────

    /**
     * 状態変換 [resetVaultScopedStates] が落とさないフィールド。
     * **ここに無いフィールドは必ず初期値へ戻る。**
     *
     * 新しい状態を [NoteUiState] へ足してリセット登録を忘れると、下のテストが
     * 「初期値へ戻っていない」で落ちる。意図して残す場合だけ、理由を添えてここへ足す。
     */
    private val survivesVaultChange = mapOf(
        // 選択済みであることそのもの（切替先を選んだ直後なので true）
        "vaultSelected" to true,
        // 端末設定。Vaultと無関係
        "darkTheme" to true,
        // 切替直後に loadRandomNote が走って差し替わるため、ここで落とすと画面が点滅する
        "noteState" to NoteState.Success(title = "旧ノート", content = "本文"),
        "wikilinkTitles" to setOf("旧リンク"),
        // 以下2つは**状態変換ではなくController側が落とす**。
        // 蒸留は復旧待ち（RecoveryRequired）だけは残す判断があるため
        // DistillController.cancelForNoteChange() が持ち、補記一覧は
        // AnnotationController.onVaultChanged() が走行中のJob停止と一緒に落とす。
        // ここで二重に落とすと、その判断が状態変換側にも分裂する。
        // 実際に Idle へ戻ることは下の結線テストで確かめている。
        "distillState" to DistillState.Saved(sourceTitle = "旧ノート", sentenceCount = 3),
        "annotationListState" to AnnotationListState.Success(emptyList())
    )

    @Test
    fun `Vault切替の状態リセットに登録漏れが無い`() {
        val reset = fullyPopulatedState().resetVaultScopedStates()

        assertEachFieldReset(actual = reset, survivors = survivesVaultChange)
    }

    @Test
    fun `ノート切替ではVault単位の状態が残る`() {
        val reset = fullyPopulatedState().resetNoteScopedStates()

        // 補記管理画面の一覧とさがすタブのスコープはノートと無関係なので、
        // ノートを開き直しただけで消えてはいけない（A案で分けた二層の担保）。
        assertTrue(reset.annotationListState is AnnotationListState.Success)
        assertEquals(1, reset.folders.size)
        assertEquals("下書き", reset.selectedFolder?.name)
        assertTrue(reset.searchState is SearchState.Success)
        assertEquals("列挙できませんでした", reset.foldersError)

        // ノート単位はすべて消える
        assertTrue(reset.summaryState is SummaryState.Idle)
        assertTrue(reset.relatedNotesState is RelatedNotesState.Idle)
        assertTrue(reset.quizState is QuizState.Idle)
        assertTrue(reset.annotationState is AnnotationState.Idle)
        assertNull(reset.sectionChat)
        assertEquals(false, reset.isSectionChatSheetVisible)
        assertNull(reset.readingTraceCard)
    }

    // ── 調停の結線 ─────────────────────────────────────────────────────────

    @Test
    fun `Vault切替で7 Controller の状態が一斉に初期化される`() = runTest {
        val env = Env(this)
        val coordinator = env.coordinator()

        // 各Controllerを非Idleにする（Uriを要さない経路だけで作れる）。
        // openSection はセッションが無いとき前セクションのクイズを捨てる仕様なので、
        // クイズより先に呼ぶ。
        coordinator.fetchSummary("ノートA", "Aの本文")
        coordinator.openSection(NoteSection(title = "導入", level = 2, text = "セクション本文"))
        coordinator.generateQuiz("セクション", "十分な長さの本文をここに置く。".repeat(20))
        advanceUntilIdle()

        assertTrue(coordinator.uiState.value.summaryState !is SummaryState.Idle)
        assertTrue(coordinator.uiState.value.quizState !is QuizState.Idle)
        assertTrue(coordinator.uiState.value.sectionChat != null)

        coordinator.onVaultChanged()
        advanceUntilIdle()

        val state = coordinator.uiState.value
        assertTrue(state.summaryState is SummaryState.Idle)
        assertTrue(state.quizState is QuizState.Idle)
        assertTrue(state.annotationState is AnnotationState.Idle)
        assertTrue(state.annotationListState is AnnotationListState.Idle)
        assertTrue(state.distillState is DistillState.Idle)
        assertNull(state.sectionChat)
        assertEquals(false, state.isSectionChatSheetVisible)
        assertNull(state.readingTraceCard)
        assertTrue(state.searchState is SearchState.Idle)
        assertEquals(emptyList<NoteFolder>(), state.folders)
        assertEquals(emptyList<HistoryEntry>(), state.todayHistory)
        assertTrue(state.vaultSelected)
        // 旧VaultのURIは新Vaultで開けないので履歴の永続化ごと捨てる
        assertEquals(1, env.history.clearCount)
    }

    @Test
    fun `Vault切替のたびに世代が進む`() {
        val env = Env(TestScope())
        val coordinator = env.coordinator()

        assertEquals(0L, coordinator.vaultGeneration)
        coordinator.onVaultChanged()
        assertEquals(1L, coordinator.vaultGeneration)
        coordinator.onVaultChanged()
        assertEquals(2L, coordinator.vaultGeneration)
    }

    /** 新しいVaultを指すのは、記録中セッションの破棄と世代の採番が済んだ後。 */
    @Test
    fun `Vaultの差し替えは世代を進めた後に反映される`() {
        val env = Env(TestScope())
        val coordinator = env.coordinator()
        var generationWhenApplied = -1L

        coordinator.onVaultChanged { generationWhenApplied = coordinator.vaultGeneration }

        assertEquals(1L, generationWhenApplied)
    }

    @Test
    fun `窓口が持つノート単位ジョブも契約から止まる`() {
        val env = Env(TestScope())
        var hostCancelCount = 0
        val coordinator = env.coordinator(cancelHostJobs = { hostCancelCount++ })

        coordinator.cancelNoteScopedJobs()
        assertEquals(1, hostCancelCount)

        // Vault切替も同じ契約を通る
        coordinator.onVaultChanged()
        assertEquals(2, hostCancelCount)
    }

    // ── ヘルパー ───────────────────────────────────────────────────────────

    /**
     * 全フィールドを初期値と異なる値で埋めた状態。
     * `Uri` を要する中身（補記の保存先・履歴・候補ノート・ノートファイル）は
     * 素のJVMテストで作れないため、空リストや `Uri` を持たない派生で代用する。
     */
    private fun fullyPopulatedState() = NoteUiState(
        vaultSelected = true,
        noteState = NoteState.Success(title = "旧ノート", content = "本文"),
        summaryState = SummaryState.Success("要約"),
        relatedNotesState = RelatedNotesState.Success(emptyList(), emptyList()),
        quizState = QuizState.Success(sourceTitle = "旧ノート", cards = emptyList()),
        wikilinkTitles = setOf("旧リンク"),
        annotationState = AnnotationState.Error(message = "失敗", sourceTitle = "旧ノート"),
        distillState = DistillState.Saved(sourceTitle = "旧ノート", sentenceCount = 3),
        annotationListState = AnnotationListState.Success(emptyList()),
        sectionChat = SectionChatState(sectionTitle = "導入", sectionContext = "文脈"),
        isSectionChatSheetVisible = true,
        readingTraceCard = ReadingTraceCard(
            visitCount = 2,
            lastVisitAtMillis = 1L,
            lastSectionTitle = "導入",
            lastProgressPercent = 40
        ),
        folders = listOf(NoteFolder(name = "下書き", documentId = "old-folder")),
        selectedFolder = NoteFolder(name = "下書き", documentId = "old-folder"),
        foldersError = "列挙できませんでした",
        searchState = SearchState.Success(emptyList()),
        todayHistory = emptyList(),
        darkTheme = true
    )

    /**
     * 初期値へ戻っていないフィールドを [survivors] と突き合わせる。
     *
     * `kotlin-reflect` を足さずに済ませるため Java のリフレクションで読む。
     * 個別に `assertEquals` を並べないのは、**フィールドを足したときに書き忘れる**のが
     * まさにこの契約で防ぎたい失敗だから（テスト自身が漏れては意味が無い）。
     */
    private fun assertEachFieldReset(actual: NoteUiState, survivors: Map<String, Any?>) {
        val defaults = NoteUiState()
        val unexpected = mutableListOf<String>()
        NoteUiState::class.java.declaredFields
            .filterNot { it.isSynthetic || Modifier.isStatic(it.modifiers) }
            .forEach { field ->
                field.isAccessible = true
                val actualValue = field.get(actual)
                val expected = if (survivors.containsKey(field.name)) {
                    survivors[field.name]
                } else {
                    field.get(defaults)
                }
                if (actualValue != expected) {
                    unexpected += "${field.name}: expected=$expected actual=$actualValue"
                }
            }
        assertEquals(
            "初期値へ戻っていないフィールドがある（リセット登録漏れ、または survivors への追記漏れ）",
            emptyList<String>(),
            unexpected
        )
    }

    private class Env(private val scope: TestScope) {
        val ai = FakeAi()
        val history = FakeHistoryStore()
        val distill = FakeDistillPersistence()
        val trace = FakeTracePersistence()

        fun coordinator(cancelHostJobs: () -> Unit = {}) = NoteSessionCoordinator(
            scope = scope,
            persistScope = scope,
            repository = NoteRepository(),
            aiClient = ai,
            summarizeUseCase = SummarizeUseCase(ai),
            searchPickerUseCase = SearchPickerUseCase(ai),
            distillPersistence = distill,
            readingTracePersistence = trace,
            history = history,
            vaultUri = { null },
            currentVaultKey = { "vault-a" },
            onModelReady = { _, _ -> },
            reloadBody = { _, _ -> false },
            cancelHostJobs = cancelHostJobs
        )
    }

    private class FakeAi : AiClient {
        override suspend fun checkAvailability(): AiAvailability = AiAvailability.Available
        override suspend fun generate(prompt: String): String = "生成結果"
        override fun downloadModel(): Flow<DownloadStatus> = emptyFlow()
    }

    private class FakeHistoryStore : HistoryStore {
        var clearCount = 0
            private set

        override fun load(): List<HistoryEntry> = emptyList()
        override fun record(title: String, uri: Uri): List<HistoryEntry> = emptyList()
        override fun clear() {
            clearCount++
        }
    }

    private class FakeDistillPersistence : DistillPersistence {
        override fun write(request: DistillWriteRequest): DistillWriteResult =
            DistillWriteResult.Success(sha256Hex(request.outputBytes), request.outputBytes.size)
        override fun assessPendingRecovery(): DistillRecoveryAssessment = DistillRecoveryAssessment.None
        override fun discardResolvedRecovery(assessment: DistillRecoveryAssessment): Boolean = true
        override fun discardPendingRecovery(): Boolean = true
        override fun pendingOriginal(): PendingDistillOriginal? = null
        override fun restoreOriginal(): DistillRecoveryResolutionResult =
            DistillRecoveryResolutionResult.NoValidRecord
    }

    private class FakeTracePersistence : ReadingTracePersistence {
        override fun folderStatus(): ReadingTraceFolderStatus = ReadingTraceFolderStatus.Ready
        override fun load(vaultRelativePath: String, vaultKey: String): ReadingTraceReadResult =
            ReadingTraceReadResult.None
        override fun save(trace: ReadingTrace, vaultKey: String): ReadingTraceSaveResult =
            ReadingTraceSaveResult.Success
    }
}
