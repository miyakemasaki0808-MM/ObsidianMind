package com.example.newproject.controller

import com.example.newproject.ai.AiAvailability
import com.example.newproject.ai.AiClient
import com.example.newproject.ai.PromptBuilder
import com.example.newproject.ai.RebuttalCandidateLine
import com.example.newproject.domain.RebuttalCandidate
import com.example.newproject.domain.buildNoteExcerpt
import com.example.newproject.domain.parseCandidateIds
import com.example.newproject.domain.rankRebuttalCandidates
import com.example.newproject.domain.rebuttalCandidateId
import com.example.newproject.domain.scanRebuttalClaims
import com.example.newproject.model.DocumentRef
import com.example.newproject.model.NoteExcerptLimits
import com.example.newproject.model.RebuttalStateWriter
import com.example.newproject.model.RelatedNote
import com.example.newproject.model.isRebuttalNone
import com.example.newproject.model.state.RebuttalSentence
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 反証の一文（→ `docs/dev/features/rebuttal_sentence.md`）。関連ノートの本文から、
 * 今のノートと最も食い違う1文を**選ぶ**。生成はしない。
 *
 * **関連ノートの結果が届いたときにだけ走る**（[NoteSessionCoordinator.completeRelatedNotes]）。
 * 候補は関連ノートに出たノートの中から取るので、関連ノートの完了より前には材料が無い。
 *
 * **失敗も空振りも黙る。** 状態は「選べた1文」か null だけで、読み込み中も失敗も持たない。
 * モデルDLは始めない（未取得・DL中は黙って諦め、DL完了で関連ノートが呼び戻されたときに試し直す）。
 *
 * **ノート単位。** [cancelAndClear] は `NoteSessionCoordinator.cancelNoteScopedJobs()` に登録してある。
 */
class RebuttalController(
    private val scope: CoroutineScope,
    private val aiClient: AiClient,
    /** 生成の直前に通す門番。関連ノートが既に通っていればすぐ返る。ノートを離れたら `CancellationException` で抜ける。 */
    private val awaitDwell: suspend () -> Unit,
    private val state: RebuttalStateWriter,
    /**
     * 抜粋の組み立てと、候補の列挙・順位付けを逃がす先。**純粋だが入力に比例する**（→ `docs/dev/lessons.md` L13）。
     * `ThreadingScanTest` がこの形を走査で固定している。
     */
    private val excerptDispatcher: CoroutineDispatcher = Dispatchers.Default
) {
    private var job: Job? = null
    private var activeRequestId = 0L

    /**
     * 関連ノートの結果から1文を選ぶ。前の要求は取り消す。
     *
     * @param readContent 関連ノートの本文の先頭を読む口（関連ノートの再ランクと同じ8KB境界読み出し）。
     *   読めなかったノートは候補から落として続ける。
     */
    fun select(
        currentTitle: String,
        currentContent: String,
        relatedNotes: List<RelatedNote>,
        aiNotes: List<RelatedNote>,
        readContent: suspend (DocumentRef) -> String
    ) {
        val requestId = ++activeRequestId
        job?.cancel()
        val sources = rebuttalSources(relatedNotes, aiNotes)
        if (sources.isEmpty()) return
        job = scope.launch {
            val availability = try {
                aiClient.checkAvailability()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return@launch
            }
            when (availability) {
                AiAvailability.Ready -> Unit
                AiAvailability.NeedsDownload,
                AiAvailability.Downloading,
                AiAvailability.Unsupported,
                is AiAvailability.TemporarilyUnavailable -> return@launch
            }

            val bodies = sources.mapNotNull { note -> readOrNull(note, readContent)?.let { note to it } }
            val excerpt = withContext(excerptDispatcher) {
                buildNoteExcerpt(currentContent, NoteExcerptLimits.REBUTTAL)
            }
            val candidates = withContext(excerptDispatcher) {
                rankRebuttalCandidates(
                    excerptText = excerpt.text,
                    claimsBySource = bodies.map { (note, body) -> note to scanRebuttalClaims(body) }
                )
            }
            if (candidates.isEmpty()) return@launch

            val lines = candidates.mapIndexed { index, candidate ->
                RebuttalCandidateLine(rebuttalCandidateId(index), candidate.text)
            }
            val prompt = PromptBuilder.buildRebuttalPrompt(currentTitle, excerpt, lines)
            awaitDwell()
            val chosen = try {
                chosenCandidate(aiClient.generate(prompt.text), prompt.validIds, lines, candidates)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return@launch
            } ?: return@launch
            // **取り消しだけに頼らない。** `AiClient` は他実装を許す契約で、取り消しに従わない生成が後から返りうる。
            if (!isCurrent(requestId)) return@launch
            state.update { RebuttalSentence(chosen.text, chosen.source) }
        }
    }

    /** ノートを離れる。走っている選定を止め、出していた一文を消す。 */
    fun cancelAndClear() {
        activeRequestId++
        job?.cancel()
        job = null
        state.update { null }
    }

    private suspend fun readOrNull(note: RelatedNote, readContent: suspend (DocumentRef) -> String): String? =
        try {
            readContent(note.ref)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }

    private fun isCurrent(requestId: Long): Boolean = requestId == activeRequestId
}

/** 本文を読み直す関連ノートの上限。追加の読み出しを「この件数×8KB」に閉じる。 */
internal const val REBUTTAL_SOURCE_NOTE_LIMIT = 8

/**
 * 候補を取る関連ノート。**関連タブの並び**（AI推薦 → 未リンクの決定的 → リンク済み）で、参照の重複を畳み、
 * 上限 [REBUTTAL_SOURCE_NOTE_LIMIT] で切る。この並びが候補の同点の順にもなる。
 */
internal fun rebuttalSources(relatedNotes: List<RelatedNote>, aiNotes: List<RelatedNote>): List<RelatedNote> =
    (aiNotes + relatedNotes.filterNot { it.isWikilinked } + relatedNotes.filter { it.isWikilinked })
        .distinctBy { it.ref }
        .take(REBUTTAL_SOURCE_NOTE_LIMIT)

/**
 * 応答から選ばれた候補を引く。`NONE`・空・候補外・散文は null（何も出さない）。
 *
 * **表示する文は手元の候補から引く。** 応答の本文は使わない。
 */
private fun chosenCandidate(
    response: String,
    validIds: Set<String>,
    lines: List<RebuttalCandidateLine>,
    candidates: List<RebuttalCandidate>
): RebuttalCandidate? {
    val trimmed = response.trim()
    if (trimmed.isEmpty() || isRebuttalNone(trimmed)) return null
    val id = parseCandidateIds(trimmed, validIds, limit = 1, prefix = 'X').firstOrNull() ?: return null
    return candidates.getOrNull(lines.indexOfFirst { it.id == id })
}
