package com.example.newproject

import com.example.newproject.domain.markdown.NoteSectionModel
import com.example.newproject.domain.markdown.buildNoteSectionModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// 再会カードの「読み進めたところ」と、その前後の切り出し・続きから読むの送り先
// （→ features/reunion_card.md 判断6）。送り先は実機でしか走らないテストに任せない（→ lessons L53）。
class ReunionPassageTest {

    /** 見出しの無い10ブロック。番号がそのままブロック番号になる。 */
    private val plain = buildNoteSectionModel((0 until 10).joinToString("\n\n") { "段落${it}の本文。" })

    // ── 読み進めたところ ─────────────────────────────────────────────────

    @Test
    fun `ブロックが無ければ読み進めたところは無い`() {
        assertNull(buildNoteSectionModel("").readFrontierBlock(sectionTitle = null, progressPercent = 40))
    }

    /**
     * 到達率は「最深ブロックの番号＋そのブロックの見えている割合」を切り捨てた百分率。
     * 第8ブロックが全部見えた回は 90% になる。素直に割り戻すと未表示の第9ブロックを指す。
     */
    @Test
    fun `最深ブロックが全部見えた回は、そのブロックを指す`() {
        assertEquals(8, plain.readFrontierBlock(sectionTitle = null, progressPercent = 90))
    }

    @Test
    fun `到達率の見積もりは手前へ寄せる`() {
        // 第8ブロックの途中（8.5）なら 85%。
        assertEquals(8, plain.readFrontierBlock(sectionTitle = null, progressPercent = 85))
        assertEquals(0, plain.readFrontierBlock(sectionTitle = null, progressPercent = 0))
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
        assertEquals(6, model.readFrontierBlock(sectionTitle = "本論", progressPercent = 90))
        assertEquals(3, model.readFrontierBlock(sectionTitle = "本論", progressPercent = 10))
    }

    @Test
    fun `節が見つからなければ到達率だけで決める`() {
        val model = headedModel()
        assertEquals(
            model.readFrontierBlock(sectionTitle = null, progressPercent = 50),
            model.readFrontierBlock(sectionTitle = "消えた節", progressPercent = 50)
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
        assertEquals(6, model.readFrontierBlock(sectionTitle = "まとめ", progressPercent = 100))
        assertEquals(1, model.readFrontierBlock(sectionTitle = "まとめ", progressPercent = 20))
    }

    // ── 前後の切り出し ───────────────────────────────────────────────────

    @Test
    fun `境目を持ったまま前後を切り出す`() {
        val passage = plain.passageAround(frontierBlock = 4, targetLength = 1_200)

        assertTrue(passage.before.endsWith("段落4の本文。"))
        assertTrue(passage.after.startsWith("段落5の本文。"))
        assertTrue("読み進めたブロックを後ろ側へ入れない", !passage.after.contains("段落4"))
    }

    @Test
    fun `前後を合わせて目標の長さに収める`() {
        val long = buildNoteSectionModel((0 until 200).joinToString("\n\n") { "段落${it}の本文。".repeat(5) })
        val passage = long.passageAround(frontierBlock = 100, targetLength = 1_200)

        assertTrue(passage.before.length + passage.after.length <= 1_200)
        assertTrue("前が半分より大きく削られている", passage.before.length >= 550)
        assertTrue("後ろが半分より大きく削られている", passage.after.length >= 550)
    }

    @Test
    fun `先頭で読み止めたときは余りを後ろへ回す`() {
        val long = buildNoteSectionModel((0 until 200).joinToString("\n\n") { "段落${it}の本文。".repeat(5) })
        val passage = long.passageAround(frontierBlock = 0, targetLength = 1_200)

        assertTrue(passage.before.length < 100)
        assertTrue(passage.before.length + passage.after.length > 1_100)
    }

    @Test
    fun `末尾のブロックでは後ろが空になり、余りを前へ回す`() {
        val long = buildNoteSectionModel((0 until 200).joinToString("\n\n") { "段落${it}の本文。".repeat(5) })
        val passage = long.passageAround(frontierBlock = 199, targetLength = 1_200)

        assertEquals("", passage.after)
        assertTrue(passage.before.length > 1_100)
    }

    /** 1ブロックが長くても、削るのは境目から遠い側。境目の近くが残る。 */
    @Test
    fun `長いブロックは境目から遠い側を削る`() {
        val model = buildNoteSectionModel("冒頭。" + "あ".repeat(5_000) + "境目の直前。\n\n直後の段落。" + "い".repeat(5_000))
        val passage = model.passageAround(frontierBlock = 0, targetLength = 1_200)

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

    private fun headedModel(): NoteSectionModel = buildNoteSectionModel(
        "# 導入\n導入1\n\n# 本論\n" + (1..9).joinToString("\n\n") { "本論$it" } + "\n\n# まとめ\nまとめ1"
    )
}
