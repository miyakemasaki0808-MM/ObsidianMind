package com.example.newproject

import com.example.newproject.ai.AiAvailability
import com.example.newproject.ai.AiClient
import com.example.newproject.controller.RemarkController
import com.example.newproject.model.DocumentRef
import com.example.newproject.model.NoteUiState
import com.example.newproject.model.NoteUiStateStore
import com.example.newproject.model.Reflection
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

    private companion object {
        const val FIXED_NOW = 1_700_000_000_000L
    }

    @Test
    fun `受理されたひとことは表示され痕跡へ預けられる`() = runTest {
        val state = NoteUiStateStore(NoteUiState())
        val handed = mutableListOf<String>()
        val controller = controller(
            state,
            ImmediateAiClient("「読書は著者との対話である」という考えは、反対するときにも成り立つだろうか？"),
            onRemarkReady = { handed += it.remark }
        )

        controller.create("対話について", body, relatedNotes = emptyList(), aiNotes = emptyList())

        val ready = state.value.remarkState as RemarkState.Ready
        assertEquals("「読書は著者との対話である」という考えは、反対するときにも成り立つだろうか？", ready.reflection.remark)
        assertEquals(listOf(ready.reflection.remark), handed)
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
            onRemarkReady = { handed += it.remark }
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
            onRemarkReady = { handed += it.remark }
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
        val controller = controller(state, ai, onRemarkReady = { handed += it.remark })

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

    // ── 返事 ─────────────────────────────────────────────────────────────────

    @Test
    fun `返事を残すと状態と保存の両方へ反映される`() = runTest {
        val state = NoteUiStateStore(NoteUiState())
        val saved = mutableListOf<Triple<String, String, Long>>()
        val controller = controller(
            state,
            ImmediateAiClient("「読書は著者との対話である」は、反対する相手にも当てはまるだろうか？"),
            persistReply = { path, reply, at -> saved += Triple(path, reply, at); true }
        )
        controller.create("対話について", body, relatedNotes = emptyList(), aiNotes = emptyList())

        controller.saveReply("ideas/dialog.md", "  実際に困った場面があった  ")

        val ready = state.value.remarkState as RemarkState.Ready
        assertEquals("実際に困った場面があった", ready.reflection.reply)
        assertEquals(FIXED_NOW, ready.reflection.repliedAtEpochMillis)
        // 前後の空白は落として保存する（画面の下書きをそのまま渡さない）
        assertEquals(listOf(Triple("ideas/dialog.md", "実際に困った場面があった", FIXED_NOW)), saved)
    }

    @Test
    fun `空白だけの返事は保存しない`() = runTest {
        val state = NoteUiStateStore(NoteUiState())
        val saved = mutableListOf<String>()
        val controller = controller(
            state,
            ImmediateAiClient("「読書は著者との対話である」は、反対する相手にも当てはまるだろうか？"),
            persistReply = { _, reply, _ -> saved += reply; true }
        )
        controller.create("対話について", body, relatedNotes = emptyList(), aiNotes = emptyList())

        controller.saveReply("ideas/dialog.md", "   ")

        assertTrue(saved.isEmpty())
        assertNull((state.value.remarkState as RemarkState.Ready).reflection.reply)
    }

    /**
     * **保存に失敗しても状態は進める。** 痕跡側がセッションへ預け直しており、
     * 離脱時に書かれる。ここで失敗を見せると、実際には残っているのに
     * 「保存できなかった」と読まれる。
     */
    @Test
    fun `保存に失敗しても返事は画面に残る`() = runTest {
        val state = NoteUiStateStore(NoteUiState())
        val controller = controller(
            state,
            ImmediateAiClient("「読書は著者との対話である」は、反対する相手にも当てはまるだろうか？"),
            persistReply = { _, _, _ -> false }
        )
        controller.create("対話について", body, relatedNotes = emptyList(), aiNotes = emptyList())

        controller.saveReply("ideas/dialog.md", "それでも残したい")

        assertEquals("それでも残したい", (state.value.remarkState as RemarkState.Ready).reflection.reply)
    }

    // ── 保存済みの読み戻し ───────────────────────────────────────────────────

    @Test
    fun `専用画面を開くと保存済みの組が復元される`() = runTest {
        val state = NoteUiStateStore(NoteUiState())
        val stored = Reflection("前回のひとこと", 1L, "前回の返事", 2L)
        val controller = controller(state, UnavailableAiClient, saved = stored)

        controller.restoreSaved("ideas/dialog.md", "対話について")

        assertEquals(stored, (state.value.remarkState as RemarkState.Ready).reflection)
    }

    /**
     * 走行中・生成済みの結果を、古い保存値で上書きしない。
     * 「見る」で画面へ入った直後に読み込みが返ってくる順序があるため。
     */
    @Test
    fun `生成中なら保存済みで上書きしない`() = runTest {
        val state = NoteUiStateStore(NoteUiState())
        val ai = ControllableAiClient()
        val controller = controller(state, ai, saved = Reflection("古いひとこと", 1L))

        controller.create("対話について", body, relatedNotes = emptyList(), aiNotes = emptyList())
        controller.restoreSaved("ideas/dialog.md", "対話について")

        assertTrue(state.value.remarkState is RemarkState.Loading)
        ai.response.complete("NONE")
    }

    @Test
    fun `保存済みが無ければIdleのまま`() = runTest {
        val state = NoteUiStateStore(NoteUiState())

        controller(state, UnavailableAiClient, saved = null).restoreSaved("ideas/dialog.md", "対話について")

        assertTrue(state.value.remarkState is RemarkState.Idle)
    }

    // ── ヘルパ ───────────────────────────────────────────────────────────────

    private fun controller(
        state: NoteUiStateStore,
        aiClient: AiClient,
        onRemarkReady: (Reflection) -> Unit = {},
        saved: Reflection? = null,
        persistReply: suspend (String, String, Long) -> Boolean = { _, _, _ -> true }
    ) = RemarkController(
        scope = CoroutineScope(Dispatchers.Unconfined),
        aiClient = aiClient,
        state = state.remarkWriter,
        onRemarkReady = onRemarkReady,
        persistReply = persistReply,
        loadReflection = { saved },
        clock = { FIXED_NOW },
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
