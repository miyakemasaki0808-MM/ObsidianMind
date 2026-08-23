package com.example.newproject.controller

import com.example.newproject.ai.AiClient
import com.example.newproject.data.DistillPersistence
import com.example.newproject.data.HistoryStore
import com.example.newproject.model.DocumentRef
import com.example.newproject.model.NoteFolder
import com.example.newproject.model.NotePaperTone
import com.example.newproject.data.NoteRepository
import com.example.newproject.data.VaultBrowser
import com.example.newproject.data.ReadingTracePersistence
import com.example.newproject.model.RelatedNote
import com.example.newproject.domain.SearchPickerUseCase
import com.example.newproject.domain.SummarizeUseCase
import com.example.newproject.domain.markdown.NoteSection
import com.example.newproject.domain.markdown.NoteSectionModel
import com.example.newproject.model.NoteUiState
import com.example.newproject.model.NoteUiStateStore
import com.example.newproject.model.state.NoteState
import com.example.newproject.model.state.RelatedNotesState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex

/**
 * 機能Controllerを束ね、**Controller間の調停**（ノート切替・Vault切替での
 * 一斉停止と一斉初期化）を担う。画面状態 [NoteUiState] の唯一の持ち主でもある。
 * **件数は書かない** — 足すたびに直す羽目になり、実際に古いまま残っていた。
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
    vaultBrowser: VaultBrowser,
    aiClient: AiClient,
    summarizeUseCase: SummarizeUseCase,
    searchPickerUseCase: SearchPickerUseCase,
    distillPersistence: DistillPersistence,
    readingTracePersistence: ReadingTracePersistence,
    private val history: HistoryStore,
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
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /**
     * 表示用Markdownの解析を載せるディスパッチャ。既定は [NoteSectionController] 側の
     * `Dispatchers.Default`。テストがテストスケジューラへ差し替えるための口。
     */
    parseDispatcher: CoroutineDispatcher = Dispatchers.Default
) {

    private val stateStore = NoteUiStateStore(initialState)
    val uiState: StateFlow<NoteUiState> = stateStore.uiState

    /**
     * 表示用Markdownのパース結果。`NoteUiState` ではなく独立した [StateFlow] なのは、
     * `model` パッケージが葉で `domain` を import できないため（→ [NoteSectionController]）。
     */
    val sectionModel: StateFlow<NoteSectionModel?> get() = sections.model

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
    // sections だけは NoteUiState の外に状態を持つ（理由は sectionModel のKDoc）。
    private val sections = NoteSectionController(scope, parseDispatcher)
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
        vault = vaultBrowser,
        state = stateStore.annotationListWriter,
        vaultGeneration = { vaultGeneration }
    )
    private val search = SearchController(
        scope = scope,
        vault = vaultBrowser,
        searchPickerUseCase = searchPickerUseCase,
        state = stateStore.searchWriter,
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
    /**
     * 痕跡サイドカーの read-modify-write を直列化する錠。
     *
     * **同じ錠を read-modify-write する全経路へ配る。** 訪問の追記（[ReadingTraceController]）と
     * 読み戻しの適用（[ReadingTraceBackupController]）はまったく同じ形で同じファイルを書くので、
     * 錠をクラスごとに持つと「錠はあるのに守られていない」状態になる。
     */
    private val traceWriteMutex = Mutex()

    private val readingTrace = ReadingTraceController(
        scope = scope,
        persistScope = persistScope,
        aiClient = aiClient,
        state = stateStore.readingTraceWriter,
        persistence = readingTracePersistence,
        currentVaultKey = currentVaultKey,
        clock = clock,
        ioDispatcher = ioDispatcher,
        writeMutex = traceWriteMutex
    )

    /**
     * ノートへのひとこと。**[readingTrace] より後に宣言する** — 生成結果の預け先が
     * 痕跡側のセッションなので、初期化順を逆にすると参照が未初期化になる。
     */
    private val remark = RemarkController(
        scope = scope,
        aiClient = aiClient,
        state = stateStore.remarkWriter,
        // 保存は痕跡の書き込み契機（背面化・離脱）へ相乗りさせる。
        // ここで直接保存すると、痕跡ファイルが未作成の初読で黙って失われる。
        onRemarkReady = { readingTrace.setPendingRemark(it) },
        persistReply = { path, reply, at -> readingTrace.saveReply(path, reply, at) },
        loadReflection = { path -> readingTrace.loadReflection(path) },
        currentContent = { stateStore.currentNote()?.content },
        persistMirrored = { path, mirrored -> readingTrace.saveMirrored(path, mirrored) },
        clock = clock
    )

    /**
     * 読書痕跡の整理。**Vault単位**なのでノート単位の契約へは登録しない
     * （ノートを開き直しただけで洗い出しが消えるのは誤り）。
     */
    private val readingTraceCleanup = ReadingTraceCleanupController(
        scope = scope,
        vault = vaultBrowser,
        persistence = readingTracePersistence,
        state = stateStore.readingTraceCleanupWriter,
        currentVaultKey = currentVaultKey,
        vaultGeneration = { vaultGeneration }
    )

    /**
     * 読書痕跡の退避。**整理と同じくVault単位**なのでノート単位の契約へは登録しない。
     * 別のControllerにしているのは、片方の状態がもう片方の操作で消えないようにするため。
     */
    private val readingTraceBackup = ReadingTraceBackupController(
        scope = scope,
        persistence = readingTracePersistence,
        state = stateStore.readingTraceBackupWriter,
        currentVaultKey = currentVaultKey,
        vaultGeneration = { vaultGeneration },
        clock = clock,
        writeMutex = traceWriteMutex
    )

    fun assessReadingTraceOrphans() = readingTraceCleanup.assess()

    fun deleteReadingTrace(key: String) = readingTraceCleanup.delete(key)

    // ── 読書痕跡の退避（実装は ReadingTraceBackupController・Vault単位）──────

    fun exportReadingTraces(write: suspend (ByteArray) -> Unit) = readingTraceBackup.export(write)
    fun prepareReadingTraceImport(read: suspend () -> ByteArray) =
        readingTraceBackup.prepareImport(read)
    fun applyReadingTraceImport() = readingTraceBackup.applyImport()
    fun cancelReadingTraceBackup() = readingTraceBackup.cancel()
    fun dismissReadingTraceBackup() = readingTraceBackup.dismiss()

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
        sections.cancelAndClear()
        summary.cancelAndClear()
        quiz.cancelAndClear()
        // 補記一覧（annotation）はVault単位なのでここには登録しない。
        remark.cancelAndClear()
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
        readingTraceCleanup.onVaultChanged()
        readingTraceBackup.onVaultChanged()
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

    /**
     * ノート本文の状態を反映し、表示用Markdownの解析を始める。
     *
     * **本文が変わる経路は2つしかない**（ここと [applyReloadedBody]）。解析の開始を
     * 呼び出し側へ配らずこの2つへ集約するのは、片方を足し忘れると
     * 「本文は新しいのにブロックが旧いまま」になるため。Success 以外では
     * ブロックを持たないので破棄する（プレースホルダ本文はここでは解析しない）。
     */
    fun setNoteState(state: NoteState) {
        stateStore.setNoteState(state)
        if (state is NoteState.Success) sections.parse(state.content) else sections.cancelAndClear()
    }

    /**
     * 紙の地色の段階を決める。**[setNoteState] より先に呼ぶ**（理由は Store 側のKDoc）。
     * 段階の算出そのものはVault走査の結果が要るので、材料を持つ呼び出し側が行う。
     */
    fun setNotePaperTone(tone: NotePaperTone) {
        stateStore.setNotePaperTone(tone)
    }

    fun currentNote(): NoteState.Success? = stateStore.currentNote()

    /** ノートを開けた時点で当日履歴に積む。 */
    fun recordHistory(title: String, ref: DocumentRef) {
        stateStore.setTodayHistory(history.record(title, ref))
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
        val applied = stateStore.applyReloadedBody(targetUri, loaded)
        // 本文が変わったので解析し直す。ここを落とすと太字化した本文に対して
        // 旧いブロックが描かれ続ける（[setNoteState] と対になる2つ目の経路）。
        if (applied) sections.parse(loaded.content)
        return applied
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
    fun revealReadingTrace(vaultRelativePath: String, content: String) =
        readingTrace.revealTrace(vaultRelativePath, content)

    fun toggleReadingTraceMark() = readingTrace.toggleMark()
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

    fun loadFolders() = search.loadFolders()
    fun selectSearchFolder(folder: NoteFolder?) = search.selectFolder(folder)
    fun searchByKeyword(query: String) = search.searchByKeyword(query)
    fun pickRandomInScope() = search.pickRandomInScope()

    // ── クイズ（実装は QuizController）──────────────────────────────────────

    fun generateQuiz(sourceLabel: String, context: String) = quiz.create(sourceLabel, context)
    fun markQuizViewed() = quiz.markViewed()

    // ── ノートへのひとこと（実装は RemarkController）─────────────────────────

    fun createRemark(
        title: String,
        content: String,
        relatedNotes: List<RelatedNote>,
        aiNotes: List<RelatedNote>
    ) = remark.create(title, content, relatedNotes, aiNotes)

    /** 専用画面を開いたとき、保存済みの組を読み戻す。パスは読書セッションから引く。 */
    fun restoreSavedRemark(title: String) =
        remark.restoreSaved(readingTrace.currentPath(), title)

    fun saveRemarkReply(reply: String) =
        remark.saveReply(readingTrace.currentPath(), reply)

    // ── 旧補記ファイルの片付け（実装は AnnotationController・Vault単位）──────

    fun loadAnnotations() = annotation.loadList()
    fun deleteAnnotation(ref: DocumentRef) = annotation.delete(ref)
    fun deleteAllAnnotations() = annotation.deleteAll()

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
    /** 要約エリアの再試行。開いているセクションのまま作り直す。 */
    fun retrySectionSummary() = sectionChat.retrySummary()
    /** Q&Aログの再試行。答えを返せていない質問だけを作り直す。 */
    fun retrySectionAnswer() = sectionChat.retryAnswer()
    fun dismissSectionChatSheet() = sectionChat.dismissSheet()

    /** セッションの明示終了。文脈が閉じるので、そのセッションで作ったクイズも破棄する。 */
    fun endSectionChat() {
        sectionChat.cancelAndClear()
        quiz.cancelAndClear()
    }
}
