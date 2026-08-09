package com.example.newproject.domain

import com.example.newproject.model.REMARK_NONE_TOKEN
import com.example.newproject.model.ReadingTraceLimits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ひとことの応答検証。
 *
 * 旧補記は出力を一切検証していなかったため、プロンプトが守られたかを誰も
 * 確認できなかった（→ design/reflect_remark.md §0・§6）。ここが唯一の門番になる。
 */
class RemarkComposerTest {

    private val source = """
        読書は著者との対話である。読み手は問いを持ち込むことで、本文に書かれていない
        ことまで考えられるようになる。逆に問いを持たずに読むと、文字を追うだけになる。
    """.trimIndent()

    private val candidates = mapOf(
        "C01" to "問いを立てる技術",
        "C02" to "積読の効用"
    )

    private fun compose(response: String) = composeRemark(response, source, candidates)

    // ── 受理される形 ────────────────────────────────────────────────────

    @Test
    fun `原文の言葉を含む問いは受理される`() {
        val result = compose("「読書は著者との対話である」という考えは、著者の主張に反対するときにも成り立つだろうか？")

        assertEquals(
            "「読書は著者との対話である」という考えは、著者の主張に反対するときにも成り立つだろうか？",
            (result as RemarkResult.Accepted).remark
        )
    }

    @Test
    fun `候補IDは実タイトルへ差し戻される`() {
        val result = compose("[[C01]]とつなげると、「対話」を具体的にどう始めるかまで考えられそうです。")

        assertEquals(
            "[[問いを立てる技術]]とつなげると、「対話」を具体的にどう始めるかまで考えられそうです。",
            (result as RemarkResult.Accepted).remark
        )
    }

    // ID は小文字・桁落ちで返ってくることがある（関連ノートで実績のある揺れ）。
    @Test
    fun `小文字のIDも差し戻せる`() {
        val result = compose("[[c02]]と並べて読むと、問いの持ち込み方が別の角度から見えてきそうです。")

        assertTrue((result as RemarkResult.Accepted).remark.contains("[[積読の効用]]"))
    }

    // 実在ノートへの参照そのものがノート固有なので、原文との一致が無くても根拠として認める。
    @Test
    fun `リンクを含むひとことは原文一致が無くても受理される`() {
        val result = compose("[[C01]]と並べると、まったく別の角度から捉え直せるかもしれません。")

        assertTrue(result is RemarkResult.Accepted)
    }

    @Test
    fun `箇条書きや引用符の飾りは剥がされる`() {
        val result = compose("- 「読書は著者との対話である」とき、対話の相手はいつも著者だろうか？")

        assertTrue((result as RemarkResult.Accepted).remark.startsWith("「読書は"))
    }

    @Test
    fun `複数行の応答は1文へ畳まれる`() {
        val result = compose("「読書は著者との対話である」と書いてあるが、\n沈黙している著者とはどう対話するのだろう？")

        val remark = (result as RemarkResult.Accepted).remark
        assertTrue("改行が残っている: $remark", !remark.contains("\n"))
    }

    // ── 拒否される形 ────────────────────────────────────────────────────

    @Test
    fun `NONE は出すものが無しとして拒否される`() {
        assertEquals(
            RemarkRejection.NothingToSay,
            (compose(REMARK_NONE_TOKEN) as RemarkResult.Rejected).reason
        )
        assertEquals(
            RemarkRejection.NothingToSay,
            (compose("   ") as RemarkResult.Rejected).reason
        )
    }

    // 一般論の禁止を「指示」ではなく「検査」で担保できていること。
    // これが効かないと旧補記と同じ「守られたか誰も確認していない」状態に戻る。
    @Test
    fun `原文の言葉を含まない一般論は拒否される`() {
        val result = compose("この内容について、もっと掘り下げて整理してみると新しい発見があるかもしれませんね。")

        assertEquals(RemarkRejection.NotGrounded, (result as RemarkResult.Rejected).reason)
    }

    // 候補外を黙って落とすと文が宙に浮く（AIピッカーが mapNotNull で件数を減らすのと同じ轍）。
    @Test
    fun `候補外のリンクは丸ごと拒否される`() {
        val result = compose("[[C09]]とつなげると、「読書は著者との対話である」が別の意味を持ちそうです。")

        assertEquals(RemarkRejection.UnknownLink, (result as RemarkResult.Rejected).reason)
    }

    @Test
    fun `生タイトルのリンクは候補外として拒否される`() {
        val result = compose("[[問いを立てる技術]]とつなげると、「対話」の始め方が見えてきそうです。")

        assertEquals(RemarkRejection.UnknownLink, (result as RemarkResult.Rejected).reason)
    }

    @Test
    fun `短すぎる相槌は拒否される`() {
        assertEquals(
            RemarkRejection.TooShort,
            (compose("なるほど。") as RemarkResult.Rejected).reason
        )
    }

    @Test
    fun `長すぎる応答は拒否される`() {
        val tooLong = "読書は著者との対話である。" + "あ".repeat(RemarkLimits.MAX_CHARS)

        assertEquals(
            RemarkRejection.TooLong,
            (compose(tooLong) as RemarkResult.Rejected).reason
        )
    }

    // ── 保存側との整合 ──────────────────────────────────────────────────

    /**
     * 検査を通ったひとことが保存で弾かれると、原因が2箇所に散る。
     * 文字数上限は必ずバイト上限の内側に収まっていること。
     */
    @Test
    fun `文字数上限は保存のバイト上限の内側にある`() {
        val worstCase = "あ".repeat(RemarkLimits.MAX_CHARS).toByteArray(Charsets.UTF_8).size

        assertTrue(
            "文字数上限($worstCase バイト)が保存上限(${ReadingTraceLimits.MAX_REMARK_BYTES})を超えている",
            worstCase <= ReadingTraceLimits.MAX_REMARK_BYTES
        )
    }
}
