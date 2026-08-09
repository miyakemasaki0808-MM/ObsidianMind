package com.example.newproject.domain

import com.example.newproject.model.REMARK_NONE_TOKEN
import com.example.newproject.model.ReadingTraceLimits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    /**
     * **リンクがあっても根拠検査は免除しない。**
     *
     * 当初は「実在ノートへの参照自体がノート固有」として免除していたが、
     * それだと地の文が丸ごと一般論の「[[X]]と並べると、まったく別の角度から
     * 捉え直せるかもしれません」が通る。**今回いちばん潰したかった形そのもの**だった。
     */
    @Test
    fun `リンクがあっても地の文が一般論なら拒否される`() {
        val result = compose("[[C01]]と並べると、まったく別の角度から捉え直せるかもしれません。")

        assertEquals(RemarkRejection.NotGrounded, (result as RemarkResult.Rejected).reason)
    }

    // 根拠は [[...]] を除いた地の文で測る。リンク先のタイトルがたまたま原文に
    // 出てくると、それだけで検査を通ってしまう（wikilinkを持つノートでは普通に起きる）。
    @Test
    fun `リンク先の名前は根拠に数えない`() {
        // 「読書」は原文に出てくるが、地の文ではなくリンク内にしかない
        val result = composeRemark(
            response = "[[C03]]はどうでしょうか。",
            groundingSource = source,
            candidateTitlesById = candidates + ("C03" to "読書について")
        )

        assertEquals(RemarkRejection.NotGrounded, (result as RemarkResult.Rejected).reason)
    }

    // 空振りと書式失敗を分ける。次の行動（再試行が効くか）が違う。
    @Test
    fun `NONE だけがモデルの失敗ではない`() {
        assertFalse(RemarkRejection.NothingToSay.isModelFailure)
        listOf(
            RemarkRejection.TooShort,
            RemarkRejection.TooLong,
            RemarkRejection.NotGrounded,
            RemarkRejection.UnknownLink
        ).forEach { assertTrue("$it は再試行が効く", it.isModelFailure) }
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

    // ── 映し返し（返事を受けて返す1文） ────────────────────────────────

    @Test
    fun `映し返しは受理される`() {
        val result = composeMirroredRemark("「対話」を、同意ではなく反論まで含む応答として捉えている。")

        assertEquals(
            "「対話」を、同意ではなく反論まで含む応答として捉えている。",
            (result as RemarkResult.Accepted).remark
        )
    }

    /**
     * **問いを返してきたら捨てる。** 1往復で閉じるという制約は出力の形で守るので、
     * 疑問形が返ってきたら守られていない。禁じるだけでは確認できない。
     */
    @Test
    fun `映し返しが問いを返したら拒否される`() {
        listOf(
            "あなたにとって「対話」とは何だろうか？",
            "あなたは対話を反論まで含むものと捉えている。ではその先は?"
        ).forEach { response ->
            assertEquals(
                "問いが通っている: $response",
                RemarkRejection.AskedAQuestion,
                (composeMirroredRemark(response) as RemarkResult.Rejected).reason
            )
        }
    }

    // 映し返しは返事に応じる文なので、原文一致は求めない
    // （返事にしか出てこない語を拾うのが正しい応答になり得る）。
    @Test
    fun `映し返しは原文一致を求めない`() {
        val result = composeMirroredRemark("あなたは自分の失敗談を根拠として持ち出している。")

        assertTrue(result is RemarkResult.Accepted)
    }

    @Test
    fun `映し返しのNONEは空振りとして扱われる`() {
        assertEquals(
            RemarkRejection.NothingToSay,
            (composeMirroredRemark(REMARK_NONE_TOKEN) as RemarkResult.Rejected).reason
        )
    }

    // ── 冒頭の二人称 ────────────────────────────────────────────────────
    //
    // 「あなた」は当初プロンプトで指示していたもので、痕跡の俯瞰要約から持ってきていた。
    // あちらは読み手自身の行動を述べる文なので二人称が要るが、
    // ひとことはノートについて話す文なので、名指しすると採点者の口調になる。

    @Test
    fun `ひとことの冒頭のあなたは剥がされる`() {
        val result = compose("あなたは「読書は著者との対話である」をどう捉えているだろう。")

        assertEquals(
            "「読書は著者との対話である」をどう捉えているだろう。",
            (result as RemarkResult.Accepted).remark
        )
    }

    @Test
    fun `映し返しの冒頭のあなたも剥がされる`() {
        val result = composeMirroredRemark("あなたは「対話」を、反論まで含む応答として捉えている。")

        assertEquals(
            "「対話」を、反論まで含む応答として捉えている。",
            (result as RemarkResult.Accepted).remark
        )
    }

    // 文中の「あなた」は触らない。剥がすのは冒頭の主語だけ。
    @Test
    fun `文中のあなたは残す`() {
        val result = composeMirroredRemark("「対話」は、あなた自身の問いから始まると捉えている。")

        assertEquals(
            "「対話」は、あなた自身の問いから始まると捉えている。",
            (result as RemarkResult.Accepted).remark
        )
    }

    /**
     * **剥がして壊れるなら剥がさない。** 文体の好みで壊れた日本語を出すくらいなら、
     * 二人称が残っているほうがまし。
     */
    @Test
    fun `剥がすと読点始まりになる場合は元のまま`() {
        val result = composeMirroredRemark("あなた、「対話」を反論まで含むものとして捉えている。")

        assertEquals(
            "あなた、「対話」を反論まで含むものとして捉えている。",
            (result as RemarkResult.Accepted).remark
        )
    }

    // ── AIへ渡す返事の抜粋 ──────────────────────────────────────────────
    //
    // **保存とAI入力の予算は別。** 以前は同じ400文字で縛っており、
    // ローカルLLMへ渡せる長さがそのままユーザーの文章の上限になっていた。

    @Test
    fun `短い返事はそのまま渡る`() {
        val reply = "実際に困った場面があった。"

        assertEquals(reply, excerptReplyForPrompt(reply))
    }

    @Test
    fun `長い返事は先頭と末尾を残して中略される`() {
        val head = "書き出しの主張。"
        val tail = "最後にたどり着いた結論。"
        val reply = head + "あ".repeat(RemarkLimits.REPLY_EXCERPT_CHARS * 2) + tail

        val excerpt = excerptReplyForPrompt(reply)

        assertTrue("先頭が残っていない", excerpt.startsWith(head))
        assertTrue("末尾が残っていない", excerpt.endsWith(tail))
        assertTrue("中略の印が無い", excerpt.contains("中略"))
        assertTrue(
            "予算を超えている: ${excerpt.length}",
            excerpt.length <= RemarkLimits.REPLY_EXCERPT_CHARS
        )
    }

    /**
     * 保存できる長さがAI入力の予算より**十分大きい**こと。
     * ここが同じ値だった頃、長文の返事は貼った時点で捨てられていた。
     */
    @Test
    fun `保存の上限はAI入力の予算より大きい`() {
        assertTrue(RemarkLimits.MAX_REPLY_CHARS > RemarkLimits.REPLY_EXCERPT_CHARS * 10)
        // 静かに知らせる目安は、上限より内側にあること
        assertTrue(RemarkLimits.SOFT_REPLY_CHARS < RemarkLimits.MAX_REPLY_CHARS)
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

    /** 返事も同じ。画面で受け付けた長さが、保存で弾かれてはいけない。 */
    @Test
    fun `返事の文字数上限は保存のバイト上限の内側にある`() {
        val worstCase = "あ".repeat(RemarkLimits.MAX_REPLY_CHARS).toByteArray(Charsets.UTF_8).size

        assertTrue(
            "返事の文字数上限($worstCase バイト)が保存上限(${ReadingTraceLimits.MAX_REPLY_BYTES})を超えている",
            worstCase <= ReadingTraceLimits.MAX_REPLY_BYTES
        )
    }
}
