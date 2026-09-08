package com.example.newproject

import com.example.newproject.controller.BOOKLET_SIZE
import com.example.newproject.model.BookletSeed
import com.example.newproject.model.DocumentRef
import com.example.newproject.model.RelatedNote
import com.example.newproject.model.buildWeaveState
import com.example.newproject.model.state.RelatedNotesState
import com.example.newproject.model.state.WeaveBlockedReason
import com.example.newproject.model.state.WeaveState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 編む束の中身を固定する（→ features/booklet_mode.md 判断12）。
 *
 * **ここは純関数なので、素のJVMで組み立てて結果で見る。**
 * 「トグルをどう見せるか」の3通りが型に写っているので、見せ方の判定もここで落ちる。
 */
class BookletWeaveTest {

    // ── 3通りの見せ方 ────────────────────────────────────────────────────────

    /** 種が無ければトグル自体を出さない。**行き先が1つしか無いのに選択肢を見せない。** */
    @Test
    fun `種が無ければ編む余地が無い`() {
        assertEquals(WeaveState.NoSeed, weave(seed = null, related = success(ai = listOf("A.md"))))
    }

    /** タイトルの無いノートは種にならない（トグルのラベルが作れない）。 */
    @Test
    fun `種のタイトルが空なら編む余地が無い`() {
        assertEquals(
            WeaveState.NoSeed,
            weave(seed = BookletSeed(DocumentRef("content://fake/種"), ""), related = success(ai = listOf("A.md")))
        )
    }

    /**
     * **まだ探している最中と、探した結果が無かったのを畳まない。**
     * 待てば変わるかどうかで、利用者の次の行動が違う。
     */
    @Test
    fun `関連ノートが走行中なら押せない理由は探している最中`() {
        assertEquals(
            WeaveState.Blocked("種", WeaveBlockedReason.Pending),
            weave(related = RelatedNotesState.Loading)
        )
    }

    /**
     * **Idle も「探している最中」に畳む。** 冊子へ入る時点でノートが開いているなら
     * 関連ノートは必ず走っているので、Idle は開始直後の一瞬である。
     */
    @Test
    fun `関連ノートが未着手でも探している最中として扱う`() {
        assertEquals(
            WeaveState.Blocked("種", WeaveBlockedReason.Pending),
            weave(related = RelatedNotesState.Idle)
        )
    }

    @Test
    fun `関連ノートが失敗していたら押せない理由は取れなかった`() {
        assertEquals(
            WeaveState.Blocked("種", WeaveBlockedReason.Failed),
            weave(related = RelatedNotesState.Error("失敗"))
        )
    }

    @Test
    fun `候補が1件も無ければ押せない理由は見つからなかった`() {
        assertEquals(
            WeaveState.Blocked("種", WeaveBlockedReason.Empty),
            weave(related = success())
        )
    }

    // ── 束の中身 ─────────────────────────────────────────────────────────────

    /**
     * **AI推薦が先、決定的な関連のうち未リンクが次、wikilink済みが最後。**
     *
     * 既に繋がっているノートへ「再会」しても新しくないので後ろへ回す
     * （`RemarkController` の候補選定と同じ並べ方）。
     */
    @Test
    fun `AI推薦を先に未リンクを次にwikilink済みを最後に並べる`() {
        val state = weave(
            related = RelatedNotesState.Success(
                relatedNotes = listOf(note("リンク済み.md", wikilinked = true), note("未リンク.md")),
                aiNotes = listOf(note("AI推薦.md"))
            )
        )

        assertEquals(
            listOf("AI推薦.md", "未リンク.md", "リンク済み.md"),
            (state as WeaveState.Ready).bundle.entries.map { it.title }
        )
    }

    /** 同じノートがAI経路と決定的経路の両方から来る。**参照で畳む。** */
    @Test
    fun `同じノートは一度しか入らない`() {
        val both = note("両方から.md")
        val state = weave(
            related = RelatedNotesState.Success(relatedNotes = listOf(both), aiNotes = listOf(both))
        )

        assertEquals(listOf("両方から.md"), (state as WeaveState.Ready).bundle.entries.map { it.title })
    }

    /** 種自身が候補に混ざることがある。**いま居たノートへ戻る紙を束へ入れない。** */
    @Test
    fun `種のノート自身は束に入らない`() {
        val seedRef = DocumentRef("content://fake/種.md")
        val state = weave(
            seed = BookletSeed(seedRef, "種"),
            related = RelatedNotesState.Success(
                relatedNotes = emptyList(),
                aiNotes = listOf(RelatedNote(title = "種.md", ref = seedRef, isWikilinked = false), note("別.md"))
            )
        )

        assertEquals(listOf("別.md"), (state as WeaveState.Ready).bundle.entries.map { it.title })
    }

    @Test
    fun `10枚を超えたら先頭の10枚だけを束にする`() {
        val state = weave(related = success(ai = (1..25).map { "ノート$it.md" }))

        assertEquals(BOOKLET_SIZE, (state as WeaveState.Ready).bundle.entries.size)
    }

    /**
     * **水増ししない。** 後半をランダムで埋めると「関係ないノイズ」ではなく
     * 「**関係あると思って読むノイズ**」になり、何枚目で関連が切れたか分からなくなる。
     */
    @Test
    fun `候補が薄ければ薄いまま実際の枚数を出す`() {
        val state = weave(related = success(ai = listOf("A.md", "B.md", "C.md")))

        assertEquals(3, (state as WeaveState.Ready).bundle.entries.size)
    }

    /** 扉はタイトルへフォールバックするので、タイトルの無い候補は白紙の紙になる。 */
    @Test
    fun `タイトルの無い候補は束に入らない`() {
        val state = weave(
            related = RelatedNotesState.Success(
                relatedNotes = emptyList(),
                aiNotes = listOf(RelatedNote(title = "  ", ref = DocumentRef("content://fake/空"), isWikilinked = false), note("有る.md"))
            )
        )

        assertEquals(listOf("有る.md"), (state as WeaveState.Ready).bundle.entries.map { it.title })
    }

    /** 束の世代は呼び出し側が決める。**既定値の 0 のままだと切り替えで積み直りが出ない。** */
    @Test
    fun `渡された世代が束に載る`() {
        val state = weave(related = success(ai = listOf("A.md")), bundleId = 7L)

        assertEquals(7L, (state as WeaveState.Ready).bundle.bundleId)
    }

    /** 束を作った直後は必ず1枚目から。 */
    @Test
    fun `編んだ束は1枚目から始まる`() {
        val state = weave(related = success(ai = listOf("A.md")))

        assertTrue((state as WeaveState.Ready).bundle.page == 0)
    }

    // ── 補助 ──────────────────────────────────────────────────────────────

    private fun weave(
        seed: BookletSeed? = BookletSeed(DocumentRef("content://fake/種.md"), "種"),
        related: RelatedNotesState,
        bundleId: Long = 1L
    ): WeaveState = buildWeaveState(seed, related, BOOKLET_SIZE, bundleId)

    private fun success(ai: List<String> = emptyList()) =
        RelatedNotesState.Success(relatedNotes = emptyList(), aiNotes = ai.map { note(it) })

    private fun note(name: String, wikilinked: Boolean = false) =
        RelatedNote(title = name, ref = DocumentRef("content://fake/$name"), isWikilinked = wikilinked)
}
