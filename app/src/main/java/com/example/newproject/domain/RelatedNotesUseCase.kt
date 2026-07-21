package com.example.newproject.domain

import android.net.Uri
import com.example.newproject.NoteFile
import com.example.newproject.NoteMeta
import com.example.newproject.ai.AiAvailability
import com.example.newproject.ai.AiClient
import com.example.newproject.ai.PromptBuilder
import com.example.newproject.ai.RelatedCandidateLine
import com.example.newproject.toNormalizedObsidianTitle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

data class RelatedNote(
    val title: String,
    val uri: Uri,
    val isWikilinked: Boolean,
    val lastModified: Long? = null
)

enum class AiRecommendationStatus {
    Ready,
    Unavailable,
    NeedsDownload,
    Error
}

sealed class RelatedNotesResult {
    data class Success(
        val relatedNotes: List<RelatedNote>,
        val aiNotes: List<RelatedNote>,
        val aiStatus: AiRecommendationStatus = AiRecommendationStatus.Ready,
        val aiErrorMessage: String? = null
    ) : RelatedNotesResult()
    data class Error(val message: String) : RelatedNotesResult()
}

class RelatedNotesUseCase(private val aiClient: AiClient) {

    // 候補の本文コンテキスト（スニペット・タグ・aliases）を URI+lastModified で記憶する。
    // ノートを開き直すたびに候補集合は大きく重なるため、再読込を避けられる。
    private val candidateCache = KeyedMemoCache<CandidateCacheKey, CandidateContextData>(CANDIDATE_CACHE_MAX_ENTRIES)

    // Vault切替時に呼ぶ。旧VaultのURIは新Vaultで開けないため全破棄する。
    fun clearCache() = candidateCache.clear()

    private data class CandidateCacheKey(val uri: Uri, val lastModified: Long?)

    // 本文読込後・ID採番前の候補。Phase 3b の再ランクはこの粒度で並べ替える（IDは順確定後に振る）。
    private data class ReadCandidate(val note: NoteFile, val data: CandidateContextData)

    suspend fun findRelated(
        currentTitle: String,
        currentContent: String,
        allNotes: List<NoteFile>,
        wikilinkTitles: Set<String>,
        readContent: suspend (Uri) -> String,
        parseMeta: (String) -> NoteMeta
    ): RelatedNotesResult {
        return try {
            val candidateNotes = allNotes.filterNot { it.name.isSameTitleAs(currentTitle) }
            val wikilinkTitleSet = wikilinkTitles.map { it.toNormalizedObsidianTitle() }.toSet()

            val relatedNotes = buildDeterministicRelatedNotes(
                currentTitle = currentTitle,
                candidateNotes = candidateNotes,
                wikilinkTitleSet = wikilinkTitleSet
            )

            when (aiClient.checkAvailability()) {
                AiAvailability.Unavailable -> RelatedNotesResult.Success(
                    relatedNotes = relatedNotes,
                    aiNotes = emptyList(),
                    aiStatus = AiRecommendationStatus.Unavailable
                )
                AiAvailability.NeedsDownload -> RelatedNotesResult.Success(
                    relatedNotes = relatedNotes,
                    aiNotes = emptyList(),
                    aiStatus = AiRecommendationStatus.NeedsDownload
                )
                AiAvailability.Available -> {
                    // 決定的チャンネルに出したタイトルをAI候補から除外し（上限適用の前に落とす）、
                    // AIチャンネルを「未表示ノートの補完」に純化する。並べ替え・上限は純ロジックへ委譲。
                    val orderedCandidates = rankRelatedCandidates(
                        currentTitle = currentTitle,
                        candidates = candidateNotes,
                        titleOf = { it.name },
                        excludedTitles = relatedNotes.map { it.title }.toSet(),
                        limit = AI_CANDIDATE_LIMIT
                    )
                    // 本文を上限付き並列で読み込む（SAFプロバイダを詰まらせないよう Semaphore で同時数を絞る）。
                    // IDはまだ振らない。この後の本文再ランクで並びが変わるため、採番は再ランク後に確定する。
                    // キャンセルは coroutineScope が全 async を巻き取り、awaitAll から伝播する。
                    val gate = Semaphore(MAX_PARALLEL_READS)
                    val readCandidates = coroutineScope {
                        orderedCandidates.map { note ->
                            async {
                                val data = gate.withPermit { loadContextOrEmpty(note, readContent, parseMeta) }
                                ReadCandidate(note, data)
                            }
                        }.awaitAll()
                    }
                    // Phase 3b: 現ノートの本文シグナル（tags/snippet/title）で上位集合を再ランクする。
                    // タイトルだけで選んだ順を本文的な近さで並べ替え、プロンプト先頭へ良い候補を寄せる
                    // （件数は変えず並べ替えのみ）。除外は上限適用前に済んでいるため isExcluded は常に偽。
                    val currentSignals = buildCurrentNoteSignals(
                        currentTitle = currentTitle,
                        currentContent = currentContent,
                        currentTags = parseMeta(currentContent).tags,
                        snippetLen = RELATED_SNIPPET_LEN
                    )
                    val reranked = rankByScore(
                        candidates = readCandidates,
                        isExcluded = { false },
                        limit = readCandidates.size,
                        scoreOf = { relatedContextScore(currentSignals, it.note.name, it.data.snippet, it.data.tags) }
                    )
                    // 再ランク後の並びで一時ID（C01..）を採番。ID＝プロンプト順＝idToNote が一致する。
                    // 同名・別Uriも別IDになり確実に解決できる。
                    val idToNote = reranked
                        .mapIndexed { index, rc -> relatedCandidateId(index) to rc.note }
                        .toMap()
                    // 候補を本文冒頭スニペット等で肉付けし、入力バジェット内へ収める。
                    val contexts = reranked.mapIndexed { index, rc ->
                        CandidateContext(
                            id = relatedCandidateId(index),
                            title = rc.note.name,
                            snippet = rc.data.snippet,
                            tags = rc.data.tags,
                            aliases = rc.data.aliases
                        )
                    }
                    val candidateLines = renderCandidatesWithinBudget(
                        candidates = contexts,
                        charBudget = RELATED_CANDIDATES_BUDGET,
                        maxSnippetLen = RELATED_SNIPPET_LEN,
                        minSnippetLen = RELATED_MIN_SNIPPET_LEN
                    )
                    val prompt = PromptBuilder.buildRelatedNotesPrompt(
                        currentTitle = currentTitle,
                        currentContent = currentContent,
                        candidates = candidateLines
                    )
                    val response = aiClient.generate(prompt)

                    // 応答からIDを抽出→ノートへ解決。候補は既に決定的枠を除外済みだが、
                    // モデルが既出を混ぜても拾わないようUri単位でも念のため落とす（防御）。
                    val relatedUris = relatedNotes.map { it.uri }.toSet()
                    val aiNotes = parseCandidateIds(response, idToNote.keys, AI_RECOMMENDATION_LIMIT)
                        .mapNotNull { id -> idToNote[id] }
                        .filterNot { it.uri in relatedUris }
                        .distinctBy { it.uri }
                        .take(AI_RECOMMENDATION_LIMIT)
                        .map { note ->
                            RelatedNote(
                                title = note.name,
                                uri = note.uri,
                                isWikilinked = note.name.toNormalizedObsidianTitle() in wikilinkTitleSet,
                                lastModified = note.lastModified
                            )
                        }

                    RelatedNotesResult.Success(
                        relatedNotes = relatedNotes,
                        aiNotes = aiNotes
                    )
                }
            }
        } catch (e: CancellationException) {
            throw e   // ジョブキャンセルはエラー扱いせず伝播させる
        } catch (e: Exception) {
            RelatedNotesResult.Success(
                relatedNotes = buildDeterministicRelatedNotes(
                    currentTitle = currentTitle,
                    candidateNotes = allNotes.filterNot { it.name.isSameTitleAs(currentTitle) },
                    wikilinkTitleSet = wikilinkTitles.map { it.toNormalizedObsidianTitle() }.toSet()
                ),
                aiNotes = emptyList(),
                aiStatus = AiRecommendationStatus.Error,
                aiErrorMessage = e.message ?: "Unknown error"
            )
        }
    }

    private fun buildDeterministicRelatedNotes(
        currentTitle: String,
        candidateNotes: List<NoteFile>,
        wikilinkTitleSet: Set<String>
    ): List<RelatedNote> {
        val wikilinkedNotes = candidateNotes.filter {
            it.name.toNormalizedObsidianTitle() in wikilinkTitleSet
        }
        val sameGroupNotes = extractSameGroup(currentTitle, candidateNotes)

        return (wikilinkedNotes + sameGroupNotes)
            .distinctBy { it.uri }
            .take(RELATED_NOTE_LIMIT)
            .map { note ->
                RelatedNote(
                    title = note.name,
                    uri = note.uri,
                    isWikilinked = note.name.toNormalizedObsidianTitle() in wikilinkTitleSet,
                    lastModified = note.lastModified
                )
            }
    }

    // 上2桁が一致するノートのみ返す（決定的チャンネル表示用）。
    // プレフィックス抽出は純ロジックの extractHexPrefix を共用する。
    private fun extractSameGroup(currentTitle: String, candidates: List<NoteFile>): List<NoteFile> {
        val prefix = extractHexPrefix(currentTitle) ?: return emptyList()
        val twoDigit = prefix.take(2)
        return candidates.filter { extractHexPrefix(it.name)?.take(2) == twoDigit }
    }

    private fun String.isSameTitleAs(other: String): Boolean =
        toNormalizedObsidianTitle() == other.toNormalizedObsidianTitle()

    // 候補の本文コンテキストをキャッシュ経由で取得する。読み取り失敗（キャンセル以外）は
    // その候補だけタイトルのみで続行し、AI推薦全体を巻き添えにしない。キャンセルは伝播。
    private suspend fun loadContextOrEmpty(
        note: NoteFile,
        readContent: suspend (Uri) -> String,
        parseMeta: (String) -> NoteMeta
    ): CandidateContextData = try {
        candidateCache.getOrLoad(CandidateCacheKey(note.uri, note.lastModified)) {
            val content = readContent(note.uri)
            val meta = parseMeta(content)
            CandidateContextData(
                snippet = extractRelatedSnippet(content, RELATED_SNIPPET_LEN),
                tags = meta.tags,
                aliases = meta.aliases
            )
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        CandidateContextData.EMPTY
    }

    companion object {
        private const val RELATED_NOTE_LIMIT = 5
        private const val AI_RECOMMENDATION_LIMIT = 5
        // AIへ渡す候補タイトルの上限。制限箇所はここ1か所に統一（旧: プロンプト側80と二重）。
        private const val AI_CANDIDATE_LIMIT = 40
        // 以下は実機計測で調整する前提の初期値。
        private const val RELATED_SNIPPET_LEN = 150        // 1候補あたりのスニペット最大長
        private const val RELATED_MIN_SNIPPET_LEN = 40     // 短縮時の下限
        private const val RELATED_CANDIDATES_BUDGET = 3500 // 候補ブロック全体の入力文字数上限
        private const val CANDIDATE_CACHE_MAX_ENTRIES = 300
        // 候補本文の同時読み込み数。SAFプロバイダ（別プロセス）の詰まりを避けつつ速度を稼ぐ。
        private const val MAX_PARALLEL_READS = 8
    }
}
