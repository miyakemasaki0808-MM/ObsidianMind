package com.example.newproject

import com.example.newproject.domain.composeMemoSectionTitle
import com.example.newproject.domain.markdown.NoteSectionModel
import com.example.newproject.domain.markdown.ReadFrontier
import com.example.newproject.domain.markdown.buildNoteSectionModel
import com.example.newproject.model.ReadingTraceLimits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// 再会カードの「読み進めたところ」と、その前後の切り出し・続きから読むの送り先
// （→ features/reunion_card.md 判断6）。送り先は実機でしか走らないテストに任せない（→ lessons L53）。
/** 前半・中盤・終盤に固有の文を置いた長い1段落。 */
private val LONG_TEXT = "前半の文。" + "これは埋め草の文です。".repeat(300) + "中盤の文。" +
    "これは埋め草の文です。".repeat(300) + "終盤の文。"

class ReunionPassageTest {

    /** 見出しの無い10ブロック。番号がそのままブロック番号になる。 */
    private val plain = buildNoteSectionModel((0 until 10).joinToString("\n\n") { "段落${it}の本文。" })

    // ── 読み進めたところ ─────────────────────────────────────────────────

    @Test
    fun `ブロックが無ければ読み進めたところは無い`() {
        assertNull(buildNoteSectionModel("").readFrontier(sectionTitle = null, progressPercent = 40))
    }

    /**
     * 到達率は「最深ブロックの番号＋そのブロックの見えている割合」を切り捨てた百分率。
     * 第8ブロックが全部見えた回は 90% になる。素直に割り戻すと未表示の第9ブロックを指す。
     */
    @Test
    fun `最深ブロックが全部見えた回は、そのブロックを指す`() {
        assertEquals(8, plain.readFrontier(sectionTitle = null, progressPercent = 90)!!.block)
    }

    @Test
    fun `到達率の見積もりは手前へ寄せる`() {
        // 第8ブロックの途中（8.5）なら 85%。
        assertEquals(8, plain.readFrontier(sectionTitle = null, progressPercent = 85)!!.block)
        assertEquals(0, plain.readFrontier(sectionTitle = null, progressPercent = 0)!!.block)
    }

    @Test
    fun `節が今の本文にあれば、その節の範囲へ収める`() {
        val model = buildNoteSectionModel(
            """
            # 導入
            導入1

            導入2

            # 本論
            本論1

            本論2

            本論3

            # まとめ
            まとめ1
            """.trimIndent()
        )
        // ブロック: 0導入 1導入1 2導入2 3本論 4本論1 5本論2 6本論3 7まとめ 8まとめ1。
        // 前に段落を足す編集で到達率が「まとめ」側へずれても、記録した「本論」の中へ戻す。
        assertEquals(6, model.readFrontier(sectionTitle = "本論", progressPercent = 90)!!.block)
        assertEquals(3, model.readFrontier(sectionTitle = "本論", progressPercent = 10)!!.block)
    }

    /**
     * 訪問の見出しは、メモと同じ整形（上限で切る）をかけて保存される。**生の見出しと比べると、長い見出しの節が見つからない。**
     */
    @Test
    fun `上限で切って記録した長い見出しの節も、その節の範囲へ収める`() {
        val longTitle = "本論".repeat(ReadingTraceLimits.MAX_SECTION_TITLE_BYTES / 6 + 10)
        val model = buildNoteSectionModel(
            "# 導入\n導入1\n\n導入2\n\n# $longTitle\n本論1\n\n本論2\n\n本論3\n\n# まとめ\nまとめ1"
        )
        val recorded = requireNotNull(composeMemoSectionTitle(longTitle))
        assertTrue(recorded.length < longTitle.length)
        // ブロック: 0導入 1導入1 2導入2 3本論 4本論1 5本論2 6本論3 7まとめ 8まとめ1。
        assertEquals(6, model.readFrontier(sectionTitle = recorded, progressPercent = 90)!!.block)
    }

    /** 整形の前に記録した訪問は、生の見出し名を持っている。**引くときにも同じ整形をかける。** */
    @Test
    fun `整形の前に生のまま記録した見出しの節も引ける`() {
        val raw = "本\u0007論"
        val model = buildNoteSectionModel("# 導入\n導入1\n\n導入2\n\n# $raw\n本論1\n\n本論2\n\n本論3\n\n# まとめ\nまとめ1")
        assertEquals(raw, model.sections[1].title)
        assertEquals(6, model.readFrontier(sectionTitle = raw, progressPercent = 90)!!.block)
    }

    @Test
    fun `節が見つからなければ到達率だけで決める`() {
        val model = headedModel()
        assertEquals(
            model.readFrontier(sectionTitle = null, progressPercent = 50),
            model.readFrontier(sectionTitle = "消えた節", progressPercent = 50)
        )
    }

    @Test
    fun `同名の見出しが複数あれば、見積もりに最も近いものを採る`() {
        val model = buildNoteSectionModel(
            """
            ## まとめ
            前のまとめ

            ## 本論
            本論1

            本論2

            ## まとめ
            後のまとめ
            """.trimIndent()
        )
        // ブロック: 0まとめ 1前のまとめ 2本論 3本論1 4本論2 5まとめ 6後のまとめ。
        assertEquals(6, model.readFrontier(sectionTitle = "まとめ", progressPercent = 100)!!.block)
        assertEquals(1, model.readFrontier(sectionTitle = "まとめ", progressPercent = 20)!!.block)
    }

    /** 100分の1ブロック単位で割り戻す。ブロック内の割合を捨てると、長い1ブロックの途中と末尾が区別できない。 */
    @Test
    fun `ブロック内で読んだ割合も割り戻す`() {
        assertEquals(ReadFrontier(8, 0.5f), plain.readFrontier(sectionTitle = null, progressPercent = 85))
        assertEquals(ReadFrontier(8, 1f), plain.readFrontier(sectionTitle = null, progressPercent = 90))
        assertEquals(ReadFrontier(0, 0.1f), longParagraph.readFrontier(sectionTitle = null, progressPercent = 10))
    }

    @Test
    fun `節の範囲へ収めたときは、割合も節の端に合わせる`() {
        val model = headedModel()
        // 本論は第2〜第11ブロック。
        assertEquals(ReadFrontier(11, 1f), model.readFrontier(sectionTitle = "本論", progressPercent = 100))
        assertEquals(ReadFrontier(2, 0f), model.readFrontier(sectionTitle = "本論", progressPercent = 1))
    }

    // ── 前後の切り出し ───────────────────────────────────────────────────

    /**
     * **長い1ブロックの途中まで読んだとき、未読の末尾を読んだ側へ入れない**（実装レビューで固定した受理条件）。
     * 丸ごと読んだ側へ入れると、10%しか読んでいないのに終盤の文を「ここまで読んだ」と渡していた。
     */
    @Test
    fun `長い1段落は読んだ割合で前後へ割る`() {
        val early = longParagraph.passageAround(longParagraph.readFrontier(null, 10)!!, 1_200)
        val late = longParagraph.passageAround(longParagraph.readFrontier(null, 95)!!, 1_200)

        val earlyEnd = LONG_TEXT.indexOf(early.before) + early.before.length
        assertTrue("10%の読んだ側が空", early.before.isNotEmpty())
        assertTrue("10%の読んだ側が段落の先頭側に無い（終わり: $earlyEnd）", earlyEnd in 1..LONG_TEXT.length / 10)
        assertFalse("10%の読んだ側に未読の中盤がある", early.before.contains("中盤の文。"))
        assertFalse("10%の読んだ側に未読の終盤がある", early.before.contains("終盤の文。"))
        assertTrue("95%の後ろ側に終盤が無い", late.after.contains("終盤の文。"))
        assertFalse("95%の読んだ側に未読の終盤がある", late.before.contains("終盤の文。"))
        assertFalse("10%と95%で同じ入力になっている", early == late)
        assertEquals(
            "同じ10%で入力が変わる",
            early,
            longParagraph.passageAround(longParagraph.readFrontier(null, 10)!!, 1_200)
        )
    }

    /** 割る位置は文の区切りまで手前へ寄せる。読んでいない文の書き出しを読んだ側へ入れない。 */
    @Test
    fun `割る位置は文の区切りまで手前へ寄せる`() {
        val passage = longParagraph.passageAround(ReadFrontier(0, 0.1f), 1_200)

        assertTrue(passage.before.endsWith("。"))
    }

    @Test
    fun `長いコードとリストも、途中までなら全部を読んだ側へ入れない`() {
        listOf(
            "```\n" + (1..200).joinToString("\n") { "コード行%03d".format(it) } + "\n```",
            (1..200).joinToString("\n") { "- 項目%03d".format(it) }
        ).forEach { body ->
            val model = buildNoteSectionModel(body)
            val passage = model.passageAround(model.readFrontier(null, 10)!!, 1_200)
            val last = if (body.startsWith("```")) "コード行200" else "項目200"

            assertFalse("[$last] 途中までなのに末尾を読んだ側へ入れた", passage.before.contains(last))
            assertTrue("[$last] 後ろ側が空", passage.after.isNotEmpty())
        }
    }

    /** 画像・見出しは割れない。全部見えたときだけ読んだ側へ入れる。 */
    @Test
    fun `割れないブロックは途中なら読んでいない側へ入れる`() {
        val model = buildNoteSectionModel("前の段落。\n\n![](a.png)\n\n後の段落。")
        val passage = model.passageAround(ReadFrontier(1, 0.5f), 1_200)

        assertTrue(passage.before.endsWith("前の段落。"))
        assertTrue(passage.after.startsWith("![](a.png)"))
    }

    @Test
    fun `境目を持ったまま前後を切り出す`() {
        val passage = plain.passageAround(ReadFrontier(4, 1f), targetLength = 1_200)

        assertTrue(passage.before.endsWith("段落4の本文。"))
        assertTrue(passage.after.startsWith("段落5の本文。"))
        assertTrue("読み進めたブロックを後ろ側へ入れない", !passage.after.contains("段落4"))
    }

    @Test
    fun `前後を合わせて目標の長さに収める`() {
        val long = buildNoteSectionModel((0 until 200).joinToString("\n\n") { "段落${it}の本文。".repeat(5) })
        val passage = long.passageAround(ReadFrontier(100, 1f), targetLength = 1_200)

        assertTrue(passage.before.length + passage.after.length <= 1_200)
        assertTrue("前が半分より大きく削られている", passage.before.length >= 550)
        assertTrue("後ろが半分より大きく削られている", passage.after.length >= 550)
    }

    @Test
    fun `先頭で読み止めたときは余りを後ろへ回す`() {
        val long = buildNoteSectionModel((0 until 200).joinToString("\n\n") { "段落${it}の本文。".repeat(5) })
        val passage = long.passageAround(ReadFrontier(0, 1f), targetLength = 1_200)

        assertTrue(passage.before.length < 100)
        assertTrue(passage.before.length + passage.after.length > 1_100)
    }

    @Test
    fun `末尾のブロックでは後ろが空になり、余りを前へ回す`() {
        val long = buildNoteSectionModel((0 until 200).joinToString("\n\n") { "段落${it}の本文。".repeat(5) })
        val passage = long.passageAround(ReadFrontier(199, 1f), targetLength = 1_200)

        assertEquals("", passage.after)
        assertTrue(passage.before.length > 1_100)
    }

    /** 1ブロックが長くても、削るのは境目から遠い側。境目の近くが残る。 */
    @Test
    fun `長いブロックは境目から遠い側を削る`() {
        val model = buildNoteSectionModel("冒頭。" + "あ".repeat(5_000) + "境目の直前。\n\n直後の段落。" + "い".repeat(5_000))
        val passage = model.passageAround(ReadFrontier(0, 1f), targetLength = 1_200)

        assertTrue(passage.before.endsWith("境目の直前。"))
        assertTrue(passage.after.startsWith("直後の段落。"))
        assertTrue(passage.before.length + passage.after.length <= 1_200)
    }

    // ── 続きから読むの送り先 ─────────────────────────────────────────────

    @Test
    fun `節の見出しが近ければ見出しへ送る`() {
        val model = headedModel()
        // ブロック: 0導入 1導入1 2本論 3本論1 4本論2 ...
        assertEquals(2, model.resumeBlockFor(frontierBlock = 4))
    }

    @Test
    fun `節の見出しが遠ければ1ブロック手前へ送る`() {
        val model = headedModel()
        // 見出し（2）から本論9（11）までは離れすぎている。
        assertEquals(10, model.resumeBlockFor(frontierBlock = 11))
    }

    @Test
    fun `見出しが無ければ1ブロック手前へ送り、先頭より前へは送らない`() {
        assertEquals(6, plain.resumeBlockFor(frontierBlock = 7))
        assertEquals(0, plain.resumeBlockFor(frontierBlock = 0))
    }

    /** 前半・中盤・終盤に固有の文を置いた長い1段落（1ブロック）。 */
    private val longParagraph = buildNoteSectionModel(LONG_TEXT)

    private fun headedModel(): NoteSectionModel = buildNoteSectionModel(
        "# 導入\n導入1\n\n# 本論\n" + (1..9).joinToString("\n\n") { "本論$it" } + "\n\n# まとめ\nまとめ1"
    )
}
