package com.example.newproject

import com.example.newproject.ai.AiAvailability
import com.example.newproject.ai.AiClient
import com.example.newproject.controller.AnnotationController
import com.example.newproject.controller.NoteSessionCoordinator
import com.example.newproject.controller.ReadingTraceController
import com.example.newproject.controller.SearchController
import com.example.newproject.data.DistillPersistence
import com.example.newproject.data.DistillRecoveryAssessment
import com.example.newproject.data.DistillRecoveryResolutionResult
import com.example.newproject.data.DistillWriteRequest
import com.example.newproject.data.DistillWriteResult
import com.example.newproject.model.HistoryEntry
import com.example.newproject.data.HistoryStore
import com.example.newproject.model.NoteFolder
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
import com.example.newproject.model.state.AnnotationListState
import com.example.newproject.model.state.AnnotationState
import com.example.newproject.model.state.DistillState
import com.example.newproject.model.state.NoteState
import com.example.newproject.model.NoteUiState
import com.example.newproject.model.NoteUiStateStore
import com.example.newproject.model.state.QuizState
import com.example.newproject.model.ReadingTrace
import com.example.newproject.model.state.ReadingTraceCard
import com.example.newproject.model.ReadingVisit
import com.example.newproject.model.state.RelatedNotesState
import com.example.newproject.model.state.SearchState
import com.example.newproject.model.state.SectionChatState
import com.example.newproject.model.state.SummaryState
import android.net.Uri
import com.google.mlkit.genai.common.DownloadStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
 * テストは3層で見る。
 *  - **状態変換の網羅**（[NoteUiStateStore.beginNoteLoad] /
 *    [NoteUiStateStore.resetVaultScoped]）:
 *    全フィールドを埋めた状態から、何が消えて何が残るかをリフレクションで漏れなく突き合わせる。
 *    フィールドを足してリセット登録を忘れたら落ちる。
 *  - **調停の結線**: 7 Controller すべてのUI状態を非初期状態にしてから切替を通す。
 *    UI状態の一括リセットと重複して観測できない内部状態（Searchのキャッシュ／Job、
 *    Annotationの生成Job）は、実物Controllerへテスト用の値を直接積んで個別に確認する。
 *  - **ジョブ停止**: 走行中のAI生成を止めずに切り替えると旧結果が後着することの確認。
 *    状態リセットだけでは防げないので、対になっていること自体をここで担保する。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NoteSessionCoordinatorTest {

    // ── 状態変換の網羅 ──────────────────────────────────────────────────────

    /**
     * 状態変換 [NoteUiStateStore.resetVaultScoped] が落とさないフィールド。
     * **ここに無いフィールドは必ず初期値へ戻る。**
     *
     * 新しい状態を [NoteUiState] へ足してリセット登録を忘れると、下のテストが
     * 「初期値へ戻っていない」で落ちる。意図して残す場合だけ、理由を添えてここへ足す。
     */
    private val survivesVaultChangeTransform = mapOf(
        // 選択済みであることそのもの（切替先を選んだ直後なので true）
        "vaultSelected" to true,
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
        val store = NoteUiStateStore(fullyPopulatedState())
        store.resetVaultScoped()
        val reset = store.value

        assertEachFieldReset(actual = reset, survivors = survivesVaultChangeTransform)
    }

    @Test
    fun `ノート切替ではVault単位の状態が残る`() {
        val store = NoteUiStateStore(fullyPopulatedState())
        store.beginNoteLoad()
        val reset = store.value

        // 補記管理画面の一覧とさがすタブのスコープはノートと無関係なので、
        // ノートを開き直しただけで消えてはいけない（A案で分けた二層の担保）。
        assertTrue(reset.annotationListState is AnnotationListState.Success)
        assertEquals(1, reset.folders.size)
        assertEquals("下書き", reset.selectedFolder?.name)
        assertTrue(reset.searchState is SearchState.Success)
        assertEquals("列挙できませんでした", reset.foldersError)
        assertEquals(1, reset.todayHistory.size)

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

    /**
     * 7 Controller すべてを非初期状態にしてから Vault を切り替える。
     *
     * 状態変換だけでは落ちない `distillState`（DistillController）と
     * `annotationListState`（AnnotationController）を含めているので、
     * どちらかの後始末を [NoteSessionCoordinator.onVaultChanged] から消すと落ちる。
     */
    @Test
    fun `Vault切替で7 Controller の状態が一斉に初期化される`() = runTest {
        val env = Env(this)
        val coordinator = env.coordinator(initialState = fullyPopulatedState())

        assertAllControllersDirty(coordinator.uiState.value)

        coordinator.onVaultChanged()
        advanceUntilIdle()

        // Vault切替後に残ってよいのは、直後の loadRandomNote で
        // 差し替わる表示中ノートだけ。
        assertEachFieldReset(
            actual = coordinator.uiState.value,
            survivors = mapOf(
                "vaultSelected" to true,
                "noteState" to NoteState.Success(title = "旧ノート", content = "本文"),
                "wikilinkTitles" to setOf("旧リンク")
            )
        )
        // 旧VaultのURIは新Vaultで開けないので履歴の永続化ごと捨てる
        assertEquals(1, env.history.clearCount)
    }

    /**
     * ノート切替は「ジョブ停止」と「状態リセット」が必ず対になる。
     * 呼び出し側で2手に分けると片方だけ消しても動いてしまうため、
     * 調停クラスの [NoteSessionCoordinator.onNoteChanged] 1手に閉じてある。
     */
    @Test
    fun `ノート切替でノート単位だけが初期化されVault単位は残る`() = runTest {
        val env = Env(this)
        var hostCancelCount = 0
        val coordinator = env.coordinator(
            initialState = fullyPopulatedState(),
            cancelHostJobs = { hostCancelCount++ }
        )

        assertAllControllersDirty(coordinator.uiState.value)

        coordinator.onNoteChanged()
        advanceUntilIdle()

        val state = coordinator.uiState.value
        // ノート単位はすべて消えて、次の読込中になる
        assertTrue(state.noteState is NoteState.Loading)
        assertTrue(state.summaryState is SummaryState.Idle)
        assertTrue(state.relatedNotesState is RelatedNotesState.Idle)
        assertTrue(state.quizState is QuizState.Idle)
        assertTrue(state.annotationState is AnnotationState.Idle)
        assertTrue(state.distillState is DistillState.Idle)
        assertNull(state.sectionChat)
        assertEquals(false, state.isSectionChatSheetVisible)
        assertNull(state.readingTraceCard)
        // 窓口が持つノート単位ジョブ（ノート読込・関連ノート）も同じ契約から止まる
        assertEquals(1, hostCancelCount)

        // Vault単位は巻き込まない（補記管理画面とさがすタブのスコープ）
        assertTrue(state.annotationListState is AnnotationListState.Success)
        assertEquals(1, state.folders.size)
        assertEquals("下書き", state.selectedFolder?.name)
        assertTrue(state.searchState is SearchState.Success)
        assertEquals(1, state.todayHistory.size)
    }

    /**
     * SearchController のキャッシュと2種類のJobは `ContentResolver` を要する経路でしか
     * 通常は作れない。素のJVMではその実体を作れないため、実物Controllerの内部へ
     * 型消去で番兵を積み、Coordinatorからの [SearchController.onVaultChanged] 呼び出しを確認する。
     *
     * private名への依存はあるが、名前変更でテストが落ちる方が、後始末が無検証になるより安全。
     */
    @Test
    fun `Vault切替でSearchの走査キャッシュとJobが破棄される`() {
        val coordinator = Env(TestScope()).coordinator()
        val search: Any = privateField(coordinator, "search")
        val cache: MutableMap<Any?, Any?> = privateField(search, "scopeNotesCache")
        val searchJob = Job()
        val foldersJob = Job()
        cache["old-folder"] = "old-cache"
        setPrivateField(search, "searchJob", searchJob)
        setPrivateField(search, "foldersJob", foldersJob)

        coordinator.onVaultChanged()

        assertTrue(cache.isEmpty())
        assertTrue(searchJob.isCancelled)
        assertTrue(foldersJob.isCancelled)
        assertNull(privateField<Job?>(search, "searchJob"))
        assertNull(privateField<Job?>(search, "foldersJob"))
    }

    /**
     * `annotationState` は一括リセットでも Idle になるため、状態だけでは
     * [AnnotationController.cancelAndClear] の呼び忘れを検出できない。
     * 実物Controllerへ生成Jobを積み、Coordinatorのノート切替から停止されることを確認する。
     */
    @Test
    fun `ノート切替でAnnotationの生成Jobが停止される`() {
        val coordinator = Env(TestScope()).coordinator()
        val annotation: Any = privateField(coordinator, "annotation")
        val createJob = Job()
        val downloadJob = Job()
        setPrivateField(annotation, "createJob", createJob)
        setPrivateField(annotation, "downloadJob", downloadJob)

        coordinator.onNoteChanged()

        assertTrue(createJob.isCancelled)
        assertTrue(downloadJob.isCancelled)
        assertNull(privateField<Job?>(annotation, "createJob"))
        assertNull(privateField<Job?>(annotation, "downloadJob"))
    }

    // ── ジョブ停止（状態リセットだけでは防げない後着）────────────────────────

    /**
     * 走行中のAI生成を抱えたままノートを切り替える。
     *
     * 状態リセットは切替の瞬間に効くだけなので、ジョブが生きていると
     * **リセットの後から**旧ノートの結果が書き戻される。
     * [NoteSessionCoordinator.cancelNoteScopedJobs] のどれか1行を消すと、
     * ここが「Idleのはずが Success」で落ちる。
     */
    @Test
    fun `ノート切替後に旧ノートのAI結果が後着しない`() = runTest {
        val env = Env(this)
        val coordinator = env.coordinator()

        coordinator.fetchSummary("ノートA", "Aの本文")
        coordinator.openSection(NoteSection(title = "導入", level = 2, text = "セクション本文"))
        coordinator.generateQuiz("セクション", "十分な長さの本文をここに置く。".repeat(20))
        advanceUntilIdle()

        // まだ生成は返っていない
        assertTrue(coordinator.uiState.value.summaryState is SummaryState.Loading)
        assertNotNull(coordinator.uiState.value.sectionChat)

        coordinator.onNoteChanged()
        advanceUntilIdle()

        // 切替後に生成が返ってきても、新しいノートの画面には書き戻らない
        env.ai.completeAll("生成結果")
        advanceUntilIdle()

        val state = coordinator.uiState.value
        assertTrue(state.summaryState is SummaryState.Idle)
        assertTrue(state.quizState is QuizState.Idle)
        assertNull(state.sectionChat)
    }

    /**
     * セクションモデルは `NoteUiState` の外にあるため、リフレクションによる
     * 「リセット漏れ検査」の網に掛からない。切替で消えることを個別に固定する。
     */
    @Test
    fun `ノート切替でセクションモデルが破棄される`() = runTest {
        val env = Env(this)
        val coordinator = env.coordinator()

        coordinator.setNoteState(successNote("# 見出し\n\n本文"))
        advanceUntilIdle()
        assertNotNull(coordinator.sectionModel.value)

        coordinator.onNoteChanged()

        assertNull(coordinator.sectionModel.value)
    }

    /** 本文が変わる2経路のうち、蒸留の差し替え側で解析し直しを落としていないこと。 */
    @Test
    fun `蒸留の本文差し替えでセクションモデルが作り直される`() = runTest {
        val env = Env(this)
        val coordinator = env.coordinator()

        coordinator.setNoteState(successNote("# 蒸留前\n\n本文"))
        advanceUntilIdle()
        assertEquals(listOf("蒸留前"), coordinator.sectionModel.value?.sections?.map { it.title })

        val applied = coordinator.applyReloadedBody(
            targetUri = TARGET_URI,
            loaded = successNote("# 蒸留後\n\n**本文**")
        )
        advanceUntilIdle()

        assertTrue(applied)
        assertEquals(listOf("蒸留後"), coordinator.sectionModel.value?.sections?.map { it.title })
    }

    /** Success 以外へ遷移したら、直前のノートのブロックを残さない。 */
    @Test
    fun `ノートの読込に失敗するとセクションモデルが消える`() = runTest {
        val env = Env(this)
        val coordinator = env.coordinator()

        coordinator.setNoteState(successNote("# 見出し\n\n本文"))
        advanceUntilIdle()
        assertNotNull(coordinator.sectionModel.value)

        coordinator.setNoteState(NoteState.Error("読み込めません"))

        assertNull(coordinator.sectionModel.value)
    }

    /**
     * Vault切替では、記録中の読書セッションを**記録せずに**捨てる。
     * 捨て損なうと、旧ノートの痕跡が切替後のVaultへ書き込まれる（C案で塞いだ経路）。
     */
    @Test
    fun `Vault切替で記録中の読書セッションが捨てられる`() = runTest {
        val clock = TestClock()
        val env = Env(this, clock)
        val coordinator = env.coordinator()

        coordinator.startReadingTrace("習慣について", "ideas/habit.md", "doc-1")
        coordinator.reportReadingProgress(
            blockIndex = 3,
            blockFraction = 1f,
            totalBlocks = 10,
            sectionTitle = "導入"
        )
        // 訪問として記録される条件（10秒以上）を満たしておく
        clock.advance(10_000L)

        coordinator.onVaultChanged()
        advanceUntilIdle()

        assertEquals(emptyList<ReadingTrace>(), env.trace.saved)
    }

    /**
     * 通常のノート切替では、離れるノートの読書セッションを捨てずに確定保存する。
     * Vault切替の [ReadingTraceController.discard] とは逆の契約なので別々に固定する。
     */
    @Test
    fun `ノート切替で離れるノートの読書痕跡が保存される`() = runTest {
        val clock = TestClock()
        val env = Env(this, clock)
        val coordinator = env.coordinator()

        coordinator.startReadingTrace("習慣について", "ideas/habit.md", "doc-1")
        coordinator.reportReadingProgress(
            blockIndex = 3,
            blockFraction = 1f,
            totalBlocks = 10,
            sectionTitle = "導入"
        )
        clock.advance(10_000L)

        coordinator.onNoteChanged()
        advanceUntilIdle()

        assertEquals(1, env.trace.saved.size)
        assertEquals("ideas/habit.md", env.trace.saved.single().vaultRelativePath)
    }

    /**
     * ノート切替では、走行中の痕跡照合も止める。
     * 止め損なうと、**リセットの後から**旧ノートの「前回のあなた」カードが出る。
     */
    @Test
    fun `ノート切替後に旧ノートの再会カードが後着しない`() = runTest {
        val env = Env(this)
        env.trace.put(
            ReadingTrace(
                vaultRelativePath = "ideas/habit.md",
                noteTitle = "習慣について",
                documentId = "doc-1",
                visits = listOf(
                    ReadingVisit(atEpochMillis = 1L, progressPercent = 40, deepestSectionTitle = "導入")
                ),
                totalVisitCount = 2
            )
        )
        val coordinator = env.coordinator()

        // 照合はIOディスパッチャへ渡った時点で待たされる（まだカードは出ていない）
        coordinator.revealReadingTrace("ideas/habit.md")
        assertNull(coordinator.uiState.value.readingTraceCard)

        coordinator.onNoteChanged()
        advanceUntilIdle()

        assertNull(coordinator.uiState.value.readingTraceCard)
    }

    // ── Vault世代 ──────────────────────────────────────────────────────────

    @Test
    fun `Vault切替のたびに世代が進む`() {
        val coordinator = Env(TestScope()).coordinator()

        assertEquals(0L, coordinator.vaultGeneration)
        coordinator.onVaultChanged()
        assertEquals(1L, coordinator.vaultGeneration)
        coordinator.onVaultChanged()
        assertEquals(2L, coordinator.vaultGeneration)
    }

    /** 新しいVaultを指すのは、記録中セッションの破棄と世代の採番が済んだ後。 */
    @Test
    fun `Vaultの差し替えは世代を進めた後に反映される`() {
        val coordinator = Env(TestScope()).coordinator()
        var generationWhenApplied = -1L

        coordinator.onVaultChanged { generationWhenApplied = coordinator.vaultGeneration }

        assertEquals(1L, generationWhenApplied)
    }

    // ── ヘルパー ───────────────────────────────────────────────────────────

    /** 切替前に7 Controller すべてが非初期状態であることを明示する。 */
    private fun assertAllControllersDirty(state: NoteUiState) {
        assertTrue("Summary", state.summaryState !is SummaryState.Idle)
        assertTrue("Quiz", state.quizState !is QuizState.Idle)
        assertTrue("SectionChat", state.sectionChat != null)
        assertTrue("Annotation(生成)", state.annotationState !is AnnotationState.Idle)
        assertTrue("Annotation(一覧)", state.annotationListState !is AnnotationListState.Idle)
        assertTrue("Distill", state.distillState !is DistillState.Idle)
        assertTrue("ReadingTrace", state.readingTraceCard != null)
        assertTrue("Search", state.searchState !is SearchState.Idle)
    }

    /**
     * 全フィールドを初期値と異なる値で埋めた状態。
     *
     * `Uri` を要する中身（補記の保存先・候補ノート・ノートファイル）は素のJVMテストで
     * 作れないため、`Uri` を持たない派生や空リストで代用する。
     * `todayHistory` だけは**空だとリセット漏れを検出できない**ので、
     * 要素の中身を見ないことを承知のうえで型消去で非空にする。
     */
    @Suppress("UNCHECKED_CAST")
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
        // HistoryEntry は Uri を要るので作れない。ここで見たいのは「空へ戻るか」だけ。
        todayHistory = listOf("旧Vaultの履歴") as List<HistoryEntry>
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

    @Suppress("UNCHECKED_CAST")
    private fun <T> privateField(owner: Any, name: String): T {
        val field = owner.javaClass.getDeclaredField(name)
        field.isAccessible = true
        return field.get(owner) as T
    }

    private fun setPrivateField(owner: Any, name: String, value: Any?) {
        val field = owner.javaClass.getDeclaredField(name)
        field.isAccessible = true
        field.set(owner, value)
    }

    private fun successNote(content: String) = NoteState.Success(
        title = "ノート",
        content = content,
        targetUri = TARGET_URI
    )

    private companion object {
        const val TARGET_URI = "content://vault/note.md"
    }

    private class Env(
        private val scope: TestScope,
        private val clock: TestClock = TestClock()
    ) {
        val ai = FakeAi()
        val history = FakeHistoryStore()
        val distill = FakeDistillPersistence()
        val trace = FakeTracePersistence()

        fun coordinator(
            cancelHostJobs: () -> Unit = {},
            initialState: NoteUiState = NoteUiState()
        ) = NoteSessionCoordinator(
            scope = scope,
            persistScope = scope,
            repository = NoteRepository(),
            aiClient = ai,
            summarizeUseCase = SummarizeUseCase(
                ai,
                excerptDispatcher = StandardTestDispatcher(scope.testScheduler)
            ),
            searchPickerUseCase = SearchPickerUseCase(ai),
            distillPersistence = distill,
            readingTracePersistence = trace,
            history = history,
            vaultUri = { null },
            currentVaultKey = { "vault-a" },
            onModelReady = { _, _ -> },
            reloadBody = { _, _ -> false },
            cancelHostJobs = cancelHostJobs,
            initialState = initialState,
            clock = clock::now,
            // 痕跡I/Oもテストスケジューラに載せて、照合が走行中のまま切り替える状況を作る。
            ioDispatcher = StandardTestDispatcher(scope.testScheduler),
            // 表示用Markdownの解析も同様。解析中に切り替える状況を作れるようにする。
            parseDispatcher = StandardTestDispatcher(scope.testScheduler)
        )
    }

    private class TestClock {
        private var now = 0L
        fun now(): Long = now
        fun advance(millis: Long) {
            now += millis
        }
    }

    /** 生成を止めたまま保持し、[completeAll] で一斉に返す。切替後の後着を作るため。 */
    private class FakeAi : AiClient {
        private val pending = mutableListOf<CompletableDeferred<String>>()

        override suspend fun checkAvailability(): AiAvailability = AiAvailability.Available

        override suspend fun generate(prompt: String): String {
            val deferred = CompletableDeferred<String>()
            pending += deferred
            return deferred.await()
        }

        override fun downloadModel(): Flow<DownloadStatus> = emptyFlow()

        fun completeAll(result: String) {
            pending.forEach { it.complete(result) }
            pending.clear()
        }
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
        val saved = mutableListOf<ReadingTrace>()
        private val files = mutableMapOf<String, ReadingTrace>()

        fun put(trace: ReadingTrace) {
            files[trace.vaultRelativePath] = trace
        }

        override fun folderStatus(): ReadingTraceFolderStatus = ReadingTraceFolderStatus.Ready
        override fun load(vaultRelativePath: String, vaultKey: String): ReadingTraceReadResult =
            files[vaultRelativePath]?.let { ReadingTraceReadResult.Valid(it) }
                ?: ReadingTraceReadResult.None
        override fun save(trace: ReadingTrace, vaultKey: String): ReadingTraceSaveResult {
            saved += trace
            files[trace.vaultRelativePath] = trace
            return ReadingTraceSaveResult.Success
        }
    }
}
