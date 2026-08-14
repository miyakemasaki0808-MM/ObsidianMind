package com.example.newproject

import com.example.newproject.ai.AiAvailability
import com.example.newproject.ai.AiClient
import com.example.newproject.controller.RemarkController
import com.example.newproject.controller.ReplySaveOutcome
import com.example.newproject.model.DocumentRef
import com.example.newproject.model.NoteUiState
import com.example.newproject.model.NoteUiStateStore
import com.example.newproject.model.Reflection
import com.example.newproject.model.RelatedNote
import com.example.newproject.model.state.AiNoticeAction
import com.example.newproject.model.state.RemarkState
import com.example.newproject.model.state.ReplyStatus
import com.example.newproject.model.state.canRequestRemark
import com.example.newproject.fakes.FakeAiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ノートへのひとことの生成。
 *
 * **保存を自分で行わないことが設計の要**なので、[onRemarkReady] へ渡ったかどうかを
 * 中心に押さえる（→ features/reflect_remark.md §2.1）。ここを渡し忘れると、
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
            FakeAiClient.returning("「読書は著者との対話である」という考えは、反対するときにも成り立つだろうか？"),
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
            FakeAiClient.returning("[[C01]]と並べると、「対話」の始め方まで考えられそうです。"),
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
            FakeAiClient.returning("この内容をもっと掘り下げて整理すると、新しい発見があるかもしれませんね。"),
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
        val controller = controller(state, FakeAiClient.returning("NONE"))

        controller.create("対話について", body, relatedNotes = emptyList(), aiNotes = emptyList())

        assertTrue(state.value.remarkState is RemarkState.Empty)
    }

    /**
     * **非対応をエラーへ畳まない。** 畳んでいたころは「ひとことをもらえませんでした。」に
     * 続けて理由が出たうえ、**「別のひとことをもらう」が押せたまま**だった。
     * 何度押しても同じ答えが返るので、導線ごと消えていることまで固定する。
     */
    @Test
    fun `AI非対応は失敗ではなく説明になり、もう一度きく導線が消える`() = runTest {
        val state = NoteUiStateStore(NoteUiState())
        val controller = controller(state, FakeAiClient(AiAvailability.Unsupported))

        controller.create("対話について", body, relatedNotes = emptyList(), aiNotes = emptyList())

        val notice = (state.value.remarkState as RemarkState.AiNotice).notice
        assertEquals("この端末ではひとことを利用できません。", notice.message)
        assertEquals(AiNoticeAction.None, notice.action)
        assertFalse(state.value.remarkState.canRequestRemark())
    }

    /**
     * **DL実行中は自動DLを始めない。** `downloadModel()` を呼んでよいのは
     * `DOWNLOADABLE` のときだけ（beta2 の `downloadFeatureInternal` に状態の門番が無い）。
     */
    @Test
    fun `DL実行中は自動DLを始めず待つ`() = runTest {
        val state = NoteUiStateStore(NoteUiState())
        val ai = FakeAiClient(AiAvailability.Downloading)
        val controller = controller(state, ai)

        controller.create("対話について", body, relatedNotes = emptyList(), aiNotes = emptyList())

        assertEquals("DL中に download() を呼ばないこと", 0, ai.downloadCalls)
        assertEquals(0, ai.generateCalls)
        val notice = (state.value.remarkState as RemarkState.AiNotice).notice
        assertEquals(AiNoticeAction.None, notice.action)
        // **入口は閉じない。** 閉じるとDL完了後に押し直せなくなる。
        assertTrue(
            "DL中でも「ひとことをもらう」は押せること",
            state.value.remarkState.canRequestRemark()
        )
    }

    /** **DL完了後に押し直せる。** 入口を閉じると同じノートで二度ともらえなくなる。 */
    @Test
    fun `DL完了後に押し直すとひとことが届く`() = runTest {
        val state = NoteUiStateStore(NoteUiState())
        val ai = FakeAiClient(AiAvailability.Downloading) {
            "「読書は著者との対話である」は、反対する相手にも当てはまるだろうか？"
        }
        val controller = controller(state, ai)

        controller.create("対話について", body, relatedNotes = emptyList(), aiNotes = emptyList())
        assertTrue(state.value.remarkState.canRequestRemark())

        ai.availability = AiAvailability.Ready
        controller.create("対話について", body, relatedNotes = emptyList(), aiNotes = emptyList())

        assertTrue(
            "DL完了後の再操作で生成まで進むこと: ${state.value.remarkState}",
            state.value.remarkState is RemarkState.Ready
        )
    }

    /** **取得失敗は押す意味がある。** 非対応と同じ枝へ畳むと導線ごと消えてしまう。 */
    @Test
    fun `状態を取得できなかっただけならもう一度きける`() = runTest {
        val state = NoteUiStateStore(NoteUiState())
        val controller = controller(state, FakeAiClient(AiAvailability.TemporarilyUnavailable(IllegalStateException("AICore not bound"))))

        controller.create("対話について", body, relatedNotes = emptyList(), aiNotes = emptyList())

        val notice = (state.value.remarkState as RemarkState.AiNotice).notice
        assertEquals(AiNoticeAction.Retry, notice.action)
        assertTrue(state.value.remarkState.canRequestRemark())
    }

    @Test
    fun `生成中の連続タップでは二重に走らせない`() = runTest {
        val state = NoteUiStateStore(NoteUiState())
        val ai = FakeAiClient.deferred()
        val controller = controller(state, ai)

        controller.create("対話について", body, relatedNotes = emptyList(), aiNotes = emptyList())
        controller.create("対話について", body, relatedNotes = emptyList(), aiNotes = emptyList())

        assertEquals(1, ai.generateCalls)
        ai.completeAll("NONE")
    }

    /**
     * ノート切替後に届いた結果を、新しいノートの画面へ出さない。
     * **痕跡へも預けない** — 旧ノートの本文から作られた文が、
     * 新ノートのサイドカーへ入るのが最悪の壊れ方になる。
     */
    @Test
    fun `ノート切替後に届いた結果は捨てられる`() = runTest {
        val state = NoteUiStateStore(NoteUiState())
        val ai = FakeAiClient.deferred()
        val handed = mutableListOf<String>()
        val controller = controller(state, ai, onRemarkReady = { handed += it.remark })

        controller.create("対話について", body, relatedNotes = emptyList(), aiNotes = emptyList())
        controller.cancelAndClear()
        ai.completeAll("「読書は著者との対話である」は、反対する相手にも当てはまるだろうか？")

        assertTrue(state.value.remarkState is RemarkState.Idle)
        assertTrue(handed.isEmpty())
    }

    @Test
    fun `ノート切替で状態がIdleへ戻る`() = runTest {
        val state = NoteUiStateStore(NoteUiState(remarkState = RemarkState.Loading("旧ノート")))

        controller(state, FakeAiClient(AiAvailability.Unsupported)).cancelAndClear()

        assertTrue(state.value.remarkState is RemarkState.Idle)
    }

    // 出力は1件なので候補を多く見せる意味が無い。8件のタイトルより3件＋抜粋を選ぶ。
    @Test
    fun `候補ノートは3件で切られ現ノート自身と重複は除かれる`() = runTest {
        val state = NoteUiStateStore(NoteUiState())
        val ai = FakeAiClient.returning("NONE")
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
        val ai = FakeAiClient.returning("NONE")
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
        val ai = FakeAiClient.returning("NONE")
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
            FakeAiClient.returning("「読書は著者との対話である」は、反対する相手にも当てはまるだろうか？"),
            persistReply = { path, reply, at ->
                saved += Triple(path, reply, at)
                ReplySaveOutcome.Saved
            }
        )
        controller.create("対話について", body, relatedNotes = emptyList(), aiNotes = emptyList())

        controller.saveReply("ideas/dialog.md", "  実際に困った場面があった  ")

        val ready = state.value.remarkState as RemarkState.Ready
        assertEquals(ReplyStatus.Saved, ready.replyStatus)
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
            FakeAiClient.returning("「読書は著者との対話である」は、反対する相手にも当てはまるだろうか？"),
            persistReply = { _, reply, _ -> saved += reply; ReplySaveOutcome.Saved }
        )
        controller.create("対話について", body, relatedNotes = emptyList(), aiNotes = emptyList())

        controller.saveReply("ideas/dialog.md", "   ")

        assertTrue(saved.isEmpty())
        assertNull((state.value.remarkState as RemarkState.Ready).reflection.reply)
    }

    /**
     * **預かった（Held）は失敗として見せない。** 離脱時に書かれるため、
     * 「保存できませんでした」と出すと実際には残っているのに失敗したと読まれる。
     */
    @Test
    fun `預かった返事は未保存として出さない`() = runTest {
        val state = NoteUiStateStore(NoteUiState())
        val controller = controller(
            state,
            FakeAiClient.returning("「読書は著者との対話である」は、反対する相手にも当てはまるだろうか？"),
            persistReply = { _, _, _ -> ReplySaveOutcome.Held }
        )
        controller.create("対話について", body, relatedNotes = emptyList(), aiNotes = emptyList())

        controller.saveReply("ideas/dialog.md", "預かってもらう返事")

        val ready = state.value.remarkState as RemarkState.Ready
        assertEquals("預かってもらう返事", ready.reflection.reply)
        // **「保存済み」とは呼ばない。** 離脱時の書き込みで確定するまでは保存中。
        assertEquals(ReplyStatus.Held, ready.replyStatus)
    }

    /**
     * **失った（Lost）は必ず見せる。** 以前はすべて「保存済み」と表示しており、
     * ユーザーの返事が黙って消える経路があった。
     */
    @Test
    fun `保存できなかった返事は未保存として出す`() = runTest {
        val state = NoteUiStateStore(NoteUiState())
        val controller = controller(
            state,
            FakeAiClient.returning("「読書は著者との対話である」は、反対する相手にも当てはまるだろうか？"),
            persistReply = { _, _, _ -> ReplySaveOutcome.Lost }
        )
        controller.create("対話について", body, relatedNotes = emptyList(), aiNotes = emptyList())

        controller.saveReply("ideas/dialog.md", "消えたら困る返事")

        val ready = state.value.remarkState as RemarkState.Ready
        assertEquals(ReplyStatus.Failed, ready.replyStatus)
        // 本文は状態へ残す。消すと書き直しもできない。
        assertEquals("消えたら困る返事", ready.reflection.reply)
    }

    @Test
    fun `例外も未保存として扱う`() = runTest {
        val state = NoteUiStateStore(NoteUiState())
        val controller = controller(
            state,
            FakeAiClient.returning("「読書は著者との対話である」は、反対する相手にも当てはまるだろうか？"),
            persistReply = { _, _, _ -> error("書き込み失敗") }
        )
        controller.create("対話について", body, relatedNotes = emptyList(), aiNotes = emptyList())

        controller.saveReply("ideas/dialog.md", "例外でも残したい")

        assertEquals(
            ReplyStatus.Failed,
            (state.value.remarkState as RemarkState.Ready).replyStatus
        )
    }

    // ── 映し返し ─────────────────────────────────────────────────────────────

    /**
     * **保存済みを読み戻した後でも映し返しが出る。**
     *
     * 以前は `create()` が受け取った本文をフィールドへ写しており、`restoreSaved` は
     * そこを通らないため `lastContent ?: return` で黙って抜けていた。
     * Rediscover の「前回の返事を見る」から入った場合がこの経路。
     */
    @Test
    fun `保存済みを読み戻した後でも映し返しが出る`() = runTest {
        val state = NoteUiStateStore(NoteUiState())
        val mirrored = mutableListOf<String>()
        val controller = controller(
            state,
            FakeAiClient.returning("「対話」を、反論まで含む応答として捉えている。"),
            saved = Reflection("前回のひとこと", 1L),
            persistMirrored = { _, text -> mirrored += text }
        )
        controller.restoreSaved("ideas/dialog.md", "対話について")

        controller.saveReply("ideas/dialog.md", "実際に困った場面があった")

        val ready = state.value.remarkState as RemarkState.Ready
        assertEquals("「対話」を、反論まで含む応答として捉えている。", ready.reflection.mirrored)
        assertEquals(listOf("「対話」を、反論まで含む応答として捉えている。"), mirrored)
    }

    // 本文が手元に無ければ映し返しは作らない（ノートを閉じた後など）。
    @Test
    fun `本文が無ければ映し返しは作らない`() = runTest {
        val state = NoteUiStateStore(NoteUiState())
        val controller = controller(
            state,
            FakeAiClient.returning("「対話」を、反論まで含む応答として捉えている。"),
            saved = Reflection("前回のひとこと", 1L),
            currentContent = { null }
        )
        controller.restoreSaved("ideas/dialog.md", "対話について")

        controller.saveReply("ideas/dialog.md", "実際に困った場面があった")

        assertNull((state.value.remarkState as RemarkState.Ready).reflection.mirrored)
    }

    /**
     * **状態確認が契約違反で投げても、映し返しは黙って諦める。**
     *
     * この経路は `AiAvailabilityContractTest` の対応表の #4。無音の経路なので
     * 観測点が状態ではなく「何も出さない」ことにあり、足場のあるここへ置いてある。
     * 返事そのものは保存済みなので、失敗を見せないのが仕様（→ `generateMirror` のKDoc）。
     */
    @Test
    fun `状態確認が投げても映し返しは黙って諦める`() = runTest {
        val state = NoteUiStateStore(NoteUiState())
        val ai = FakeAiClient.returning("「対話」を、反論まで含む応答として捉えている。")
        ai.availabilityFailure = { IllegalStateException("AICore not bound") }
        val controller = controller(state, ai, saved = Reflection("前回のひとこと", 1L))
        controller.restoreSaved("ideas/dialog.md", "対話について")

        controller.saveReply("ideas/dialog.md", "実際に困った場面があった")

        val ready = state.value.remarkState as RemarkState.Ready
        assertNull("映し返しは作らない", ready.reflection.mirrored)
        assertEquals("返事は残る", "実際に困った場面があった", ready.reflection.reply)
    }

    /** キャンセルも同じく黙る（ノート切替でエラーを出さない）。 */
    @Test
    fun `状態確認がキャンセルでも映し返しは静かに終わる`() = runTest {
        val state = NoteUiStateStore(NoteUiState())
        val ai = FakeAiClient.returning("応答")
        ai.availabilityFailure = { kotlinx.coroutines.CancellationException("note changed") }
        val controller = controller(state, ai, saved = Reflection("前回のひとこと", 1L))
        controller.restoreSaved("ideas/dialog.md", "対話について")

        controller.saveReply("ideas/dialog.md", "実際に困った場面があった")

        val ready = state.value.remarkState as RemarkState.Ready
        assertNull(ready.reflection.mirrored)
    }

    // 保存できなかった返事へは映し返しを作らない。消える返事へ応じても仕方がない。
    @Test
    fun `保存できなかった返事には映し返しを作らない`() = runTest {
        val state = NoteUiStateStore(NoteUiState())
        val controller = controller(
            state,
            FakeAiClient.returning("「対話」を、反論まで含む応答として捉えている。"),
            saved = Reflection("前回のひとこと", 1L),
            persistReply = { _, _, _ -> ReplySaveOutcome.Lost }
        )
        controller.restoreSaved("ideas/dialog.md", "対話について")

        controller.saveReply("ideas/dialog.md", "消えてしまう返事")

        assertNull((state.value.remarkState as RemarkState.Ready).reflection.mirrored)
    }

    // ── 保存済みの読み戻し ───────────────────────────────────────────────────

    @Test
    fun `専用画面を開くと保存済みの組が復元される`() = runTest {
        val state = NoteUiStateStore(NoteUiState())
        val stored = Reflection("前回のひとこと", 1L, "前回の返事", 2L)
        val controller = controller(state, FakeAiClient(AiAvailability.Unsupported), saved = stored)

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
        val ai = FakeAiClient.deferred()
        val controller = controller(state, ai, saved = Reflection("古いひとこと", 1L))

        controller.create("対話について", body, relatedNotes = emptyList(), aiNotes = emptyList())
        controller.restoreSaved("ideas/dialog.md", "対話について")

        assertTrue(state.value.remarkState is RemarkState.Loading)
        ai.completeAll("NONE")
    }

    @Test
    fun `保存済みが無ければIdleのまま`() = runTest {
        val state = NoteUiStateStore(NoteUiState())

        controller(state, FakeAiClient(AiAvailability.Unsupported), saved = null).restoreSaved("ideas/dialog.md", "対話について")

        assertTrue(state.value.remarkState is RemarkState.Idle)
    }

    // ── ヘルパ ───────────────────────────────────────────────────────────────

    private fun controller(
        state: NoteUiStateStore,
        aiClient: AiClient,
        onRemarkReady: (Reflection) -> Unit = {},
        saved: Reflection? = null,
        persistReply: suspend (String, String, Long) -> ReplySaveOutcome =
            { _, _, _ -> ReplySaveOutcome.Saved },
        persistMirrored: suspend (String, String) -> Unit = { _, _ -> },
        currentContent: () -> String? = { body }
    ) = RemarkController(
        scope = CoroutineScope(Dispatchers.Unconfined),
        aiClient = aiClient,
        state = state.remarkWriter,
        onRemarkReady = onRemarkReady,
        persistReply = persistReply,
        loadReflection = { saved },
        currentContent = currentContent,
        persistMirrored = persistMirrored,
        clock = { FIXED_NOW },
        excerptDispatcher = Dispatchers.Unconfined
    )

    private fun candidateLinesOf(ai: FakeAiClient): List<String> =
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

}
