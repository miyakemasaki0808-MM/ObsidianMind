package com.example.newproject.controller

import android.content.ContentResolver
import android.net.Uri
import com.example.newproject.ai.AiClient
import com.example.newproject.data.DistillPersistence
import com.example.newproject.data.HistoryStore
import com.example.newproject.data.NoteFolder
import com.example.newproject.data.NoteRepository
import com.example.newproject.data.ReadingTracePersistence
import com.example.newproject.domain.RelatedNote
import com.example.newproject.domain.SearchPickerUseCase
import com.example.newproject.domain.SummarizeUseCase
import com.example.newproject.domain.markdown.NoteSection
import com.example.newproject.model.NoteUiState
import com.example.newproject.model.NoteUiStateStore
import com.example.newproject.model.state.NoteState
import com.example.newproject.model.state.RelatedNotesState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow

/**
 * 7つの機能Controllerを束ね、**Controller間の調停**（ノート切替・Vault切替での
 * 一斉停止と一斉初期化）を担う。画面状態 [NoteUiState] の唯一の持ち主でもある。
 *
 * ## なぜ ViewModel から分けたか
 *
 * この調停こそが最も壊れやすく（状態リセット漏れによる旧状態の残留は過去に何度も
 * 出ている）、同時に最も検証が要る部分である。ところが
 * [com.example.newproject.NoteViewModel] は `Uri`・`SharedPreferences`・
 * `ContentResolver` に依存するため、素のJVMテストでは**インスタンスを1つも作れない**。
 * Robolectric やモックライブラリを足せば直接テストできるが、依存を増やさずに済む形と
 * して「Android APIを呼ばない調停クラス」を分離した。
 *
 * したがって本クラスの規律は次の1点に尽きる。
 *
 * > **Android API を呼ばない。** `Uri` や `ContentResolver` を引数として受け取り
 * > 下位へ素通しするのは構わないが、`Uri.parse` / `DocumentsContract` /
 * > `SharedPreferences` などをこのクラスの中で呼んではいけない。呼んだ瞬間に
 * > [com.example.newproject.NoteSessionCoordinatorTest] が動かなくなる。
 *
 * Uri を解決する処理（Vault走査キャッシュ・ノート読込・関連ノート）は ViewModel 側に
 * 残っている。本クラスはその結果を受け取って状態へ反映する。
 */
internal class NoteSessionCoordinator(
    scope: CoroutineScope,
    persistScope: CoroutineScope,
    repository: NoteRepository,
    aiClient: AiClient,
    summarizeUseCase: SummarizeUseCase,
    searchPickerUseCase: SearchPickerUseCase,
    distillPersistence: DistillPersistence,
    readingTracePersistence: ReadingTracePersistence,
    private val history: HistoryStore,
    vaultUri: () -> Uri?,
    currentVaultKey: () -> String?,
    /** モデルDL完了で要約が再開されるとき、同じ入力で関連ノートも呼び戻す（実装は ViewModel）。 */
    onModelReady: (title: String, content: String) -> Unit,
    /** 蒸留保存後の本文読み直し（SAF I/O を伴うため実装は ViewModel）。 */
    reloadBody: suspend (targetUri: String, expectedHash: String?) -> Boolean,
    /**
     * 窓口（ViewModel）が持つノート単位ジョブ＝ノート読込・関連ノートの停止フック。
     * 契約 [cancelNoteScopedJobs] を1箇所に保つために、Controller の外にあるジョブも
     * ここから呼ぶ。ノート単位のジョブを ViewModel に足したらここへ登録する。
     */
    private val cancelHostJobs: () -> Unit = {},
    /**
     * 状態の初期値。本番は既定の [NoteUiState] のまま使う。
     * テストが「切替前にすべてのControllerが非初期値」の状況を作るための差し込み口で、
     * `Uri` を要する状態（補記の保存先など）を外から与えられるようにしている。
     */
    initialState: NoteUiState = NoteUiState(),
    /**
     * 読書時間の計測に使う時計と、痕跡I/Oのディスパッチャ。
     * [ReadingTraceController] が同じ理由で持っているものをここから差し込めるようにしている
     * （Vault切替でセッションが捨てられること・照合の後着が止まることは、
     * 時間を進められないと検証できない）。
     */
    clock: () -> Long = System::currentTimeMillis,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    private val stateStore = NoteUiStateStore(initialState)
    val uiState: StateFlow<NoteUiState> = stateStore.uiState

    /**
     * Vault単位の非同期要求の世代。[onVaultChanged] のたびに進めて、補記一覧・
     * フォルダ一覧の結果が旧Vaultのものでないかを Controller 側が update 直前に照合する。
     *
     * `vaultUri` の比較で代用しないのは、A→B→A と選び直したときに同じ値になるため。
     *
     * ノート単位の世代は各Controllerが `activeRequestId` として自前で持つ（寿命が違う。
     * 補記一覧はノート切替では無効化してはいけない）。
     */
    var vaultGeneration = 0L
        private set

    // 機能ごとのController。各Controllerには担当領域だけを書けるWriterを渡す。
    private val sectionChat = SectionChatController(scope, aiClient, stateStore.sectionChatWriter)
    private val quiz = QuizController(scope, aiClient, stateStore.quizWriter)
    private val summary = SummaryController(
        scope = scope,
        summarizeUseCase = summarizeUseCase,
        aiClient = aiClient,
        state = stateStore.summaryWriter,
        onModelReady = onModelReady
    )
    private val annotation = AnnotationController(
        scope = scope,
        repository = repository,
        aiClient = aiClient,
        state = stateStore.annotationWriter,
        vaultUri = vaultUri,
        vaultGeneration = { vaultGeneration }
    )
    private val search = SearchController(
        scope = scope,
        repository = repository,
        searchPickerUseCase = searchPickerUseCase,
        state = stateStore.searchWriter,
        vaultUri = vaultUri,
        vaultGeneration = { vaultGeneration }
    )
    private val distill = DistillController(
        scope = scope,
        aiClient = aiClient,
        state = stateStore.distillWriter,
        currentNote = stateStore::currentNote,
        persistence = distillPersistence,
        reloadBody = reloadBody
    )
    private val readingTrace = ReadingTraceController(
        scope = scope,
        persistScope = persistScope,
        aiClient = aiClient,
        state = stateStore.readingTraceWriter,
        persistence = readingTracePersistence,
        currentVaultKey = currentVaultKey,
        clock = clock,
        ioDispatcher = ioDispatcher
    )

    // ── 契約: ノート単位の実行中ジョブの停止 ──────────────────────────────────
    // 対になる状態リセット側の契約は [NoteUiStateStore] 内の withNoteScopedReset。

    /**
     * ノート単位の実行中AIジョブをまとめて止める（状態リセットと対で呼ぶ）。
     * 旧ノートの生成が残っていると、結果の上書きだけでなく generate() の
     * 直列化ロックを握り続けて新ノートの要約開始も遅らせてしまう。
     */
    fun cancelNoteScopedJobs() {
        // ここは「ノートを離れる」唯一の合流点なので、離脱フックを別に設けず
        // 読書痕跡の確定もここで行う。
        // flush は自前のスナップショットで書くため、以降のキャンセルに影響されない。
        readingTrace.flush()
        readingTrace.cancelForNoteChange()
        cancelHostJobs()
        summary.cancelAndClear()
        quiz.cancelAndClear()
        annotation.cancelAndClear()
        sectionChat.cancelAndClear()
        distill.cancelForNoteChange()
    }

    // ── Vaultのライフサイクル ────────────────────────────────────────────────

    /** 起動時に保存済みVaultを復元したときに呼ぶ。 */
    fun onVaultRestored() {
        stateStore.restoreVault(history.load())
    }

    /**
     * Vault切替の一斉初期化。ノート単位の状態に加え、さがすタブのスコープと
     * 当日履歴も破棄する（`selectedFolder` は旧Vaultの documentId を保持しているため必須）。
     *
     * URIの保存は呼び出し側（ViewModel）の責務なので、[applyLocation] で受け取って
     * **世代を進めた直後**に反映させる。順序が要点で、新しいVaultを指す前に
     * 記録中のセッションを捨て、世代を進めてからでないと旧要求が素通りする。
     */
    fun onVaultChanged(applyLocation: () -> Unit = {}) {
        // 保存先は書き込み時点のVaultから解決されるため、切替前に記録中の
        // セッションを捨てる。捨てないと旧ノートの痕跡が新Vaultへ書き込まれる。
        readingTrace.discard()
        // 走行中のVault単位要求を無効化する。cancel より先に進めておかないと、
        // すでに結果を持ち帰っている要求が旧世代のまま素通りする。
        vaultGeneration++
        applyLocation()
        search.onVaultChanged()
        annotation.onVaultChanged()
        cancelNoteScopedJobs()
        // 旧VaultのURIは新Vaultでは開けないため、閲覧履歴も破棄する
        history.clear()
        stateStore.resetVaultScoped()
        distill.checkRecovery()
    }

    // ── ノートのライフサイクル ──────────────────────────────────────────────

    /**
     * ノートを離れて次のノートの読込を始める。**ジョブ停止と状態リセットは必ずここで対になる。**
     *
     * 呼び出し側で2手に分けると、片方だけを消しても動いてしまい
     * 「旧ノートのAI結果が新しいノートの画面へ後着する」型のバグが復活する。
     * 実際この対を崩したことが過去の不具合の原因になっている。
     */
    fun onNoteChanged() {
        cancelNoteScopedJobs()
        stateStore.beginNoteLoad()
    }

    fun setNoteState(state: NoteState) {
        stateStore.setNoteState(state)
    }

    fun currentNote(): NoteState.Success? = stateStore.currentNote()

    /** ノートを開けた時点で当日履歴に積む。 */
    fun recordHistory(title: String, uri: Uri) {
        stateStore.setTodayHistory(history.record(title, uri))
    }

    /**
     * 蒸留保存後の本文差し替え。対象ノートが変わっていたら何もせず false を返す。
     * 生Markdown文脈に依存するチャット・クイズだけを破棄し、ノート全体のAI結果は維持する。
     */
    fun applyReloadedBody(targetUri: String, loaded: NoteState.Success): Boolean {
        val latest = currentNote() ?: return false
        if (latest.targetUri != targetUri) return false
        // raw Markdownを保持しているジョブを先に止め、旧文脈の結果が後着しないようにする。
        sectionChat.cancelAndClear()
        quiz.cancelAndClear()
        return stateStore.applyReloadedBody(targetUri, loaded)
    }

    // ── 要約・関連ノート ────────────────────────────────────────────────────

    fun fetchSummary(title: String, content: String) = summary.fetch(title, content)

    fun setRelatedNotesState(state: RelatedNotesState) {
        stateStore.setRelatedNotesState(state)
    }

    fun setWikilinkTitles(titles: Set<String>) {
        stateStore.setWikilinkTitles(titles)
    }

    // ── 読書痕跡（実装は ReadingTraceController）────────────────────────────

    /** @return 読書セッションの識別子（[bindReadingTracePath] に渡す）。 */
    fun startReadingTrace(title: String, vaultRelativePath: String?, documentId: String?): Long =
        readingTrace.onNoteOpened(
            vaultRelativePath = vaultRelativePath,
            noteTitle = title,
            documentId = documentId
        )

    fun bindReadingTracePath(sessionId: Long, path: String) = readingTrace.bindPath(sessionId, path)
    fun revealReadingTrace(vaultRelativePath: String) = readingTrace.revealTrace(vaultRelativePath)
    fun reportReadingProgress(
        blockIndex: Int,
        blockFraction: Float,
        totalBlocks: Int,
        sectionTitle: String?
    ) = readingTrace.onReadingProgress(blockIndex, blockFraction, totalBlocks, sectionTitle)
    fun pauseReadingTrace() = readingTrace.pause()
    fun resumeReadingTrace() = readingTrace.resume()
    fun dismissReadingTraceCard() = readingTrace.dismissCard()

    // ── さがすタブ（実装は SearchController）────────────────────────────────

    fun loadFolders(contentResolver: ContentResolver) = search.loadFolders(contentResolver)
    fun selectSearchFolder(folder: NoteFolder?) = search.selectFolder(folder)
    fun searchByKeyword(contentResolver: ContentResolver, query: String) =
        search.searchByKeyword(contentResolver, query)
    fun pickRandomInScope(contentResolver: ContentResolver) = search.pickRandomInScope(contentResolver)

    // ── クイズ（実装は QuizController）──────────────────────────────────────

    fun generateQuiz(sourceLabel: String, context: String) = quiz.create(sourceLabel, context)
    fun markQuizViewed() = quiz.markViewed()

    // ── AI補記メモ（実装は AnnotationController）────────────────────────────

    fun loadAnnotations(contentResolver: ContentResolver) = annotation.loadList(contentResolver)
    fun deleteAnnotation(contentResolver: ContentResolver, uri: Uri) =
        annotation.delete(contentResolver, uri)
    fun deleteAllAnnotations(contentResolver: ContentResolver) = annotation.deleteAll(contentResolver)
    fun markAnnotationViewed() = annotation.markViewed()
    fun createAnnotation(
        contentResolver: ContentResolver,
        title: String,
        content: String,
        summary: String?,
        relatedNotes: List<RelatedNote>,
        aiNotes: List<RelatedNote>,
        wikilinkTitles: Set<String>
    ) = annotation.create(
        contentResolver, title, content, summary, relatedNotes, aiNotes, wikilinkTitles
    )

    // ── 蒸留（実装は DistillController）─────────────────────────────────────

    fun checkDistillRecovery() = distill.checkRecovery()
    fun startDistill() = distill.start()
    fun downloadDistillModel() = distill.downloadModelAndResume()
    fun toggleDistillCandidate(id: String) = distill.toggleCandidate(id)
    fun saveDistillSelection() = distill.saveSelection()
    fun retryDistill() = distill.retry()
    fun dismissDistillResult() = distill.dismissResult()
    fun keepCurrentAfterDistillRecovery() = distill.keepCurrentAndFinishRecovery()
    fun restoreDistillOriginal() = distill.restoreOriginal()
    fun exportDistillOriginal(write: suspend (ByteArray) -> Unit) = distill.exportOriginal(write)

    // ── セクション単位のAIチャット（実装は SectionChatController）────────────

    /**
     * 吹き出しから新しいセクション文脈を開くとき、前のセクションで作ったクイズを
     * 持ち越さない（別セクションの古いクイズがシートに残り続ける問題の防止）。
     * 既存セッションの再表示（sectionChat != null）ではクイズも保持する。
     */
    fun openSection(section: NoteSection) {
        if (!stateStore.hasSectionChat()) quiz.cancelAndClear()
        sectionChat.open(section)
    }

    fun showSectionChat() = sectionChat.showSheet()
    fun sendSectionMessage(text: String) = sectionChat.sendMessage(text)
    fun dismissSectionChatSheet() = sectionChat.dismissSheet()

    /** セッションの明示終了。文脈が閉じるので、そのセッションで作ったクイズも破棄する。 */
    fun endSectionChat() {
        sectionChat.cancelAndClear()
        quiz.cancelAndClear()
    }
}
