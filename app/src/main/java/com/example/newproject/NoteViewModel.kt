package com.example.newproject

import android.app.Application
import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.mlkit.genai.common.DownloadStatus
import com.example.newproject.ai.AICoreClient
import com.example.newproject.ai.AiAvailability
import com.example.newproject.ai.AiClient
import com.example.newproject.ai.PromptBuilder
import com.example.newproject.domain.AiRecommendationStatus
import com.example.newproject.domain.RelatedNote
import com.example.newproject.domain.RelatedNotesResult
import com.example.newproject.domain.RelatedNotesUseCase
import com.example.newproject.domain.PickerResult
import com.example.newproject.domain.SearchPickerUseCase
import com.example.newproject.domain.SummarizeUseCase
import com.example.newproject.domain.SummaryResult
import com.example.newproject.ui.markdown.NoteSection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

sealed class NoteState {
    object Idle : NoteState()
    object Loading : NoteState()
    data class Success(val title: String, val content: String) : NoteState()
    object Empty : NoteState()
    data class Error(val message: String, val id: Long = System.currentTimeMillis()) : NoteState()
}

sealed class SummaryState {
    object Idle : SummaryState()
    object Loading : SummaryState()
    data class Success(val summary: String) : SummaryState()
    // DL進捗: downloaded=-1 は「開始待ち」、total=0 はサイズ不明
    data class Downloading(val downloaded: Long, val total: Long) : SummaryState()
    object AiUnavailable : SummaryState()
    data class Error(val message: String) : SummaryState()
}

sealed class RelatedNotesState {
    object Idle : RelatedNotesState()
    object Loading : RelatedNotesState()
    data class Success(
        val relatedNotes: List<RelatedNote>,
        val aiNotes: List<RelatedNote>,
        val aiStatus: AiRecommendationStatus = AiRecommendationStatus.Ready,
        val aiErrorMessage: String? = null
    ) : RelatedNotesState()
    data class Error(val message: String) : RelatedNotesState()
}

// AIピッカー（さがすタブ）の検索状態。キーワード/ランダム両モードで共有する。
sealed class SearchState {
    object Idle : SearchState()
    object Loading : SearchState()
    data class Success(
        val results: List<RelatedNote>,
        val aiStatus: AiRecommendationStatus = AiRecommendationStatus.Ready
    ) : SearchState()
    data class Error(val message: String) : SearchState()
}

data class QuizCard(
    val question: String,
    val choices: List<String>,
    val correctIndex: Int,
    val explanation: String = ""
)

sealed class QuizState {
    object Idle : QuizState()
    object Loading : QuizState()
    data class Success(val cards: List<QuizCard>) : QuizState()
    data class Error(val message: String) : QuizState()
}

sealed class AnnotationState {
    object Idle : AnnotationState()
    object Loading : AnnotationState()
    data class Success(
        val savedUri: Uri,
        val fileName: String,
        val content: String
    ) : AnnotationState()
    data class Error(val message: String) : AnnotationState()
}

sealed class AnnotationListState {
    object Idle : AnnotationListState()
    object Loading : AnnotationListState()
    data class Success(val files: List<NoteFile>) : AnnotationListState()
    data class Error(val message: String) : AnnotationListState()
}

enum class ChatRole { User, Ai }

data class ChatMessage(val role: ChatRole, val text: String)

// セクション単位のAIチャット。null のときシートは閉じている。
data class SectionChatState(
    val sectionTitle: String,
    val sectionContext: String,     // LLM に渡す本文（表示はしない）
    val summary: String? = null,
    val isSummaryLoading: Boolean = false,
    val suggestions: List<String> = emptyList(),
    val messages: List<ChatMessage> = emptyList(),  // 質問タップによる Q&A ログ
    val isGenerating: Boolean = false,              // 質問の回答生成中
    val error: String? = null
)

data class NoteUiState(
    val vaultSelected: Boolean = false,
    val noteState: NoteState = NoteState.Idle,
    val summaryState: SummaryState = SummaryState.Idle,
    val relatedNotesState: RelatedNotesState = RelatedNotesState.Idle,
    val quizState: QuizState = QuizState.Idle,
    val wikilinkTitles: Set<String> = emptySet(),
    val annotationState: AnnotationState = AnnotationState.Idle,
    val annotationListState: AnnotationListState = AnnotationListState.Idle,
    val sectionChat: SectionChatState? = null,
    // さがすタブ
    val folders: List<NoteFolder> = emptyList(),
    val selectedFolder: NoteFolder? = null,   // null = ルート直下スコープ
    val searchState: SearchState = SearchState.Idle
)

class NoteViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences(PREFS_NAME, Application.MODE_PRIVATE)
    private val repository = NoteRepository()
    private val aiClient: AiClient = AICoreClient()
    private val summarizeUseCase = SummarizeUseCase(aiClient)
    private val relatedNotesUseCase = RelatedNotesUseCase(aiClient)
    private val searchPickerUseCase = SearchPickerUseCase(aiClient)

    private val _uiState = MutableStateFlow(NoteUiState())
    val uiState: StateFlow<NoteUiState> = _uiState.asStateFlow()

    // DL完了後に要約を再実行するために保持
    private var pendingTitle: String = ""
    private var pendingContent: String = ""
    private var pendingAnnotation: PendingAnnotation? = null
    private var cachedNotes: List<NoteFile> = emptyList()

    var vaultUri: Uri? = null
        private set

    init { restoreVault() }

    private fun restoreVault() {
        val savedUri = prefs.getString(KEY_VAULT_URI, null) ?: return
        vaultUri = Uri.parse(savedUri)
        _uiState.value = _uiState.value.copy(vaultSelected = true)
    }

    fun saveVault(uri: Uri) {
        vaultUri = uri
        prefs.edit().putString(KEY_VAULT_URI, uri.toString()).apply()
        cachedNotes = emptyList()
        // Vault切替時はノート単位の状態に加え、さがすタブのスコープも破棄する
        // （selectedFolder は旧Vaultの documentId を保持しているため必須）
        _uiState.value = _uiState.value.resetNoteScopedStates().copy(
            vaultSelected = true,
            folders = emptyList(),
            selectedFolder = null,
            searchState = SearchState.Idle
        )
    }

    // ノートを開き直す・Vaultを切り替える際に、ノート単位の状態をまとめて初期化する。
    // リセットをここに集約することで、状態を追加したときのリセット漏れを防ぐ。
    private fun NoteUiState.resetNoteScopedStates(): NoteUiState = copy(
        summaryState = SummaryState.Idle,
        relatedNotesState = RelatedNotesState.Idle,
        quizState = QuizState.Idle,
        annotationState = AnnotationState.Idle,
        sectionChat = null
    )

    fun loadRandomNote(contentResolver: ContentResolver) {
        val uri = vaultUri ?: return
        viewModelScope.launch {
            _uiState.value = _uiState.value.resetNoteScopedStates().copy(
                noteState = NoteState.Loading
            )
            try {
                val notes = repository.collectNotes(contentResolver, uri)
                cachedNotes = notes
                if (notes.isEmpty()) {
                    _uiState.value = _uiState.value.copy(noteState = NoteState.Empty)
                    return@launch
                }
                val note = notes.random()
                val content = repository.readNoteContent(contentResolver, note.uri)
                _uiState.value = _uiState.value.copy(
                    noteState = NoteState.Success(note.name, content)
                )
                fetchSummary(note.name, content)
                fetchRelatedNotes(note.name, content)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    noteState = NoteState.Error(e.message ?: "Unknown error")
                )
            }
        }
    }

    fun openNote(contentResolver: ContentResolver, note: RelatedNote) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.resetNoteScopedStates().copy(
                noteState = NoteState.Loading
            )
            try {
                val content = repository.readNoteContent(contentResolver, note.uri)
                _uiState.value = _uiState.value.copy(
                    noteState = NoteState.Success(note.title, content)
                )
                fetchSummary(note.title, content)
                fetchRelatedNotes(note.title, content)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    noteState = NoteState.Error(e.message ?: "Unknown error")
                )
            }
        }
    }

    // ── さがすタブ（AIピッカー）─────────────────────────────────────────────

    // タブ表示時にフォルダchips用の第一階層フォルダを列挙する。
    fun loadFolders(contentResolver: ContentResolver) {
        val uri = vaultUri ?: return
        viewModelScope.launch {
            try {
                val folders = repository.listTopLevelFolders(contentResolver, uri)
                _uiState.value = _uiState.value.copy(folders = folders)
            } catch (_: Exception) {
                // フォルダ列挙の失敗は致命的でない（ルート直下スコープは使える）
            }
        }
    }

    fun selectSearchFolder(folder: NoteFolder?) {
        _uiState.value = _uiState.value.copy(selectedFolder = folder)
    }

    // キーワードモード: スコープ収集 → SearchPickerUseCase で3件選定。
    fun searchByKeyword(contentResolver: ContentResolver, query: String) {
        val uri = vaultUri ?: return
        val q = query.trim()
        if (q.isBlank()) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(searchState = SearchState.Loading)
            try {
                val scope = _uiState.value.selectedFolder
                val notes = repository.collectNotesInScope(contentResolver, uri, scope)
                when (val result = searchPickerUseCase.pick(q, notes)) {
                    is PickerResult.Success -> _uiState.value = _uiState.value.copy(
                        searchState = SearchState.Success(result.notes, result.aiStatus)
                    )
                    is PickerResult.Error -> _uiState.value = _uiState.value.copy(
                        searchState = SearchState.Error(result.message)
                    )
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    searchState = SearchState.Error(e.message ?: "Unknown error")
                )
            }
        }
    }

    // ランダムモード: スコープ内からシャッフルして3件（AI不使用）。
    fun pickRandomInScope(contentResolver: ContentResolver) {
        val uri = vaultUri ?: return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(searchState = SearchState.Loading)
            try {
                val scope = _uiState.value.selectedFolder
                val notes = repository.collectNotesInScope(contentResolver, uri, scope)
                val picked = notes.shuffled().take(3).map {
                    RelatedNote(title = it.name, uri = it.uri, isWikilinked = false)
                }
                _uiState.value = _uiState.value.copy(
                    searchState = SearchState.Success(picked)
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    searchState = SearchState.Error(e.message ?: "Unknown error")
                )
            }
        }
    }

    fun generateQuiz(title: String, content: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(quizState = QuizState.Loading)
            try {
                val prompt = PromptBuilder.buildQuizPrompt(title, content)
                val raw = aiClient.generate(prompt)
                val cards = parseQuizResponse(raw)
                _uiState.value = _uiState.value.copy(quizState = QuizState.Success(cards))
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    quizState = QuizState.Error(e.message ?: "Unknown error")
                )
            }
        }
    }

    fun clearQuiz() {
        _uiState.value = _uiState.value.copy(quizState = QuizState.Idle)
    }

    fun loadAnnotations(contentResolver: ContentResolver) {
        val uri = vaultUri
        if (uri == null) {
            _uiState.value = _uiState.value.copy(
                annotationListState = AnnotationListState.Error("Vault が選択されていません。")
            )
            return
        }
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(annotationListState = AnnotationListState.Loading)
            try {
                val files = repository.listAnnotationFiles(contentResolver, uri)
                _uiState.value = _uiState.value.copy(
                    annotationListState = AnnotationListState.Success(files)
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    annotationListState = AnnotationListState.Error(e.message ?: "Unknown error")
                )
            }
        }
    }

    fun deleteAnnotation(contentResolver: ContentResolver, uri: Uri) {
        viewModelScope.launch {
            repository.deleteDocument(contentResolver, uri)
            reloadAnnotations(contentResolver)
        }
    }

    fun deleteAllAnnotations(contentResolver: ContentResolver) {
        val current = _uiState.value.annotationListState as? AnnotationListState.Success ?: return
        viewModelScope.launch {
            current.files.forEach { file ->
                repository.deleteDocument(contentResolver, file.uri)
            }
            reloadAnnotations(contentResolver)
        }
    }

    private suspend fun reloadAnnotations(contentResolver: ContentResolver) {
        val uri = vaultUri ?: return
        try {
            val files = repository.listAnnotationFiles(contentResolver, uri)
            _uiState.value = _uiState.value.copy(
                annotationListState = AnnotationListState.Success(files)
            )
        } catch (e: Exception) {
            _uiState.value = _uiState.value.copy(
                annotationListState = AnnotationListState.Error(e.message ?: "Unknown error")
            )
        }
    }

    // ── セクション単位のAIチャット ─────────────────────────────────────────────

    // 吹き出しタップで開く。要約と候補質問をまとめて用意する。
    fun openSection(section: NoteSection) {
        _uiState.value = _uiState.value.copy(
            sectionChat = SectionChatState(
                sectionTitle = section.title,
                sectionContext = section.text,
                isSummaryLoading = true
            )
        )
        viewModelScope.launch {
            when (aiClient.checkAvailability()) {
                AiAvailability.Unavailable ->
                    updateChat { it.copy(isSummaryLoading = false, error = "この端末ではAIを利用できません。") }
                AiAvailability.NeedsDownload ->
                    updateChat { it.copy(isSummaryLoading = false, error = "AIモデルの準備が必要です。先にAI要約や補記メモを実行してダウンロードしてください。") }
                AiAvailability.Available -> {
                    try {
                        val summary = aiClient
                            .generate(PromptBuilder.buildSectionSummaryPrompt(section.title, section.text))
                            .trim()
                        updateChat {
                            it.copy(
                                summary = summary.ifBlank { "（要約を生成できませんでした）" },
                                isSummaryLoading = false
                            )
                        }
                    } catch (e: Exception) {
                        updateChat { it.copy(isSummaryLoading = false, error = e.message ?: "Unknown error") }
                    }
                    fetchSectionSuggestions(section)
                }
            }
        }
    }

    fun sendSectionMessage(text: String) {
        val chat = _uiState.value.sectionChat ?: return
        val question = text.trim()
        if (question.isBlank() || chat.isGenerating) return

        val history = chat.messages.map {
            (if (it.role == ChatRole.User) "User" else "AI") to it.text
        }
        updateChat {
            it.copy(
                messages = it.messages + ChatMessage(ChatRole.User, question),
                isGenerating = true,
                error = null
            )
        }
        viewModelScope.launch {
            if (aiClient.checkAvailability() != AiAvailability.Available) {
                updateChat { it.copy(isGenerating = false, error = "この端末ではAIを利用できません。") }
                return@launch
            }
            try {
                val answer = aiClient.generate(
                    PromptBuilder.buildSectionChatPrompt(
                        sectionTitle = chat.sectionTitle,
                        sectionText = chat.sectionContext,
                        history = history,
                        question = question
                    )
                ).trim()
                updateChat {
                    it.copy(
                        messages = it.messages + ChatMessage(
                            ChatRole.Ai,
                            answer.ifBlank { "（回答を生成できませんでした）" }
                        ),
                        isGenerating = false
                    )
                }
            } catch (e: Exception) {
                updateChat { it.copy(isGenerating = false, error = e.message ?: "Unknown error") }
            }
        }
    }

    fun closeSectionChat() {
        _uiState.value = _uiState.value.copy(sectionChat = null)
    }

    private suspend fun fetchSectionSuggestions(section: NoteSection) {
        try {
            val raw = aiClient.generate(
                PromptBuilder.buildSectionSuggestionsPrompt(section.title, section.text)
            )
            val questions = raw.lineSequence()
                .map { it.trim().removePrefix("-").trim().trim('"').trim() }
                .filter { it.isNotBlank() }
                .take(3)
                .toList()
            updateChat { it.copy(suggestions = questions) }
        } catch (_: Exception) {
            // サジェストは失敗しても本体機能に影響させない
        }
    }

    // sectionChat が開いている場合のみ安全に更新する
    private fun updateChat(block: (SectionChatState) -> SectionChatState) {
        val current = _uiState.value.sectionChat ?: return
        _uiState.value = _uiState.value.copy(sectionChat = block(current))
    }

    fun createAnnotation(
        contentResolver: ContentResolver,
        title: String,
        content: String,
        summary: String?,
        relatedNotes: List<RelatedNote>,
        aiNotes: List<RelatedNote>,
        wikilinkTitles: Set<String>
    ) {
        val vault = vaultUri
        if (vault == null) {
            _uiState.value = _uiState.value.copy(
                annotationState = AnnotationState.Error("Vault が選択されていません。")
            )
            return
        }

        val annotation = PendingAnnotation(
            title = title,
            content = content,
            summary = summary,
            relatedNotes = relatedNotes,
            aiNotes = aiNotes,
            wikilinkTitles = wikilinkTitles
        )

        _uiState.value = _uiState.value.copy(annotationState = AnnotationState.Loading)
        viewModelScope.launch {
            when (aiClient.checkAvailability()) {
                AiAvailability.Unavailable -> {
                    _uiState.value = _uiState.value.copy(
                        annotationState = AnnotationState.Error("補記メモはこの端末では利用できません。")
                    )
                }
                AiAvailability.NeedsDownload -> {
                    pendingAnnotation = annotation
                    startAnnotationModelDownload()
                }
                AiAvailability.Available -> {
                    createAnnotationWithAvailableModel(
                        contentResolver = contentResolver,
                        vault = vault,
                        annotation = annotation
                    )
                }
            }
        }
    }

    private fun parseQuizResponse(raw: String): List<QuizCard> {
        return raw.trim().split("\n\n").mapNotNull { block ->
            val lines = block.trim().lines()
            val q = lines.firstOrNull { it.startsWith("Q:") }?.removePrefix("Q:")?.trim()
            val a = lines.firstOrNull { it.startsWith("A:") }?.removePrefix("A:")?.trim()
            val b = lines.firstOrNull { it.startsWith("B:") }?.removePrefix("B:")?.trim()
            val c = lines.firstOrNull { it.startsWith("C:") }?.removePrefix("C:")?.trim()
            val d = lines.firstOrNull { it.startsWith("D:") }?.removePrefix("D:")?.trim()
            val answer = lines.firstOrNull { it.startsWith("ANSWER:") }?.removePrefix("ANSWER:")?.trim()
            val explanation = lines.firstOrNull { it.startsWith("EXPLANATION:") }?.removePrefix("EXPLANATION:")?.trim() ?: ""
            if (q != null && a != null && b != null && c != null && d != null && answer != null) {
                val choices = listOf(a, b, c, d)
                val correctIndex = listOf("A", "B", "C", "D").indexOf(answer)
                if (correctIndex >= 0) QuizCard(q, choices, correctIndex, explanation) else null
            } else null
        }
    }

    private suspend fun createAnnotationWithAvailableModel(
        contentResolver: ContentResolver,
        vault: Uri,
        annotation: PendingAnnotation
    ) {
        try {
            val displayTimestamp = DISPLAY_TIMESTAMP_FORMAT.format(Date())
            val fileTimestamp = FILE_TIMESTAMP_FORMAT.format(Date())
            val prompt = PromptBuilder.buildAnnotationPrompt(
                title = annotation.title,
                content = annotation.content,
                summary = annotation.summary,
                relatedTitles = annotation.relatedNotes.map { it.title.toObsidianNoteTitle() },
                aiRecommendedTitles = annotation.aiNotes.map { it.title.toObsidianNoteTitle() },
                wikilinkTitles = annotation.wikilinkTitles,
                createdAt = displayTimestamp
            )
            val generated = aiClient.generate(prompt).trim()
            if (!hasAnnotationBody(generated)) {
                _uiState.value = _uiState.value.copy(
                    annotationState = AnnotationState.Error("補記メモの生成結果が空でした。")
                )
                return
            }

            val sourceTitle = annotation.title.toObsidianNoteTitle()
            val fileTitle = sanitizeAnnotationFileTitle(sourceTitle)
            val fileName = "${fileTitle}__補記_$fileTimestamp.md"
            val markdown = buildAnnotationMarkdown(
                title = sourceTitle,
                createdAt = displayTimestamp,
                generatedBody = generated
            )
            val savedUri = repository.createAnnotationFile(
                contentResolver = contentResolver,
                vaultUri = vault,
                sanitizedTitle = fileTitle,
                timestamp = fileTimestamp,
                content = markdown
            )
            _uiState.value = _uiState.value.copy(
                annotationState = AnnotationState.Success(
                    savedUri = savedUri,
                    fileName = fileName,
                    content = markdown
                )
            )
        } catch (e: Exception) {
            _uiState.value = _uiState.value.copy(
                annotationState = AnnotationState.Error(e.message ?: "Unknown error")
            )
        }
    }

    private fun hasAnnotationBody(generated: String): Boolean {
        val requiredSections = listOf("粒度評価", "補記すべき内容")
        return requiredSections.any { section ->
            generated.substringAfter("## $section", missingDelimiterValue = "")
                .substringBefore("## ")
                .trim()
                .isNotBlank()
        }
    }

    private fun buildAnnotationMarkdown(
        title: String,
        createdAt: String,
        generatedBody: String
    ): String = """
        # $title AI補記メモ

        > Source: [[$title]]
        > Created: $createdAt
        > Generated by: Obsidian Mind local AI

        ${generatedBody.ensureAnnotationSections()}
    """.trimIndent()

    private fun String.ensureAnnotationSections(): String {
        val sections = listOf("粒度評価", "補記すべき内容")
        val normalized = trim()
        val missing = sections.filterNot { "## $it" in normalized }
        if (missing.isEmpty()) return normalized
        return buildString {
            append(normalized)
            missing.forEach { section ->
                append("\n\n## ")
                append(section)
                append("\n")
            }
        }.trim()
    }

    private fun fetchSummary(title: String, content: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(summaryState = SummaryState.Loading)
            when (val result = summarizeUseCase.summarize(title, content)) {
                is SummaryResult.Success      -> {
                    _uiState.value = _uiState.value.copy(
                        summaryState = SummaryState.Success(result.summary)
                    )
                }
                is SummaryResult.AiUnavailable -> {
                    _uiState.value = _uiState.value.copy(summaryState = SummaryState.AiUnavailable)
                }
                is SummaryResult.AiNeedsDownload -> {
                    // モデル未DL → 自動でダウンロード開始
                    pendingTitle = title
                    pendingContent = content
                    startModelDownload()
                }
                is SummaryResult.Error -> {
                    _uiState.value = _uiState.value.copy(
                        summaryState = SummaryState.Error(result.message)
                    )
                }
            }
        }
    }

    private fun fetchRelatedNotes(title: String, content: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(relatedNotesState = RelatedNotesState.Loading)

            // さがすタブ等、loadRandomNote を経由しない導線では未収集のことがあるため補填する
            if (cachedNotes.isEmpty()) {
                val uri = vaultUri
                if (uri != null) {
                    try {
                        cachedNotes = repository.collectNotes(
                            getApplication<Application>().contentResolver, uri
                        )
                    } catch (_: Exception) {
                        // 収集に失敗した場合は従来どおり候補なしとして扱う
                    }
                }
            }
            if (cachedNotes.isEmpty()) {
                _uiState.value = _uiState.value.copy(
                    relatedNotesState = RelatedNotesState.Success(
                        relatedNotes = emptyList(),
                        aiNotes = emptyList()
                    )
                )
                return@launch
            }

            val wikilinkTitles = repository.parseMeta(content).wikilinkTitles
            _uiState.value = _uiState.value.copy(wikilinkTitles = wikilinkTitles)
            when (val result = relatedNotesUseCase.findRelated(title, content, cachedNotes, wikilinkTitles)) {
                is RelatedNotesResult.Success -> {
                    _uiState.value = _uiState.value.copy(
                        relatedNotesState = RelatedNotesState.Success(
                            relatedNotes = result.relatedNotes,
                            aiNotes = result.aiNotes,
                            aiStatus = result.aiStatus,
                            aiErrorMessage = result.aiErrorMessage
                        )
                    )
                }
                is RelatedNotesResult.Error -> {
                    _uiState.value = _uiState.value.copy(
                        relatedNotesState = RelatedNotesState.Error(result.message)
                    )
                }
            }
        }
    }

    private fun startModelDownload() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                summaryState = SummaryState.Downloading(downloaded = -1L, total = 0L)
            )
            try {
                aiClient.downloadModel().collect { status ->
                    when (status) {
                        is DownloadStatus.DownloadStarted -> {
                            _uiState.value = _uiState.value.copy(
                                summaryState = SummaryState.Downloading(0L, status.bytesToDownload)
                            )
                        }
                        is DownloadStatus.DownloadProgress -> {
                            val total = (_uiState.value.summaryState as? SummaryState.Downloading)?.total ?: 0L
                            _uiState.value = _uiState.value.copy(
                                summaryState = SummaryState.Downloading(status.totalBytesDownloaded, total)
                            )
                        }
                        is DownloadStatus.DownloadCompleted -> {
                            // DL完了 → 要約を実行
                            fetchSummary(pendingTitle, pendingContent)
                            fetchRelatedNotes(pendingTitle, pendingContent)
                        }
                        is DownloadStatus.DownloadFailed -> {
                            _uiState.value = _uiState.value.copy(
                                summaryState = SummaryState.Error(
                                    "モデルのダウンロードに失敗しました: ${status.e.message}"
                                )
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    summaryState = SummaryState.Error("ダウンロードエラー: ${e.message}")
                )
            }
        }
    }

    private fun startAnnotationModelDownload() {
        viewModelScope.launch {
            try {
                aiClient.downloadModel().collect { status ->
                    when (status) {
                        is DownloadStatus.DownloadStarted,
                        is DownloadStatus.DownloadProgress -> {
                            _uiState.value = _uiState.value.copy(annotationState = AnnotationState.Loading)
                        }
                        is DownloadStatus.DownloadCompleted -> {
                            val annotation = pendingAnnotation ?: return@collect
                            val vault = vaultUri ?: return@collect
                            pendingAnnotation = null
                            createAnnotationWithAvailableModel(
                                contentResolver = getApplication<Application>().contentResolver,
                                vault = vault,
                                annotation = annotation
                            )
                        }
                        is DownloadStatus.DownloadFailed -> {
                            _uiState.value = _uiState.value.copy(
                                annotationState = AnnotationState.Error(
                                    "モデルのダウンロードに失敗しました: ${status.e.message}"
                                )
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    annotationState = AnnotationState.Error("ダウンロードエラー: ${e.message}")
                )
            }
        }
    }

    private data class PendingAnnotation(
        val title: String,
        val content: String,
        val summary: String?,
        val relatedNotes: List<RelatedNote>,
        val aiNotes: List<RelatedNote>,
        val wikilinkTitles: Set<String>
    )

    companion object {
        private const val PREFS_NAME = "random_note_prefs"
        private const val KEY_VAULT_URI = "vault_uri"
        private val DISPLAY_TIMESTAMP_FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        private val FILE_TIMESTAMP_FORMAT = SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault())
    }
}
