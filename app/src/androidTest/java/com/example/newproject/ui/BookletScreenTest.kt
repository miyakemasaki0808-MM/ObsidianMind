package com.example.newproject.ui

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.newproject.model.BookletCover
import com.example.newproject.model.BookletEntry
import com.example.newproject.model.DocumentRef
import com.example.newproject.model.state.BookletBundle
import com.example.newproject.model.state.BookletMode
import com.example.newproject.model.state.BookletState
import com.example.newproject.model.state.WeaveBlockedReason
import com.example.newproject.model.state.WeaveState
import com.example.newproject.ui.screen.BookletScreen
import com.example.newproject.ui.screen.openFromBooklet
import com.example.newproject.ui.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * **扉が実際に描かれ、押した先が正しいことを固定する。**
 *
 * 選定そのものは `BookletCoverLineTest`、束の作り方は `BookletControllerTest` が
 * JVMで押さえている。**そこから「画面がその値を描く」ことは観測できない**ので、
 * 描画と配線だけをここで見る（→ `ReadingTraceCardPanelTest` と同じ理由）。
 */
@RunWith(AndroidJUnit4::class)
class BookletScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun 扉の代表文とタイトルが出る() {
        show(openState(listOf(entry("ノートA", BookletCover.Ready("最初の文である。")))))

        composeRule.onNodeWithText("最初の文である。").assertIsDisplayed()
        composeRule.onNodeWithText("ノートA").assertIsDisplayed()
    }

    @Test
    fun これを読むでその1枚が渡る() {
        val opened = mutableListOf<BookletEntry>()
        val target = entry("ノートA", BookletCover.Ready("最初の文である。"))
        show(openState(listOf(target)), onRead = { opened += it })

        composeRule.onNodeWithText("これを読む").performClick()

        assertEquals(listOf(target), opened)
    }

    /**
     * **読書ボタンはどのノートを開くかを名前で言う。**
     *
     * 読み上げでは全ページが「これを読む」になり、めくっても区別が付かない。
     * ページャは隣のページも同時に持つので、**同名のボタンが複数存在する**
     * （実機で往復テストが表示中の1件を選べなかったのもこれ → 2026-08-31）。
     */
    @Test
    fun 読書ボタンはどのノートを開くかを名乗る() {
        show(openState(listOf(entry("ノートA", BookletCover.Ready("本文である。")))))

        composeRule.onNodeWithContentDescription("「ノートA」を読む").assertIsDisplayed()
    }

    /** 束を作った後に消えたノート。**そのページだけ**開けなくする。 */
    @Test
    fun 読めなかったページは開けない() {
        show(openState(listOf(entry("消えたノート", BookletCover.Failed))))

        composeRule.onNodeWithText("このノートは開けませんでした。").assertIsDisplayed()
        composeRule.onNodeWithText("これを読む").assertIsNotEnabled()
    }

    @Test
    fun 読める扉なら開ける() {
        show(openState(listOf(entry("ノートA", BookletCover.Ready("本文である。")))))

        composeRule.onNodeWithText("これを読む").assertIsEnabled()
    }

    /**
     * **まだ読めていない扉は開けない。** 押せると、開けるか分からないノートへ先に遷移し、
     * ページ内に留めるはずの失敗が通常表示側の読込エラーに化ける。
     */
    @Test
    fun 読み込み中の扉は開けない() {
        val opened = mutableListOf<BookletEntry>()
        show(
            openState(listOf(entry("ノートA", BookletCover.Loading))),
            onRead = { opened += it }
        )

        composeRule.onNodeWithText("これを読む").assertIsNotEnabled()
        composeRule.onNodeWithText("これを読む").performClick()
        assertEquals(emptyList<BookletEntry>(), opened)
    }

    /**
     * 束は `min(10, 利用可能数)` なので、終端の文言を10枚と決め打たない。
     *
     * **ページ送りにセマンティック操作を使う。** スワイプより決定的で、
     * 同時に「読み上げ操作でめくれる」契約そのものも押さえられる。
     */
    @Test
    fun 終端は実際の枚数を出す() {
        val entries = (1..3).map { entry("ノート$it", BookletCover.Ready("$it 枚目。")) }
        show(openState(entries))

        repeat(entries.size) { turnPage("次のページへ") }

        composeRule.onNodeWithText("ここまでの3枚でした。").assertIsDisplayed()
    }

    @Test
    fun 前のページへ戻れる() {
        val entries = (1..2).map { entry("ノート$it", BookletCover.Ready("$it 枚目。")) }
        show(openState(entries))

        turnPage("次のページへ")
        assertPagePosition("2/2ページ")

        turnPage("前のページへ")
        assertPagePosition("1/2ページ")
    }

    /** 読み上げ操作（スイッチアクセス等）から1ページ動かす。 */
    private fun turnPage(label: String) {
        val actions = composeRule
            .onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions))
            .fetchSemanticsNode()
            .config[SemanticsActions.CustomActions]
        composeRule.runOnUiThread { actions.first { it.label == label }.action() }
        composeRule.waitForIdle()
    }

    /** 位置がスワイプでしか分からない画面なので、読み上げにも同じことを言わせる。 */
    @Test
    fun ページ位置は読み上げにも出る() {
        show(
            openState(
                listOf(
                    entry("ノートA", BookletCover.Ready("一枚目。")),
                    entry("ノートB", BookletCover.Ready("二枚目。"))
                )
            )
        )

        assertPagePosition("1/2ページ")
    }

    @Test
    fun ノートが無ければ引けないと伝える() {
        show(openState(emptyList()))

        composeRule.onNodeWithText("引けるノートがありません。").assertIsDisplayed()
    }

    @Test
    fun 束を作れなければ理由を出す() {
        show(BookletState.Failed("走査に失敗しました。"))

        composeRule.onNodeWithText("走査に失敗しました。").assertIsDisplayed()
    }

    @Test
    fun 表示したページは先読みを要求する() {
        val settled = mutableListOf<Int>()
        show(
            openState(listOf(entry("ノートA", BookletCover.Loading))),
            onPageSettled = { settled += it }
        )

        assertEquals(listOf(0), settled)
    }

    // ── 冊子から本文へ渡す境界 ───────────────────────────────────────────────

    /**
     * **渡した先は必ず本文の先頭から始まる。**
     *
     * `noteListState` は Activity 生存で共有され、ノート切替ではリセットされない。
     * 呼び出しの有無ではなく、**実際のスクロール位置**で確かめる。
     */
    @Test
    fun 冊子から渡すと本文は先頭から始まる() {
        lateinit var listState: LazyListState
        composeRule.setContent {
            listState = rememberLazyListState(initialFirstVisibleItemIndex = 3)
            LazyColumn(state = listState, modifier = Modifier.height(120.dp)) {
                items(30) { index -> Text("行$index", modifier = Modifier.height(40.dp)) }
            }
        }
        composeRule.waitForIdle()
        assertEquals(3, listState.firstVisibleItemIndex)

        composeRule.runOnUiThread { openFromBooklet(listState, open = {}, navigateToNote = {}) }
        composeRule.waitForIdle()

        assertEquals(0, listState.firstVisibleItemIndex)
        assertEquals(0, listState.firstVisibleItemScrollOffset)
    }

    /** 渡す順序も固定する。**読込を始めてから遷移する**（逆だと表示が先に切り替わる）。 */
    @Test
    fun 冊子から渡すと読込を始めてから遷移する() {
        val calls = mutableListOf<String>()
        lateinit var listState: LazyListState
        composeRule.setContent {
            listState = rememberLazyListState()
            LazyColumn(state = listState) { items(3) { Text("行$it") } }
        }

        composeRule.runOnUiThread {
            openFromBooklet(
                listState,
                open = { calls += "open" },
                navigateToNote = { calls += "navigate" }
            )
        }

        assertEquals(listOf("open", "navigate"), calls)
    }

    /**
     * **引き直しの失敗は、終端で理由と再試行を同時に見せる。**
     *
     * 冊子ごとエラー画面へ落とすと、押してすらいない編む束と種まで消える
     * （2026-09-07 のレビュー `P2-1`）。
     */
    @Test
    fun 引き直しに失敗すると終端に理由が出て再試行できる() {
        val entries = listOf(entry("ノートA", BookletCover.Ready("本文。")))
        show(openState(entries, redrawError = "走査に失敗しました。"))

        turnPage("次のページへ")

        composeRule.onNodeWithText("走査に失敗しました。").assertIsDisplayed()
        composeRule.onNodeWithText("もう10枚引く").assertIsEnabled()
    }

    // ── 引く⇄編むのトグル（判断12）──────────────────────────────────────────

    /** **行き先が1つしか無いのに選択肢を見せない。** */
    @Test
    fun 種が無ければトグルを出さない() {
        show(openState(listOf(entry("ノートA", BookletCover.Ready("本文。")))))

        composeRule.onNodeWithText("引く").assertDoesNotExist()
    }

    /** **トグルは種のノート名を名乗る。** 何から編むのかが画面から分からないと選べない。 */
    @Test
    fun 編めるならトグルが種の名前を出す() {
        show(weavableState())

        composeRule.onNodeWithText("引く").assertIsEnabled()
        composeRule.onNodeWithText("読書について.mdから編む").assertIsEnabled()
    }

    /**
     * **出すが押せない。** 押せない理由を添えるのは、
     * 出さないと「なぜ押せないのか」が画面のどこにも無いため。
     */
    @Test
    fun 編めないときは押せず理由が出る() {
        show(
            openState(
                entries = listOf(entry("ノートA", BookletCover.Ready("本文。"))),
                weave = WeaveState.Blocked("読書について.md", WeaveBlockedReason.Pending)
            )
        )

        composeRule.onNodeWithText("読書について.mdから編む").assertIsNotEnabled()
        // **待てば編める、と読ませない。** 種は📖を押した一瞬のコピーなので、
        // 開いたまま待っても編めるようにはならない（→ booklet_mode 判断12）。
        composeRule.onNodeWithText("関連ノートをまだ探しています。冊子を開き直すと編めます。").assertIsDisplayed()
    }

    /** **「探している最中」と「見つからなかった」を同じ文にしない。** */
    @Test
    fun 候補が無かったときは探し終えたと分かる文言を出す() {
        show(
            openState(
                entries = listOf(entry("ノートA", BookletCover.Ready("本文。"))),
                weave = WeaveState.Blocked("読書について.md", WeaveBlockedReason.Empty)
            )
        )

        composeRule.onNodeWithText("関連するノートが見つかりませんでした。").assertIsDisplayed()
    }

    @Test
    fun 編む側へ切り替えると呼び出し側へ伝える() {
        val modes = mutableListOf<BookletMode>()
        show(weavableState(), onModeChange = { modes += it })

        composeRule.onNodeWithText("読書について.mdから編む").performClick()

        assertEquals(listOf(BookletMode.Weave), modes)
    }

    /**
     * **編む側の終端に「もう10枚編む」は無い。**
     * 編みは決定的なので、押しても同じ10枚が出る。数だけを最後に1回言う。
     */
    @Test
    fun 編む側の終端は枚数だけを出す() {
        val woven = (1..2).map { entry("関連$it", BookletCover.Ready("$it 枚目。")) }
        show(
            openState(
                entries = listOf(entry("ノートA", BookletCover.Ready("本文。"))),
                weave = WeaveState.Ready("読書について.md", BookletBundle(woven, bundleId = 2L)),
                mode = BookletMode.Weave
            )
        )

        repeat(woven.size) { turnPage("次のページへ") }

        composeRule.onNodeWithText("「読書について.md」から編んだ2枚でした。").assertIsDisplayed()
        composeRule.onNodeWithText("もう10枚引く").assertDoesNotExist()
    }

    /** 編む側を見ているときは、編む束の紙が出る（引く束ではない）。 */
    @Test
    fun 編む側では編んだ束が出る() {
        show(
            openState(
                entries = listOf(entry("引いた1枚", BookletCover.Ready("引いた本文。"))),
                weave = WeaveState.Ready(
                    "読書について.md",
                    BookletBundle(listOf(entry("編んだ1枚", BookletCover.Ready("編んだ本文。"))), bundleId = 2L)
                ),
                mode = BookletMode.Weave
            )
        )

        composeRule.onNodeWithText("編んだ本文。").assertIsDisplayed()
        composeRule.onNodeWithText("引いた本文。").assertDoesNotExist()
    }

    private fun weavableState() = openState(
        entries = listOf(entry("ノートA", BookletCover.Ready("本文。"))),
        weave = WeaveState.Ready(
            "読書について.md",
            BookletBundle(listOf(entry("関連ノート", BookletCover.Ready("関連の本文。"))), bundleId = 2L)
        )
    )

    private fun entry(title: String, cover: BookletCover) = BookletEntry(
        ref = DocumentRef("content://fake/$title"),
        title = title,
        cover = cover
    )

    /** 既定は引く束だけの冊子（編む束は持たない）。 */
    private fun openState(
        entries: List<BookletEntry>,
        weave: WeaveState = WeaveState.NoSeed,
        mode: BookletMode = BookletMode.Draw,
        redrawError: String? = null
    ) = BookletState.Open(
        drawn = BookletBundle(entries),
        weave = weave,
        mode = mode,
        redrawError = redrawError
    )

    private fun show(
        state: BookletState,
        onPageSettled: (Int) -> Unit = {},
        onRead: (BookletEntry) -> Unit = {},
        onDrawAgain: () -> Unit = {},
        onModeChange: (BookletMode) -> Unit = {},
        onExit: () -> Unit = {}
    ) {
        composeRule.setContent {
            AppTheme(darkTheme = false) {
                BookletScreen(
                    // 分野の索引。**この検査は色を見ない**ので空で渡す（見え方は実機のケース表が持つ）。
                    noteFields = emptyMap(),
                    state = state,
                    onPageSettled = onPageSettled,
                    onRead = onRead,
                    onDrawAgain = onDrawAgain,
                    onModeChange = onModeChange,
                    onExit = onExit
                )
            }
        }
    }

    /**
     * いま何枚目かを、**読み上げの状態説明**で確かめる。
     *
     * **画面には出ない**（2026-09-06 にページ表示を外した）。位置はスワイプでしか分からないので、
     * **読み上げにだけ残してある**（→ `bookletPagePosition`）。
     */
    private fun assertPagePosition(spoken: String) {
        composeRule.onNode(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, spoken),
            useUnmergedTree = true
        ).assertIsDisplayed()
    }

}
