package com.example.newproject

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.ui.MotionDurationScale
import com.example.newproject.controller.BookletController
import com.example.newproject.model.DocumentRef
import com.example.newproject.model.NoteFile
import com.example.newproject.model.NoteUiState
import com.example.newproject.model.NoteUiStateStore
import com.example.newproject.model.state.BookletState
import com.example.newproject.ui.screen.BookletRestackRule
import com.example.newproject.ui.screen.RESTACK_MILLIS
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 積み直り（新しい束が一度浮いて置き直される）の再生条件を固定する。
 *
 * ## なぜ要るか
 *
 * 最初の実装は「`Loading` を観測したか」で再生を決めていた。**中間状態は画面に届くとは限らない。**
 * 📖 と「もう10枚引く」は束を作り始めてから画面が動くので、ノート一覧が60秒キャッシュから
 * **同期で返ると `Loading` は次の `Open` に上書きされ、誰にも届かない。**
 * その結果、**キャッシュが効いている間の引き直しでは演出が出なかった**
 * （2026-09-03 のレビュー `P2-1`）。
 *
 * **ここで見るのは再生の契機だけで、手触りの良し悪しではない。**
 * 見え方の判定は実機検証のケース表が持つ（→ docs/dev/system/bearing_channels.md §7）。
 *
 * ## 画面が観測できるものだけを渡す
 *
 * 画面の購読はフレーム境界でまとめられるので、**1回の操作につき最終状態を1回だけ**
 * ルールへ渡す。更新のたびに渡す形にすると、届かないはずの `Loading` まで
 * 見えることになり、再現しなくなる。
 */
class BookletRestackTest {

    // ── 再生の契機 ────────────────────────────────────────────────────────

    /** **本体の受け入れ条件。** 一覧が一度もsuspendしない引き直しでも再生する。 */
    @Test
    fun `キャッシュから同期で引き直しても積み直りが再生される`() = runTest {
        val store = NoteUiStateStore(NoteUiState())
        val controller = controller(store)
        val rule = BookletRestackRule()
        // **同じ並びを返す。** 引き直した束の中身が前と同じになることは実際に起きるので、
        // 中身の比較で代用できないことをここで固定する。
        val notes = listOf(noteFile("ノート1.md"), noteFile("ノート2.md"))

        controller.draw { notes }
        assertFalse("最初の束は「届いた」ではなく「もう在った」", rule.onBundle(observe(store).drawId))

        controller.draw { notes }
        assertTrue("キャッシュ経由の引き直しで再生されない", rule.onBundle(observe(store).drawId))
    }

    /** 待ちを挟む引き直しでも、**成功した引き直しにつき1回だけ**。 */
    @Test
    fun `待ちを挟む引き直しでも再生は1回だけ`() = runTest {
        val store = NoteUiStateStore(NoteUiState())
        val controller = controller(store)
        val rule = BookletRestackRule()
        controller.draw { listOf(noteFile("一冊目.md")) }
        rule.onBundle(observe(store).drawId)

        val waiting = CompletableDeferred<List<NoteFile>>()
        controller.draw { waiting.await() }
        assertTrue("待っている間は束ではない", store.value.bookletState is BookletState.Loading)
        waiting.complete(listOf(noteFile("二冊目.md")))

        val drawId = observe(store).drawId
        assertTrue(rule.onBundle(drawId))
        assertFalse("同じ束で二度目が始まる", rule.onBundle(drawId))
    }

    /** 扉が読めた・ページが動いたでは再生しない。**束は入れ替わっていない。** */
    @Test
    fun `扉の読込とページ送りでは再生しない`() = runTest {
        val store = NoteUiStateStore(NoteUiState())
        val controller = controller(store, FakeVaultBrowser(FakeVaultHandle(snippets = { "本文である。" })))
        val rule = BookletRestackRule()
        controller.draw { listOf(noteFile("ノート1.md"), noteFile("ノート2.md")) }
        rule.onBundle(observe(store).drawId)

        controller.onPageSettled(page = 1)

        val open = observe(store)
        assertNotEquals("扉が読めていない（前提が崩れている）", BookletState.Open(emptyList()), open)
        assertEquals(1, open.page)
        assertFalse(rule.onBundle(open.drawId))
    }

    /**
     * ノートから戻る往復では再生しない。
     *
     * 冊子ルートの composition ごと作り直されるので、**ルールも作り直される** —
     * 戻ってきた束が「最初に見た束」になる。ここではその作り直しを新しい
     * [BookletRestackRule] で表す。
     */
    @Test
    fun `ノートから戻っても再生しない`() = runTest {
        val store = NoteUiStateStore(NoteUiState())
        controller(store).draw { listOf(noteFile("ノート.md")) }
        val open = observe(store)
        BookletRestackRule().onBundle(open.drawId)

        assertFalse("同じ束へ戻っただけで置き直された", BookletRestackRule().onBundle(open.drawId))
    }

    /**
     * **束を作る経路は本番に1つしかなく、そこが必ず世代を渡す。**
     *
     * [BookletState.Open] の `drawId` に既定値があるのは、世代に関心の無いフィクスチャのためである。
     * **製品コードが既定値を受け取ってよいという意味ではない** — 世代を渡し忘れた束は 0 になり、
     * 直前が 0 以外なら「別の束が届いた」と誤って読める。
     *
     * **見るのは生成箇所の数と世代の受け渡しまで**で、再生の可否は上の4件が結果で見る（→ L55）。
     */
    @Test
    fun `束を作る経路は本番に1つしかなく必ず世代を渡す`() {
        val producers = File("src/main/java/com/example/newproject")
            .walkTopDown()
            .filter { it.extension == "kt" && it.readText().contains("BookletState.Open(") }
            .map { it.name }
            .sorted()
            .toList()

        assertEquals(
            "束を作る経路が増えています。増やすなら、そこも世代を進めてください" +
                "（→ docs/dev/features/booklet_mode.md 判断10）。",
            listOf("BookletController.kt"),
            producers
        )
        assertTrue(
            "束を作るのに世代を渡していません。既定値の 0 が入ると、積み直りが誤って再生されます。",
            File("src/main/java/com/example/newproject/controller/BookletController.kt")
                .readText()
                .contains("drawId = drawId")
        )
    }

    /**
     * **積み直りの効果は状態を鍵にしない。**
     *
     * 鍵にすると、ページを送るたびに `state` が別インスタンスになるので効果が作り直され、
     * **再生中の1回送りで演出が打ち切られ、紙が浮いたまま止まる。**
     * 走査で見るのは鍵だけで、止まらないことそのものは実機検証のケース表が見る（→ L55）。
     */
    @Test
    fun `積み直りの効果はページ送りで作り直されない`() {
        val effect = File("src/main/java/com/example/newproject/ui/screen/BookletScreen.kt")
            .readText()
            .substringAfter("val rule = BookletRestackRule()", "")

        assertTrue("積み直りの効果が見つかりません。", effect.isNotEmpty())
        assertTrue(
            "積み直りの効果が `LaunchedEffect(Unit)` の中にありません。状態を鍵にすると、" +
                "ページを送った瞬間に演出が打ち切られます。",
            File("src/main/java/com/example/newproject/ui/screen/BookletScreen.kt")
                .readText()
                .substringBefore("val rule = BookletRestackRule()")
                .trimEnd()
                .endsWith("LaunchedEffect(Unit) {")
        )
    }

    // ── OSのアニメーション設定 ─────────────────────────────────────────────

    /**
     * **積み直りだけが時間で進むので、OSの「アニメーションを無効」設定に従って潰れる。**
     *
     * 指に追従する変化（紙の縮み・影）は時間を持たないので設定の対象外である。
     * この非対称は判断10の契約そのもので、**採用版の Compose が実際にそう振る舞うことを
     * ここで確かめる**（設定を打ち消す実装は置いていない → docs/dev/lessons/L39.md）。
     */
    @Test
    fun `積み直りはOSのアニメーション設定に従う`() {
        assertTrue(
            "倍率0でも積み直りが動いています。設定を打ち消す実装が入っていないか確認してください。",
            finishesOnFirstFrame(scaleFactor = 0f)
        )
        assertFalse(
            "倍率1で積み直りが最初のフレームで終わっています。時間で進んでいません。",
            finishesOnFirstFrame(scaleFactor = 1f)
        )
        // **上の2件は採用版Composeの契約を確かめただけで、こちらが打ち消していないことは別。**
        // 倍率を上書きすると、設定を切っている利用者にだけ演出が戻る。
        assertFalse(
            "冊子の画面がアニメーション倍率を上書きしています（→ features/booklet_mode.md 判断10）。",
            File("src/main/java/com/example/newproject/ui/screen/BookletScreen.kt")
                .readText()
                .contains("MotionDurationScale")
        )
    }

    /** 最初のフレームを1つだけ送って、そこで積み直りが終わるか。 */
    private fun finishesOnFirstFrame(scaleFactor: Float): Boolean = runBlocking {
        val clock = BroadcastFrameClock()
        val scale = object : MotionDurationScale {
            override val scaleFactor: Float = scaleFactor
        }
        val restack = Animatable(0f)
        // Unconfined なので、起動した時点で最初のフレーム待ちに入っている。
        val job = launch(Dispatchers.Unconfined + clock + scale) {
            restack.animateTo(1f, animationSpec = tween(RESTACK_MILLIS))
        }
        clock.sendFrame(0L)
        val finished = !job.isActive
        job.cancel()
        finished
    }

    // ── 補助 ──────────────────────────────────────────────────────────────

    /**
     * **画面が見るのと同じ粒度で1回だけ取り出す。**
     * 更新のたびに覗くと、実際には届かない中間状態まで見えてしまう。
     */
    private fun observe(store: NoteUiStateStore): BookletState.Open =
        store.value.bookletState as BookletState.Open

    private fun controller(
        store: NoteUiStateStore,
        vault: FakeVaultBrowser = FakeVaultBrowser(FakeVaultHandle())
    ) = BookletController(
        scope = CoroutineScope(Dispatchers.Unconfined),
        vault = vault,
        state = store.bookletWriter,
        vaultGeneration = { 0L },
        coverDispatcher = Dispatchers.Unconfined,
        shuffle = { it }
    )

    private fun noteFile(name: String): NoteFile =
        NoteFile(name = name, ref = DocumentRef("content://fake/$name"))
}
