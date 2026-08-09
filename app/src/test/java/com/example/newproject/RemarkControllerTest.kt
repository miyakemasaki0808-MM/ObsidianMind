package com.example.newproject

import com.example.newproject.ai.AiAvailability
import com.example.newproject.ai.AiClient
import com.example.newproject.controller.RemarkController
import com.example.newproject.model.DocumentRef
import com.example.newproject.model.NoteUiState
import com.example.newproject.model.NoteUiStateStore
import com.example.newproject.model.RelatedNote
import com.example.newproject.model.state.RemarkState
import com.google.mlkit.genai.common.DownloadStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ノートへのひとことの生成。
 *
 * **保存を自分で行わないことが設計の要**なので、[onRemarkReady] へ渡ったかどうかを
 * 中心に押さえる（→ design/reflect_remark.md §2.1）。ここを渡し忘れると、
 * 画面に出たひとことがノートを離れた瞬間に消える。
 */
class RemarkControllerTest {

    private val body = "読書は著者との対話である。問いを持ち込むことで、書かれていないことまで考えられる。"

    @Test
    fun `受理されたひとことは表示され痕跡へ預けられる`() = runTest {
        val state = NoteUiStateStore(NoteUiState())
        val handed = mutableListOf<String>()
        val controller = controller(
            state,
            ImmediateAiClient("「読書は著者との対話である」という考えは、反対するときにも成り立つだろうか？"),
            onRemarkReady = { handed += it }
        )

        controller.create("対話について", body, relatedNotes = emptyList(), aiNotes = emptyList())

        val ready = state.value.remarkState as RemarkState.Ready
        assertEquals("「読書は著者との対話である」という考えは、反対するときにも成り立つだろうか？", ready.remark)
        assertEquals(listOf(ready.remark), handed)
    }

    // 候補IDは実タイトルへ差し戻したうえで痕跡へ預ける。IDのまま保存すると
    // 次に開いたとき [[C01]] という無意味なリンクが残る。
    @Test
    fun `候補IDは実タイトルへ差し戻して預けられる`() = runTest {
        val state = NoteUiStateStore(NoteUiState())
        val handed = mutableListOf<String>()
        val controller = controller(
            state,
            ImmediateAiClient("[[C01]]と並べると、「対話」の始め方まで考えられそうです。"),
            onRemarkReady = { handed += it }
        )

        controller.create(
            title = "対話について",
            content = body,
            relatedNotes = emptyList(),
            aiNotes = listOf(relatedNote("問いを立てる技術"))
        )

        assertEquals(listOf("[[問いを立てる技術]]と並べると、「対話」の始め方まで考えられそうです。"), handed)
    }

    /**
     * 検証に落ちたものは**痕跡へ何も預けない。**
     * 預けてしまうと、一般論や候補外リンクがサイドカーへ残る。
     */
    @Test
    fun `検証に落ちたひとことは預けられない`() = runTest {
        val state = NoteUiStateStore(NoteUiState())
        val handed = mutableListOf<String>()
        val controller = controller(
            state,
            ImmediateAiClient("この内容をもっと掘り下げて整理すると、新しい発見があるかもしれませんね。"),
            onRemarkReady = { handed += it }
        )

        controller.create("対話について", body, relatedNotes = emptyList(), aiNotes = emptyList())

        // 一般論は「出すものが無い」ではなくモデルの書式失敗として扱う（再試行が効く）
        assertTrue(state.value.remarkState is RemarkState.Unusable)
        assertTrue(handed.isEmpty())
    }

    @Test
    fun `NONE は空振りとして扱われる`() = runTest {
        val state = NoteUiStateStore(NoteUiState())
        val controller = controller(state, ImmediateAiClient("NONE"))

        controller.create("対話について", body, relatedNotes = emptyList(), aiNotes = emptyList())

        assertTrue(state.value.remarkState is RemarkState.Empty)
    }

    @Test
    fun `AI非対応ならエラーになる`() = runTest {
        val state = NoteUiStateStore(NoteUiState())
        val controller = controller(state, UnavailableAiClient)

        controller.create("対話について", body, relatedNotes = emptyList(), aiNotes = emptyList())

        val error = state.value.remarkState as RemarkState.Error
        assertEquals("ひとことはこの端末では利用できません。", error.message)
    }

    @Test
    fun `生成中の連続タップでは二重に走らせない`() = runTest {
        val state = NoteUiStateStore(NoteUiState())
        val ai = ControllableAiClient()
        val controller = controller(state, ai)

        controller.create("対話について", body, relatedNotes = emptyList(), aiNotes = emptyList())
        controller.create("対話について", body, relatedNotes = emptyList(), aiNotes = emptyList())

        assertEquals(1, ai.generateCalls)
        ai.response.complete("NONE")
    }

    /**
     * ノート切替後に届いた結果を、新しいノートの画面へ出さない。
     * **痕跡へも預けない** — 旧ノートの本文から作られた文が、
     * 新ノートのサイドカーへ入るのが最悪の壊れ方になる。
     */
    @Test
    fun `ノート切替後に届いた結果は捨てられる`() = runTest {
        val state = NoteUiStateStore(NoteUiState())
        val ai = ControllableAiClient()
        val handed = mutableListOf<String>()
        val controller = controller(state, ai, onRemarkReady = { handed += it })

        controller.create("対話について", body, relatedNotes = emptyList(), aiNotes = emptyList())
        controller.cancelAndClear()
        ai.response.complete("「読書は著者との対話である」は、反対する相手にも当てはまるだろうか？")

        assertTrue(state.value.remarkState is RemarkState.Idle)
        assertTrue(handed.isEmpty())
    }

    @Test
    fun `ノート切替で状態がIdleへ戻る`() = runTest {
        val state = NoteUiStateStore(NoteUiState(remarkState = RemarkState.Loading("旧ノート")))

        controller(state, UnavailableAiClient).cancelAndClear()

        assertTrue(state.value.remarkState is RemarkState.Idle)
    }

    // 出力は1件なので候補を多く見せる意味が無い。8件のタイトルより3件＋抜粋を選ぶ。
    @Test
    fun `候補ノートは3件で切られ現ノート自身と重複は除かれる`() = runTest {
        val state = NoteUiStateStore(NoteUiState())
        val ai = CapturingAiClient()
        val controller = controller(state, ai)

        controller.create(
            title = "対話について",
            content = body,
            relatedNotes = (1..20).map { relatedNote("関連$it") },
            aiNotes = listOf(relatedNote("対話について"), relatedNote("関連1"))
        )

        val candidateLines = candidateLinesOf(ai)
        assertEquals(3, candidateLines.size)
        // 現ノート自身は候補にしない（自分自身へのリンクを提案させない）
        assertNull(candidateLines.firstOrNull { it.endsWith("| 対話について") })
        // 同じノートが2つのIDを持たない
        assertEquals(
            candidateLines.size,
            candidateLines.map { it.substringAfter("| ").substringBefore(" — ") }.distinct().size
        )
    }

    // タイトルだけでは中身に踏み込んだ接続理由を作れない。
    // スニペットは関連ノートAIが再ランクで既に読んだ値なので追加I/Oは無い。
    @Test
    fun `候補に本文スニペットが添えられる`() = runTest {
        val state = NoteUiStateStore(NoteUiState())
        val ai = CapturingAiClient()
        val controller = controller(state, ai)

        controller.create(
            title = "対話について",
            content = body,
            relatedNotes = emptyList(),
            aiNotes = listOf(relatedNote("問いを立てる技術", snippet = "良い問いは答えより長く残る。"))
        )

        assertEquals(
            listOf("C01 | 問いを立てる技術 — 良い問いは答えより長く残る。"),
            candidateLinesOf(ai)
        )
    }

    /**
     * AI推薦を先に置き、**既にwikilinkされた関連ノートは最後**へ回す。
     * 既に繋がっているノートへ「つなげると」と提案しても新しくない。
     */
    @Test
    fun `既にwikilinkされた候補は後回しになる`() = runTest {
        val state = NoteUiStateStore(NoteUiState())
        val ai = CapturingAiClient()
        val controller = controller(state, ai)

        controller.create(
            title = "対話について",
            content = body,
            relatedNotes = listOf(
                relatedNote("既にリンク済み", isWikilinked = true),
                relatedNote("まだ繋がっていない")
            ),
            aiNotes = listOf(relatedNote("AI推薦"))
        )

        assertEquals(
            listOf("AI推薦", "まだ繋がっていない", "既にリンク済み"),
            candidateLinesOf(ai).map { it.substringAfter("| ") }
        )
    }

    // ── ヘルパ ───────────────────────────────────────────────────────────────

    private fun controller(
        state: NoteUiStateStore,
        aiClient: AiClient,
        onRemarkReady: (String) -> Unit = {}
    ) = RemarkController(
        scope = CoroutineScope(Dispatchers.Unconfined),
        aiClient = aiClient,
        state = state.remarkWriter,
        onRemarkReady = onRemarkReady,
        excerptDispatcher = Dispatchers.Unconfined
    )

    private fun candidateLinesOf(ai: CapturingAiClient): List<String> =
        requireNotNull(ai.lastPrompt).lines().filter { it.matches(Regex("^C\\d\\d \\| .*")) }

    private fun relatedNote(
        title: String,
        isWikilinked: Boolean = false,
        snippet: String? = null
    ) = RelatedNote(
        title = title,
        ref = DocumentRef("doc-$title"),
        isWikilinked = isWikilinked,
        snippet = snippet
    )

    private class ImmediateAiClient(private val response: String) : AiClient {
        override suspend fun checkAvailability(): AiAvailability = AiAvailability.Available
        override suspend fun generate(prompt: String): String = response
        override fun downloadModel(): Flow<DownloadStatus> = emptyFlow()
    }

    private class CapturingAiClient : AiClient {
        var lastPrompt: String? = null
            private set

        override suspend fun checkAvailability(): AiAvailability = AiAvailability.Available
        override suspend fun generate(prompt: String): String {
            lastPrompt = prompt
            return "NONE"
        }
        override fun downloadModel(): Flow<DownloadStatus> = emptyFlow()
    }

    private class ControllableAiClient : AiClient {
        val response = CompletableDeferred<String>()
        var generateCalls = 0
            private set

        override suspend fun checkAvailability(): AiAvailability = AiAvailability.Available
        override suspend fun generate(prompt: String): String {
            generateCalls++
            return response.await()
        }
        override fun downloadModel(): Flow<DownloadStatus> = emptyFlow()
    }

    private object UnavailableAiClient : AiClient {
        override suspend fun checkAvailability(): AiAvailability = AiAvailability.Unavailable
        override suspend fun generate(prompt: String): String = ""
        override fun downloadModel(): Flow<DownloadStatus> = emptyFlow()
    }
}
