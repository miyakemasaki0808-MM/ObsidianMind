package com.example.newproject

import com.example.newproject.controller.AnnotationController
import com.example.newproject.controller.DistillController
import com.example.newproject.controller.QuizController
import com.example.newproject.controller.ReadingTraceController
import com.example.newproject.controller.SearchController
import com.example.newproject.controller.SectionChatController
import com.example.newproject.controller.SummaryController
import com.example.newproject.controller.NOTES_CACHE_TTL_MS
import com.example.newproject.data.DistillPersistence
import com.example.newproject.data.DistillRecoveryStore
import com.example.newproject.data.DistillWriteRepository
import com.example.newproject.data.InvalidNoteEncodingException
import com.example.newproject.data.NoteFile
import com.example.newproject.data.NoteFileTooLargeException
import com.example.newproject.data.NoteFolder
import com.example.newproject.data.NoteHistoryStore
import com.example.newproject.data.NoteRepository
import com.example.newproject.data.ReadingTraceStore
import com.example.newproject.data.SafDistillDocumentGateway
import com.example.newproject.data.SafReadingTraceDocumentGateway
import com.example.newproject.model.AnnotationState
import com.example.newproject.model.NoteState
import com.example.newproject.model.NoteUiState
import com.example.newproject.model.QuizState
import com.example.newproject.model.RelatedNotesState
import com.example.newproject.model.SearchState
import com.example.newproject.model.SummaryState
import com.example.newproject.model.withDistillBodyReloaded
import com.example.newproject.ui.screen.NoteReaderTab
import android.app.Application
import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.newproject.ai.AICoreClient
import com.example.newproject.ai.AiClient
import com.example.newproject.domain.RelatedNote
import com.example.newproject.domain.RelatedNotesResult
import com.example.newproject.domain.RelatedNotesUseCase
import com.example.newproject.domain.SearchPickerUseCase
import com.example.newproject.domain.SummarizeUseCase
import com.example.newproject.domain.DistillLimits
import com.example.newproject.domain.markdown.NoteSection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// 状態定義は NoteUiState.kt を参照。

class NoteViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences(PREFS_NAME, Application.MODE_PRIVATE)
    private val history = NoteHistoryStore(prefs)
    private val repository = NoteRepository()
    private val aiClient: AiClient = AICoreClient()
    private val summarizeUseCase = SummarizeUseCase(aiClient)
    private val relatedNotesUseCase = RelatedNotesUseCase(aiClient)
    private val searchPickerUseCase = SearchPickerUseCase(aiClient)

    private val _uiState = MutableStateFlow(NoteUiState())
    val uiState: StateFlow<NoteUiState> = _uiState.asStateFlow()

    // 機能ごとのController。scope と状態Flowを共有し、担当領域の状態のみ更新する
    private val sectionChat = SectionChatController(viewModelScope, aiClient, _uiState)
    private val quiz = QuizController(viewModelScope, aiClient, _uiState)
    private val summary = SummaryController(
        scope = viewModelScope,
        summarizeUseCase = summarizeUseCase,
        aiClient = aiClient,
        uiState = _uiState,
        // 関連ノートは走査キャッシュ（Uriを持つ NoteFile）に依存するためViewModel側に残す。
        // モデルDL完了で要約が再開されるとき、同じ入力で関連ノートも呼び戻す。
        onModelReady = { title, content -> fetchRelatedNotes(title, content) }
    )
    private val annotation = AnnotationController(
        scope = viewModelScope,
        repository = repository,
        aiClient = aiClient,
        uiState = _uiState,
        vaultUri = { vaultUri },
        vaultGeneration = { vaultGeneration }
    )
    private val search = SearchController(
        scope = viewModelScope,
        repository = repository,
        searchPickerUseCase = searchPickerUseCase,
        uiState = _uiState,
        vaultUri = { vaultUri },
        vaultGeneration = { vaultGeneration }
    )
    private val distillPersistence: DistillPersistence = DistillWriteRepository(
        gateway = SafDistillDocumentGateway(application.contentResolver),
        recoveryStore = DistillRecoveryStore(application.noBackupFilesDir),
        cacheDirectory = java.io.File(application.cacheDir, "distill")
    )
    private val distill = DistillController(
        scope = viewModelScope,
        aiClient = aiClient,
        uiState = _uiState,
        persistence = distillPersistence,
        reloadBody = ::reloadNoteBody
    )
    private val readingTrace = ReadingTraceController(
        scope = viewModelScope,
        aiClient = aiClient,
        uiState = _uiState,
        persistence = ReadingTraceStore(
            SafReadingTraceDocumentGateway(application.contentResolver) { vaultUri }
        ),
        currentVaultKey = { vaultUri?.toString() }
    )

    private var cachedNotes: List<NoteFile> = emptyList()
    private var cachedNotesLoadedAt = 0L

    // ノート切替時に前のノートのAI応答が後から届いて上書きしないよう、
    // 実行中ジョブを保持して新規要求時にキャンセルする
    // （要約・セクションチャット等のジョブは各Controllerが保持）
    private var noteLoadJob: Job? = null
    private var relatedNotesJob: Job? = null

    // 更新はメインスレッドだが、読み取りは痕跡保存などIOスレッドからも走る。
    // 可視性を保証しないと、切替がIO側へいつ伝わるか決まらない。
    @Volatile
    var vaultUri: Uri? = null
        private set

    // Vault単位の非同期要求の世代。saveVault() のたびに進めて、補記一覧・フォルダ一覧の
    // 結果が旧Vaultのものでないかを Controller 側が update 直前に照合する。
    //
    // vaultUri の比較で代用しないのは、A→B→A と選び直したときに同じ値になるため。
    // 選び直しでも cachedNotes とスコープキャッシュは破棄されるので、無効化したい。
    //
    // ノート単位の世代は各Controllerが activeRequestId として自前で持つ（寿命が違う。
    // 補記一覧はノート切替では無効化してはいけない）。
    private var vaultGeneration = 0L

    init {
        restoreTheme()
        restoreVault()
        distill.checkRecovery()
    }

    private fun restoreTheme() {
        val dark = prefs.getBoolean(KEY_DARK_THEME, false)
        if (dark) _uiState.update { it.copy(darkTheme = true) }
    }

    /**
     * 表示テーマを切り替える。OS設定には追従しないので、ここが唯一の切替点。
     * 端末に残すのは真偽値1つだけで、Vaultにも痕跡にも書かない。
     */
    fun setDarkTheme(enabled: Boolean) {
        if (_uiState.value.darkTheme == enabled) return
        prefs.edit().putBoolean(KEY_DARK_THEME, enabled).apply()
        _uiState.update { it.copy(darkTheme = enabled) }
    }

    private fun restoreVault() {
        val savedUri = prefs.getString(KEY_VAULT_URI, null) ?: return
        vaultUri = Uri.parse(savedUri)
        _uiState.update { current ->
            current.copy(
                vaultSelected = true,
                todayHistory = history.load()
            )
        }
    }

    // ノートを開けた時点で当日履歴に積む（loadRandomNote / openNote の成功時に呼ぶ）
    private fun recordHistory(title: String, uri: Uri) {
        _uiState.update { current -> current.copy(todayHistory = history.record(title, uri)) }
    }

    fun saveVault(uri: Uri) {
        // 保存先は書き込み時点の vaultUri から解決されるため、切替前に記録中の
        // セッションを捨てる。捨てないと旧ノートの痕跡が新Vaultへ書き込まれる。
        readingTrace.discard()
        // 走行中のVault単位要求を無効化する。cancel より先に進めておかないと、
        // すでに結果を持ち帰っている要求が旧世代のまま素通りする。
        vaultGeneration++
        vaultUri = uri
        prefs.edit().putString(KEY_VAULT_URI, uri.toString()).apply()
        cachedNotes = emptyList()
        cachedNotesLoadedAt = 0L
        relatedNotesUseCase.clearCache()
        search.onVaultChanged()
        annotation.onVaultChanged()
        cancelNoteScopedJobs()
        // 旧VaultのURIは新Vaultでは開けないため、閲覧履歴も破棄する
        history.clear()
        // Vault切替時はノート単位の状態に加え、さがすタブのスコープも破棄する
        // （selectedFolder は旧Vaultの documentId を保持しているため必須）
        _uiState.update { current ->
            current.resetNoteScopedStates().copy(
                vaultSelected = true,
                folders = emptyList(),
                selectedFolder = null,
                foldersError = null,
                searchState = SearchState.Idle,
                todayHistory = emptyList()
            )
        }
        distill.checkRecovery()
    }

    // ノートを開き直す・Vaultを切り替える際に、ノート単位の状態をまとめて初期化する。
    // リセットをここに集約することで、状態を追加したときのリセット漏れを防ぐ。
    private fun NoteUiState.resetNoteScopedStates(): NoteUiState = copy(
        summaryState = SummaryState.Idle,
        relatedNotesState = RelatedNotesState.Idle,
        quizState = QuizState.Idle,
        annotationState = AnnotationState.Idle,
        sectionChat = null,
        isSectionChatSheetVisible = false,
        // ここで必ず消えることが「カードは Rediscover でしか出ない」の担保になっている
        // （設定するのは loadRandomNote だけ）。由来フラグを別に持たない理由。
        readingTraceCard = null
    )

    // ノート単位の実行中AIジョブをまとめて止める（状態リセットと対で呼ぶ）。
    // 旧ノートの生成が残っていると、結果の上書きだけでなく generate() の
    // 直列化ロックを握り続けて新ノートの要約開始も遅らせてしまう。
    private fun cancelNoteScopedJobs() {
        // ここは「ノートを離れる」唯一の合流点（loadRandomNote / openNote の先頭）なので、
        // 離脱フックを別に設けず読書痕跡の確定もここで行う。
        // flush は自前のスナップショットで書くため、以降のキャンセルに影響されない。
        readingTrace.flush()
        readingTrace.cancelForNoteChange()
        noteLoadJob?.cancel()
        relatedNotesJob?.cancel()
        summary.cancelAndClear()
        quiz.cancelAndClear()
        annotation.cancelAndClear()
        sectionChat.cancelAndClear()
        distill.cancelForNoteChange()
    }

    // Vault全体のノート一覧をTTL付きで取得する。期限内は cachedNotes を再利用し、
    // ランダム表示の連打や関連ノートの補填で毎回の全走査を避ける。
    private suspend fun collectAllNotesCached(
        contentResolver: ContentResolver,
        vaultUri: Uri
    ): List<NoteFile> {
        val now = System.currentTimeMillis()
        if (cachedNotes.isNotEmpty() && now - cachedNotesLoadedAt < NOTES_CACHE_TTL_MS) {
            return cachedNotes
        }
        val notes = repository.collectNotes(contentResolver, vaultUri)
        cachedNotes = notes
        cachedNotesLoadedAt = now
        return notes
    }

    fun loadRandomNote(contentResolver: ContentResolver) {
        val uri = vaultUri ?: return
        cancelNoteScopedJobs()
        noteLoadJob = viewModelScope.launch {
            _uiState.update { current ->
                current.resetNoteScopedStates().copy(noteState = NoteState.Loading)
            }
            try {
                val notes = collectAllNotesCached(contentResolver, uri)
                if (notes.isEmpty()) {
                    _uiState.update { current -> current.copy(noteState = NoteState.Empty) }
                    return@launch
                }
                val note = notes.random()
                val loaded = loadNoteForDistill(contentResolver, note.name, note.uri)
                // 本文を出す前にセッションを作る。表示後だと、痕跡レポータの初回emitが
                // セッションより先に届いて訪問を取りこぼしうる。
                // 走査で得た NoteFile なので相対パスは常に揃っている。
                startReadingTrace(note.name, note.uri, note.vaultRelativePath)
                _uiState.update { current -> current.copy(noteState = loaded) }
                recordHistory(note.name, note.uri)
                // 「前回のあなた」カードは Rediscover 経路だけで出す。openNote では呼ばない。
                readingTrace.revealTrace(note.vaultRelativePath)
                summary.fetch(note.name, loaded.content)
                fetchRelatedNotes(note.name, loaded.content)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { current ->
                    current.copy(noteState = NoteState.Error(e.message ?: "Unknown error"))
                }
            }
        }
    }

    fun openNote(contentResolver: ContentResolver, note: RelatedNote) {
        cancelNoteScopedJobs()
        noteLoadJob = viewModelScope.launch {
            _uiState.update { current ->
                current.resetNoteScopedStates().copy(noteState = NoteState.Loading)
            }
            try {
                val loaded = loadNoteForDistill(contentResolver, note.title, note.uri)
                // RelatedNote は相対パスを持たない。キャッシュにあれば即使い、無ければ
                // パス未確定でセッションだけ作る（表示前にVault走査を挟まないため）。
                // 本文を出す前に呼ぶ理由は loadRandomNote 側のコメント参照。
                val sessionId = startReadingTrace(note.title, note.uri, cachedRelativePath(note.uri))
                _uiState.update { current -> current.copy(noteState = loaded) }
                recordHistory(note.title, note.uri)
                // 表示を終えてから相対パスを確定させる。さがすタブは collectNotesInScope を
                // 使い cachedNotes を温めないため、ここで走査しないとセッションごとに
                // 「さがす経由の最初の1件」が記録から漏れる。
                bindReadingTracePath(contentResolver, note.uri, sessionId)
                summary.fetch(note.title, loaded.content)
                fetchRelatedNotes(note.title, loaded.content)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { current ->
                    current.copy(noteState = NoteState.Error(e.message ?: "Unknown error"))
                }
            }
        }
    }

    // ── 読書痕跡（実装は ReadingTraceController）────────────────────────────────

    /** 最終可視ブロックと、そのブロックの可視割合の報告。NoteReaderTab がスクロールに追従して呼ぶ。 */
    fun reportReadingProgress(
        blockIndex: Int,
        blockFraction: Float,
        totalBlocks: Int,
        sectionTitle: String?
    ) = readingTrace.onReadingProgress(blockIndex, blockFraction, totalBlocks, sectionTitle)

    /** アプリが背面へ回るときに呼ぶ（ノート表示中のまま離れた訪問を取りこぼさないため）。 */
    fun pauseReadingTrace() = readingTrace.pause()

    /** 背面から復帰したときに呼ぶ（背面にいた時間を読書時間に含めないため）。 */
    fun resumeReadingTrace() = readingTrace.resume()

    /** 「読んだ」でカードを畳む。永続化しないので次回 Rediscover では再表示される。 */
    fun dismissReadingTraceCard() = readingTrace.dismissCard()

    /** @return 読書セッションの識別子（[bindReadingTracePath] に渡す）。 */
    private fun startReadingTrace(title: String, uri: Uri, vaultRelativePath: String?): Long =
        readingTrace.onNoteOpened(
            vaultRelativePath = vaultRelativePath,
            noteTitle = title,
            // 端末内キャッシュとしてだけ持つ値なので、取れなければ null で構わない。
            documentId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull()
        )

    /**
     * uri から vault相対パスを引く。既に走査済みのキャッシュだけを見て、**I/Oはしない**。
     *
     * 表示前にVault全走査を挟むとノート表示が遅れ「本質（ノートを読む）を妨げない」に反する。
     * 加えて、ここで suspend すると uiState 更新とセッション開始の間でメインスレッドを譲り、
     * 痕跡レポータの初回emitを取りこぼして訪問がまるごと記録されなくなる。
     * キャッシュが冷えている場合は [bindReadingTracePath] が表示後に埋める。
     */
    private fun cachedRelativePath(uri: Uri): String? =
        cachedNotes.firstOrNull { it.uri == uri }
            ?.vaultRelativePath
            ?.takeIf { it.isNotEmpty() }

    /**
     * 表示後に相対パスを確定させる。走査はTTLキャッシュ付きなので通常は追加I/Oなし。
     * `_AI補記` 配下は collectNotes の対象外なので見つからず、痕跡も残らない（意図どおり）。
     */
    private suspend fun bindReadingTracePath(
        contentResolver: ContentResolver,
        uri: Uri,
        sessionId: Long
    ) {
        val vault = vaultUri ?: return
        val path = try {
            collectAllNotesCached(contentResolver, vault)
                .firstOrNull { it.uri == uri }
                ?.vaultRelativePath
                ?.takeIf { it.isNotEmpty() }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: return
        readingTrace.bindPath(sessionId, path)
    }

    // ── さがすタブ（実装は SearchController）──────────────────────────────────

    fun loadFolders(contentResolver: ContentResolver) = search.loadFolders(contentResolver)
    fun selectSearchFolder(folder: NoteFolder?) = search.selectFolder(folder)
    fun searchByKeyword(contentResolver: ContentResolver, query: String) = search.searchByKeyword(contentResolver, query)
    fun pickRandomInScope(contentResolver: ContentResolver) = search.pickRandomInScope(contentResolver)

    // sourceLabel=対象セクション名、context=フォーカス周辺テキスト（NoteReaderTab が構築）
    fun generateQuiz(sourceLabel: String, context: String) = quiz.create(sourceLabel, context)
    fun markQuizViewed() = quiz.markViewed()

    // ── AI補記メモ（実装は AnnotationController）───────────────────────────────

    fun loadAnnotations(contentResolver: ContentResolver) = annotation.loadList(contentResolver)
    fun deleteAnnotation(contentResolver: ContentResolver, uri: Uri) = annotation.delete(contentResolver, uri)
    fun deleteAllAnnotations(contentResolver: ContentResolver) = annotation.deleteAll(contentResolver)
    fun markAnnotationViewed() = annotation.markViewed()

    // ── 蒸留（実装は DistillController）──────────────────────────────────────

    fun startDistill() = distill.start()
    fun downloadDistillModel() = distill.downloadModelAndResume()
    fun toggleDistillCandidate(id: String) = distill.toggleCandidate(id)
    fun saveDistillSelection() = distill.saveSelection()
    fun retryDistill() = distill.retry()
    fun dismissDistillResult() = distill.dismissResult()
    fun keepCurrentAfterDistillRecovery() = distill.keepCurrentAndFinishRecovery()
    fun restoreDistillOriginal() = distill.restoreOriginal()
    fun exportDistillOriginal(contentResolver: ContentResolver, destination: Uri) =
        distill.exportOriginal { bytes -> repository.writeDocumentBytes(contentResolver, destination, bytes) }

    // ── セクション単位のAIチャット（実装は SectionChatController）─────────────

    /**
     * 吹き出しから新しいセクション文脈を開くとき、前のセクションで作ったクイズを
     * 持ち越さない（別セクションの古いクイズがシートに残り続ける問題の防止）。
     * 既存セッションの再表示（sectionChat != null）ではクイズも保持する。
     */
    fun openSection(section: NoteSection) {
        if (_uiState.value.sectionChat == null) quiz.cancelAndClear()
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

    fun createAnnotation(
        contentResolver: ContentResolver,
        title: String,
        content: String,
        summary: String?,
        relatedNotes: List<RelatedNote>,
        aiNotes: List<RelatedNote>,
        wikilinkTitles: Set<String>
    ) = annotation.create(contentResolver, title, content, summary, relatedNotes, aiNotes, wikilinkTitles)

    private suspend fun loadNoteForDistill(
        contentResolver: ContentResolver,
        title: String,
        uri: Uri
    ): NoteState.Success {
        return try {
            val snapshot = repository.readNoteSnapshot(
                contentResolver,
                uri,
                DistillLimits.MAX_FILE_BYTES + DISTILL_OUTPUT_GROWTH_BYTES
            )
            val tooLarge = snapshot.bytes.size > DistillLimits.MAX_FILE_BYTES
            NoteState.Success(
                title = title,
                content = snapshot.content,
                targetUri = uri.toString(),
                originalHash = snapshot.hash,
                distillUnavailableReason = if (tooLarge) {
                    "このノートは256KBを超えるため蒸留できません。"
                } else null
            )
        } catch (error: NoteFileTooLargeException) {
            displayFallback(
                contentResolver = contentResolver,
                title = title,
                uri = uri,
                reason = "このノートは256KBを超えるため蒸留できません。"
            )
        } catch (error: InvalidNoteEncodingException) {
            displayFallback(
                contentResolver = contentResolver,
                title = title,
                uri = uri,
                reason = "このノートはUTF-8として安全に確認できないため蒸留できません。"
            )
        }
    }

    /**
     * 蒸留できないノートを、表示だけはできるように読み直す。
     *
     * ここへ落ちてくるのは一番大きいノートなので読込にも上限がある。切り詰めたときは
     * 黙って先頭だけ見せるのではなく、蒸留できない理由と一緒にその旨を伝える
     * （この経路は元々理由を表示しているので、新しいUIの受け皿は要らない）。
     */
    private suspend fun displayFallback(
        contentResolver: ContentResolver,
        title: String,
        uri: Uri,
        reason: String
    ): NoteState.Success {
        val loaded = repository.readNoteForDisplay(contentResolver, uri)
        return NoteState.Success(
            title = title,
            content = loaded.text,
            targetUri = uri.toString(),
            distillUnavailableReason = if (loaded.isTruncated) {
                "$reason 大きすぎるため先頭1MBのみ表示しています。"
            } else {
                reason
            }
        )
    }

    /**
     * 同一ノートの本文と基準ハッシュを更新する。
     * ノート全体のAI結果は維持し、生Markdown文脈に依存するチャット・クイズだけを破棄する。
     */
    private suspend fun reloadNoteBody(targetUri: String, expectedHash: String?): Boolean {
        val current = _uiState.value.noteState as? NoteState.Success ?: return false
        if (current.targetUri != targetUri) return false
        return try {
            val loaded = loadNoteForDistill(
                getApplication<Application>().contentResolver,
                current.title,
                Uri.parse(targetUri)
            )
            if (expectedHash != null && loaded.originalHash != expectedHash) return false
            val latest = _uiState.value.noteState as? NoteState.Success ?: return false
            if (latest.targetUri != targetUri) return false
            // raw Markdownを保持しているジョブを先に止め、旧文脈の結果が後着しないようにする。
            sectionChat.cancelAndClear()
            quiz.cancelAndClear()
            _uiState.update { state ->
                val active = state.noteState as? NoteState.Success
                if (active?.targetUri != targetUri) state else state.withDistillBodyReloaded(loaded)
            }
            cachedNotes = emptyList()
            cachedNotesLoadedAt = 0L
            relatedNotesUseCase.clearCache()
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun fetchRelatedNotes(title: String, content: String) {
        relatedNotesJob?.cancel()
        relatedNotesJob = viewModelScope.launch {
            _uiState.update { current -> current.copy(relatedNotesState = RelatedNotesState.Loading) }

            // さがすタブ等、loadRandomNote を経由しない導線では未収集のことがあるため補填する
            if (cachedNotes.isEmpty()) {
                val uri = vaultUri
                if (uri != null) {
                    try {
                        collectAllNotesCached(getApplication<Application>().contentResolver, uri)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        // 収集に失敗した場合は従来どおり候補なしとして扱う
                    }
                }
            }
            if (cachedNotes.isEmpty()) {
                _uiState.update { current ->
                    current.copy(
                        relatedNotesState = RelatedNotesState.Success(
                            relatedNotes = emptyList(),
                            aiNotes = emptyList()
                        )
                    )
                }
                return@launch
            }

            val wikilinkTitles = repository.parseMeta(content).wikilinkTitles
            _uiState.update { current -> current.copy(wikilinkTitles = wikilinkTitles) }
            val contentResolver = getApplication<Application>().contentResolver
            when (
                val result = relatedNotesUseCase.findRelated(
                    currentTitle = title,
                    currentContent = content,
                    allNotes = cachedNotes,
                    wikilinkTitles = wikilinkTitles,
                    // 候補はスニペットとfront matterしか使わないので、先頭だけ読む
                    readContent = { uri -> repository.readNoteSnippet(contentResolver, uri) },
                    parseMeta = { repository.parseMeta(it) }
                )
            ) {
                is RelatedNotesResult.Success -> {
                    _uiState.update { current ->
                        current.copy(
                            relatedNotesState = RelatedNotesState.Success(
                                relatedNotes = result.relatedNotes,
                                aiNotes = result.aiNotes,
                                aiStatus = result.aiStatus,
                                aiErrorMessage = result.aiErrorMessage
                            )
                        )
                    }
                }
                is RelatedNotesResult.Error -> {
                    _uiState.update { current ->
                        current.copy(relatedNotesState = RelatedNotesState.Error(result.message))
                    }
                }
            }
        }
    }

    companion object {
        private const val PREFS_NAME = "random_note_prefs"
        private const val KEY_VAULT_URI = "vault_uri"
        private const val KEY_DARK_THEME = "dark_theme"
        private const val DISTILL_OUTPUT_GROWTH_BYTES = DistillLimits.FINAL_SELECTION_LIMIT * 4
    }
}
