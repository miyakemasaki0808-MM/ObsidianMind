package com.example.newproject

import com.example.newproject.controller.NOTES_CACHE_TTL_MS
import com.example.newproject.controller.NoteSessionCoordinator
import com.example.newproject.data.NoteImageGateway
import com.example.newproject.data.VaultImageIndexStore
import com.example.newproject.ui.markdown.NoteImageLoader
import com.example.newproject.data.InvalidNoteEncodingException
import com.example.newproject.model.NoteFile
import com.example.newproject.model.NotePaperTone
import com.example.newproject.data.NoteFileTooLargeException
import com.example.newproject.model.NoteFolder
import com.example.newproject.model.state.NoteState
import com.example.newproject.model.NoteUiState
import com.example.newproject.model.state.RelatedNotesState
import com.example.newproject.ui.screen.NoteReaderTab
import android.app.Application
import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.net.toUri
import com.example.newproject.data.toUri as toDocumentUri
import com.example.newproject.model.DocumentRef
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.newproject.model.RelatedNote
import com.example.newproject.domain.RelatedNotesResult
import com.example.newproject.domain.notePaperTone
import com.example.newproject.domain.notePaperToneForCandidate
import com.example.newproject.model.DistillLimits
import com.example.newproject.domain.markdown.NoteSection
import com.example.newproject.domain.markdown.NoteSectionModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

// 状態定義は NoteUiState.kt を参照。

/**
 * 画面の窓口。**Android（SAF・SharedPreferences・Uri）に触れる仕事だけ**を持ち、
 * Controller間の調停と状態の所有は [NoteSessionCoordinator] へ委ねる。
 *
 * 手元に残しているのは、いずれも `Uri` か `ContentResolver` を解決する処理:
 * Vault走査のTTLキャッシュ・ノート本文の読込・関連ノート・痕跡の相対パス解決。
 */
class NoteViewModel internal constructor(
    application: Application,
    dependencies: NoteViewModelDependencies
) : AndroidViewModel(application) {

    constructor(application: Application) :
        this(application, NoteViewModelDependencies.default(application))

    private val preferences = dependencies.preferences
    private val repository = dependencies.repository
    private val relatedNotesUseCase = dependencies.relatedNotesUseCase
    private val vaultLocation = dependencies.vaultLocation

    /**
     * ノート単位ジョブを載せるスコープ。既定は `viewModelScope` で、
     * 差し替えられるのはテスト（`TestScope`）のため。
     * 痕跡の書き出しだけは寿命が違うので [readingTraceWriteScope] を使う。
     */
    private val scope: CoroutineScope = dependencies.scope ?: viewModelScope

    private val session = NoteSessionCoordinator(
        scope = scope,
        persistScope = readingTraceWriteScope,
        repository = dependencies.repository,
        vaultBrowser = dependencies.vaultBrowser,
        aiClient = dependencies.aiClient,
        summarizeUseCase = dependencies.summarizeUseCase,
        searchPickerUseCase = dependencies.searchPickerUseCase,
        distillPersistence = dependencies.distillPersistence,
        readingTracePersistence = dependencies.readingTracePersistence,
        history = dependencies.history,
        currentVaultKey = { vaultLocation.uri?.toString() },
        // 関連ノートは走査キャッシュ（Uriを持つ NoteFile）に依存するためViewModel側に残す。
        // モデルDL完了で要約が再開されるとき、同じ入力で関連ノートも呼び戻す。
        onModelReady = { title, content -> fetchRelatedNotes(title, content) },
        reloadBody = ::reloadNoteBody,
        cancelHostJobs = {
            noteLoadJob?.cancel()
            relatedNotesJob?.cancel()
        }
    )

    val uiState: StateFlow<NoteUiState> = session.uiState

    /**
     * 表示用Markdownのパース結果。Main の外で1回だけ作り、通常表示と全画面表示が共有する。
     * `uiState` と別の流れなのは `model` パッケージが `domain` を import できないため
     * （→ [com.example.newproject.controller.NoteSectionController]）。テーマと同じ扱い。
     */
    val sectionModel: StateFlow<NoteSectionModel?> = session.sectionModel

    /**
     * ノート内画像の読み込み口。**Vault世代を Coordinator から受けるのでここで組み立てる**
     * （依存の組み立て時点では `vaultGeneration` がまだ存在しない）。
     * 索引はVault単位なので、ノート単位の契約2箇所には登録しない。
     */
    internal val imageLoader: NoteImageLoader = AppNoteImageLoader(
        NoteImageGateway(
            contentResolver = application.contentResolver,
            indexStore = VaultImageIndexStore(
                vault = dependencies.vaultBrowser,
                vaultGeneration = { session.vaultGeneration }
            ),
            loadScope = scope
        )
    )

    private val mutableDarkTheme = MutableStateFlow(preferences.darkTheme)
    val darkTheme: StateFlow<Boolean> = mutableDarkTheme.asStateFlow()

    private val mutableNotePaperAging = MutableStateFlow(preferences.notePaperAging)
    val notePaperAging: StateFlow<Boolean> = mutableNotePaperAging.asStateFlow()

    private var cachedNotes: List<NoteFile> = emptyList()
    private var cachedNotesLoadedAt = 0L

    // ノート切替時に前のノートの読込・関連ノートが後から届いて上書きしないよう、
    // 実行中ジョブを保持して新規要求時にキャンセルする
    // （要約・セクションチャット等のジョブは各Controllerが保持）
    private var noteLoadJob: Job? = null
    private var relatedNotesJob: Job? = null

    /** 選択中Vault。痕跡のSAFゲートウェイと同じ実体を見る（[vaultLocation]）。 */
    val vaultUri: Uri?
        get() = vaultLocation.uri

    init {
        restoreVault()
        session.checkDistillRecovery()
    }

    /**
     * 表示テーマを切り替える。OS設定には追従しないので、ここが唯一の切替点。
     * 端末に残すのは真偽値1つだけで、Vaultにも痕跡にも書かない。
     */
    fun setDarkTheme(enabled: Boolean) {
        if (mutableDarkTheme.value == enabled) return
        preferences.darkTheme = enabled
        mutableDarkTheme.value = enabled
    }

    /**
     * 紙の地色の演出を切り替える。テーマ側の窓口が段階を色へ写す係なので、
     * **開いているノートを読み直さずに切替が反映される**（段階は `uiState` に載ったまま）。
     */
    fun setNotePaperAging(enabled: Boolean) {
        if (mutableNotePaperAging.value == enabled) return
        preferences.notePaperAging = enabled
        mutableNotePaperAging.value = enabled
    }

    private fun restoreVault() {
        val savedUri = preferences.vaultUri ?: return
        vaultLocation.uri = savedUri.toUri()
        session.onVaultRestored()
    }

    fun saveVault(uri: Uri) {
        // 新しいVaultを指すのは、記録中セッションの破棄と世代の採番が済んだ後。
        // 順序は調停クラス側（onVaultChanged）が持つ。
        session.onVaultChanged {
            vaultLocation.uri = uri
            preferences.vaultUri = uri.toString()
            cachedNotes = emptyList()
            cachedNotesLoadedAt = 0L
            relatedNotesUseCase.clearCache()
        }
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
        // 走査が部分的に失敗していても、読めた分でランダム表示・関連ノートを続ける
        // （止めるほうが体験として悪い）。完全性を要求するのは、不在を根拠に
        // 何かを消す処理だけ。→ VaultScan のKDoc
        val notes = repository.collectNotes(contentResolver, vaultUri).notes
        cachedNotes = notes
        cachedNotesLoadedAt = now
        return notes
    }

    fun loadRandomNote(contentResolver: ContentResolver) {
        val uri = vaultLocation.uri ?: return
        session.onNoteChanged()
        noteLoadJob = scope.launch {
            try {
                val notes = collectAllNotesCached(contentResolver, uri)
                if (notes.isEmpty()) {
                    session.setNoteState(NoteState.Empty)
                    return@launch
                }
                val note = notes.random()
                val loaded = loadNoteForDistill(contentResolver, note.name, note.ref)
                // 本文を出す前にセッションを作る。表示後だと、痕跡レポータの初回emitが
                // セッションより先に届いて訪問を取りこぼしうる。
                // 走査で得た NoteFile なので相対パスは常に揃っている。
                startReadingTrace(note.name, note.ref, note.vaultRelativePath)
                // 紙の地色は本文より先に決める（後だと現行色で1フレーム描かれる）。
                // この経路は走査結果を手元に持つので、常に段階が確定する。
                session.setNotePaperTone(notePaperTone(note.lastModified, notes.map { it.lastModified }))
                session.setNoteState(loaded)
                session.recordHistory(note.name, note.ref)
                // 「前回のあなた」カードは Rediscover 経路だけで出す。openNote では呼ばない。
                session.revealReadingTrace(note.vaultRelativePath)
                session.fetchSummary(note.name, loaded.content)
                fetchRelatedNotes(note.name, loaded.content)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                session.setNoteState(NoteState.Error(e.message ?: "Unknown error"))
            }
        }
    }

    fun openNote(contentResolver: ContentResolver, note: RelatedNote) {
        session.onNoteChanged()
        noteLoadJob = scope.launch {
            try {
                val loaded = loadNoteForDistill(contentResolver, note.title, note.ref)
                // RelatedNote は相対パスを持たない。キャッシュにあれば即使い、無ければ
                // パス未確定でセッションだけ作る（表示前にVault走査を挟まないため）。
                // 本文を出す前に呼ぶ理由は loadRandomNote 側のコメント参照。
                val sessionId = startReadingTrace(note.title, note.ref, cachedRelativePath(note.ref))
                session.setNotePaperTone(notePaperToneForCandidate(note, cachedNotes))
                session.setNoteState(loaded)
                session.recordHistory(note.title, note.ref)
                // 表示を終えてから相対パスを確定させる。さがすタブは collectNotesInScope を
                // 使い cachedNotes を温めないため、ここで走査しないとセッションごとに
                // 「さがす経由の最初の1件」が記録から漏れる。
                bindReadingTracePath(contentResolver, note.ref, sessionId)
                session.fetchSummary(note.title, loaded.content)
                fetchRelatedNotes(note.title, loaded.content)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                session.setNoteState(NoteState.Error(e.message ?: "Unknown error"))
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
    ) = session.reportReadingProgress(blockIndex, blockFraction, totalBlocks, sectionTitle)

    /** アプリが背面へ回るときに呼ぶ（ノート表示中のまま離れた訪問を取りこぼさないため）。 */
    fun pauseReadingTrace() = session.pauseReadingTrace()

    /** 背面から復帰したときに呼ぶ（背面にいた時間を読書時間に含めないため）。 */
    fun resumeReadingTrace() = session.resumeReadingTrace()

    /** 「読んだ」でカードを畳む。永続化しないので次回 Rediscover では再表示される。 */
    fun dismissReadingTraceCard() = session.dismissReadingTraceCard()

    /** @return 読書セッションの識別子（[bindReadingTracePath] に渡す）。 */
    private fun startReadingTrace(title: String, ref: DocumentRef, vaultRelativePath: String?): Long =
        session.startReadingTrace(
            title = title,
            vaultRelativePath = vaultRelativePath,
            // 端末内キャッシュとしてだけ持つ値なので、取れなければ null で構わない。
            documentId = runCatching { DocumentsContract.getDocumentId(ref.toDocumentUri()) }.getOrNull()
        )

    /**
     * uri から vault相対パスを引く。既に走査済みのキャッシュだけを見て、**I/Oはしない**。
     *
     * 表示前にVault全走査を挟むとノート表示が遅れ「本質（ノートを読む）を妨げない」に反する。
     * 加えて、ここで suspend すると uiState 更新とセッション開始の間でメインスレッドを譲り、
     * 痕跡レポータの初回emitを取りこぼして訪問がまるごと記録されなくなる。
     * キャッシュが冷えている場合は [bindReadingTracePath] が表示後に埋める。
     */
    private fun cachedRelativePath(ref: DocumentRef): String? =
        cachedNotes.firstOrNull { it.ref == ref }
            ?.vaultRelativePath
            ?.takeIf { it.isNotEmpty() }


    /**
     * 表示後に相対パスを確定させる。走査はTTLキャッシュ付きなので通常は追加I/Oなし。
     * `_AI補記` 配下は collectNotes の対象外なので見つからず、痕跡も残らない（意図どおり）。
     */
    private suspend fun bindReadingTracePath(
        contentResolver: ContentResolver,
        ref: DocumentRef,
        sessionId: Long
    ) {
        val vault = vaultLocation.uri ?: return
        val path = try {
            collectAllNotesCached(contentResolver, vault)
                .firstOrNull { it.ref == ref }
                ?.vaultRelativePath
                ?.takeIf { it.isNotEmpty() }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: return
        session.bindReadingTracePath(sessionId, path)
    }

    // ── さがすタブ（実装は SearchController）──────────────────────────────────

    fun loadFolders() = session.loadFolders()
    fun selectSearchFolder(folder: NoteFolder?) = session.selectSearchFolder(folder)
    fun searchByKeyword(query: String) = session.searchByKeyword(query)
    fun pickRandomInScope() = session.pickRandomInScope()

    // sourceLabel=対象セクション名、context=フォーカス周辺テキスト（NoteReaderTab が構築）
    fun generateQuiz(sourceLabel: String, context: String) = session.generateQuiz(sourceLabel, context)
    fun markQuizViewed() = session.markQuizViewed()

    // ── 旧補記ファイルの片付け（実装は AnnotationController・Vault単位）─────────

    fun loadAnnotations() = session.loadAnnotations()
    fun deleteAnnotation(ref: DocumentRef) = session.deleteAnnotation(ref)
    fun deleteAllAnnotations() = session.deleteAllAnnotations()

    /** 読書痕跡の孤児候補を洗い出す（整理画面を開いたとき）。 */
    fun assessReadingTraceOrphans() = session.assessReadingTraceOrphans()

    /** 洗い出した候補を1件削除する。ノート本文には触れない。 */
    fun deleteReadingTrace(key: String) = session.deleteReadingTrace(key)

    // ── 蒸留（実装は DistillController）──────────────────────────────────────

    fun startDistill() = session.startDistill()
    fun downloadDistillModel() = session.downloadDistillModel()
    fun toggleDistillCandidate(id: String) = session.toggleDistillCandidate(id)
    fun saveDistillSelection() = session.saveDistillSelection()
    fun retryDistill() = session.retryDistill()
    fun dismissDistillResult() = session.dismissDistillResult()
    fun keepCurrentAfterDistillRecovery() = session.keepCurrentAfterDistillRecovery()
    fun restoreDistillOriginal() = session.restoreDistillOriginal()
    fun exportDistillOriginal(contentResolver: ContentResolver, destination: Uri) =
        session.exportDistillOriginal { bytes ->
            repository.writeDocumentBytes(contentResolver, destination, bytes)
        }

    // ── セクション単位のAIチャット（実装は SectionChatController）─────────────

    fun openSection(section: NoteSection) = session.openSection(section)
    fun showSectionChat() = session.showSectionChat()
    fun sendSectionMessage(text: String) = session.sendSectionMessage(text)
    fun retrySectionAi() = session.retrySectionAi()
    fun dismissSectionChatSheet() = session.dismissSectionChatSheet()
    fun endSectionChat() = session.endSectionChat()

    // ── ノートへのひとこと（実装は RemarkController）───────────────────────────

    fun createRemark(
        title: String,
        content: String,
        relatedNotes: List<RelatedNote>,
        aiNotes: List<RelatedNote>
    ) = session.createRemark(title, content, relatedNotes, aiNotes)

    /** 専用画面を開いたときに、保存済みの「ひとこと＋返事」を読み戻す。 */
    fun restoreSavedRemark(title: String) = session.restoreSavedRemark(title)

    /** 返事を残す。書いた時点で対話は完了するので、AIへ再送しない。 */
    fun saveRemarkReply(reply: String) = session.saveRemarkReply(reply)

    private suspend fun loadNoteForDistill(
        contentResolver: ContentResolver,
        title: String,
        ref: DocumentRef
    ): NoteState.Success {
        val uri = ref.toDocumentUri()
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
     * 同一ノートの本文と基準ハッシュを更新する。読み直しだけを担当し、
     * 状態の差し替え（＝どのAI結果を捨てるか）は調停クラスが持つ。
     */
    private suspend fun reloadNoteBody(targetUri: String, expectedHash: String?): Boolean {
        val current = session.currentNote() ?: return false
        if (current.targetUri != targetUri) return false
        return try {
            val loaded = loadNoteForDistill(
                getApplication<Application>().contentResolver,
                current.title,
                // targetUri は元から Uri.toString() の文字列。Uri へ戻さず参照へ包む。
                DocumentRef(targetUri)
            )
            if (expectedHash != null && loaded.originalHash != expectedHash) return false
            if (!session.applyReloadedBody(targetUri, loaded)) return false
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
        relatedNotesJob = scope.launch {
            session.setRelatedNotesState(RelatedNotesState.Loading)

            // さがすタブ等、loadRandomNote を経由しない導線では未収集のことがあるため補填する
            if (cachedNotes.isEmpty()) {
                val uri = vaultLocation.uri
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
                session.setRelatedNotesState(
                    RelatedNotesState.Success(relatedNotes = emptyList(), aiNotes = emptyList())
                )
                return@launch
            }

            val wikilinkTitles = repository.parseMeta(content).wikilinkTitles
            session.setWikilinkTitles(wikilinkTitles)
            val contentResolver = getApplication<Application>().contentResolver
            when (
                val result = relatedNotesUseCase.findRelated(
                    currentTitle = title,
                    currentContent = content,
                    allNotes = cachedNotes,
                    wikilinkTitles = wikilinkTitles,
                    // 候補はスニペットとfront matterしか使わないので、先頭だけ読む
                    readContent = { ref -> repository.readNoteSnippet(contentResolver, ref.toDocumentUri()) },
                    parseMeta = { repository.parseMeta(it) }
                )
            ) {
                is RelatedNotesResult.Success -> session.setRelatedNotesState(
                    RelatedNotesState.Success(
                        relatedNotes = result.relatedNotes,
                        aiNotes = result.aiNotes
                    )
                )
                is RelatedNotesResult.Error ->
                    session.setRelatedNotesState(RelatedNotesState.Error(result.message))
            }
        }
    }

    companion object {
        /**
         * 読書痕跡の書き出し専用スコープ。**プロセスと同じ寿命**。
         *
         * `viewModelScope` に載せると、タスクスワイプや Activity finish で
         * `onStop()` → `pauseReadingTrace()` の直後に `onCleared()` が走り、
         * IOへディスパッチされる前の保存がキャンセルされて訪問が失われる。
         *
         * `ProcessLifecycleOwner` を使わないのは、`lifecycle-process` の依存追加に対して
         * 必要なのが「`onCleared()` で死なないスコープ」1つだけだから。Activity も
         * ViewModel も参照しないので、明示的なキャンセル契機は持たない。
         * `Main.immediate` を土台にするのは `ReadingTraceController` の
         * スレッド規律（セッション状態はメインスレッドのみ）に合わせるため。
         */
        private val readingTraceWriteScope =
            CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

        private const val DISTILL_OUTPUT_GROWTH_BYTES = DistillLimits.FINAL_SELECTION_LIMIT * 4
    }
}
