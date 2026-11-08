package com.example.newproject.domain

import com.example.newproject.model.ReunionKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 再会カードの候補列挙。**規則だけで列挙し、選別はAIへ渡す**段の回帰テスト。
 *
 * ここに並ぶ規則は机上で決めたものではなく、**`docs/` を代理コーパスに実測して直した**結果である
 * （→ features/reunion_card.md §5）。素朴な仕様のままだと、疑問文が2%しか出ず、
 * 古びうる記述が73%出て中身は日付ラベルだった。
 */
class ReunionCandidateScannerTest {

    // --- 疑問文 ---------------------------------------------------------------

    /** **日本語の疑問文は「？」を伴わないことが多い。** これが無いと候補が実測で2%まで落ちた。 */
    @Test
    fun `疑問符が無くても「か。」で終わる文を問いとして拾う`() {
        val candidates = scanReunionCandidates(
            "この設計で本当に必要なものを満たせるだろうか。\n次の段落はただの説明である。"
        )
        assertEquals(listOf("この設計で本当に必要なものを満たせるだろうか。"), candidates.questions)
    }

    /**
     * 終助詞「か」で終わる形をひととおり通す。
     * **`だろうか` や `のか` を別扱いにしない** — 末尾は「か。」なので同じ枝で足りる。
     */
    @Test
    fun `終助詞かで終わる問いを形の違いによらず拾う`() {
        listOf(
            "これでよいのだろうか。",
            "本当にそうなのか。",
            "この案を採用すべきか。",
            "先に測るほうがよいかな。"
        ).forEach { line ->
            assertEquals(line, 1, scanReunionCandidates(line).questions.size)
        }
    }

    /** 「か」で終わっても問いでない文を拾わない。過去形の「〜かった。」が最も紛らわしい。 */
    @Test
    fun `かで終わらない断定文は問いにしない`() {
        listOf(
            "この方式のほうが速かった。",
            "そうするしかなかった。",
            "これは十分な説明である。"
        ).forEach { line ->
            assertTrue(line, scanReunionCandidates(line).questions.isEmpty())
        }
    }

    @Test
    fun `疑問符で終わる文も拾う`() {
        val candidates = scanReunionCandidates("この方式は本当に速いのだろうか？")
        assertEquals(1, candidates.questions.size)
    }

    /** タスクやコールアウトの印は、問いの文面に残さない（扉と同じ前処理を通る）。 */
    @Test
    fun `タスクとコールアウトの印は問いの文面に残らない`() {
        assertEquals(listOf("この方式は本当に速いのだろうか？"), scanReunionCandidates("- [ ] この方式は本当に速いのだろうか？").questions)
        assertEquals(listOf("この前提はまだ正しいのか？"), scanReunionCandidates("> [!question] この前提はまだ正しいのか？").questions)
    }

    // --- 引用（→ features/reunion_card.md §5「引用した問いは書き手の問いにしない」） ---------------

    /**
     * **引用した他人の問いを、書き手の問いにしない。** カードは「前回のあなたはこの問いで止まっていました」と断定するので、
     * 引用から拾うと事実に反する。記号を落とした後では地の文と区別が付かないので、落とす前に見分ける。
     */
    @Test
    fun `素の引用の問いは拾わず、地の文の問いは拾う`() {
        val candidates = scanReunionCandidates("> これでいいのか。と彼は言った。\n> 本当にそうなのか。\n\n自分はどうしたいのか。")

        assertEquals(listOf("自分はどうしたいのか。"), candidates.questions)
    }

    /** 引用と出典のコールアウト（`[!quote]`・`[!cite]`）は引用。大文字小文字と開閉の印によらない。 */
    @Test
    fun `引用と出典のコールアウトの問いは、題の行も続きの行も拾わない`() {
        assertTrue(scanReunionCandidates("> [!quote] 本当にそうなのか。\n> 何のために読むのか。").questions.isEmpty())
        assertTrue(scanReunionCandidates("> [!CITE]- 出典\n> 何のために読むのか。").questions.isEmpty())
    }

    /** ほかのコールアウト（メモ・問いなど）は書き手が書いたものなので、問いとして拾う。 */
    @Test
    fun `引用でないコールアウトの問いは拾う`() {
        assertEquals(listOf("次は何を試すべきか。"), scanReunionCandidates("> [!note] メモ\n> 次は何を試すべきか。").questions)
        assertEquals(listOf("なぜ続かないのか。"), scanReunionCandidates("> [!question]+ 自分への問い\n> なぜ続かないのか。").questions)
    }

    /** コールアウトの中に入れ子にした引用は、外のコールアウトが何であっても引用。抜ければ書き手の文に戻る。 */
    @Test
    fun `書き手のコールアウトの中の入れ子の引用は拾わない`() {
        val candidates = scanReunionCandidates("> [!note] メモ\n> > 他人の問いなのか。\n> 自分の問いなのか。")

        assertEquals(listOf("自分の問いなのか。"), candidates.questions)
        // 1行で2段を開くと、外の段は中身が入れ子の引用なので素の引用。中のコールアウトの種類は外の段に移さない。
        assertTrue(scanReunionCandidates("> > [!note] 入れ子のメモなのか。").questions.isEmpty())
    }

    /**
     * `>` の無い続きの行も、段落が続く限り引用の中（Obsidian はそこまでを引用として描く）。
     * 空行・空の `>` の行・見出しや箇条の行で引用は終わる。
     */
    @Test
    fun `記号の無い続きの行は段落が続く限り引用で、空行や空の記号の行や箇条で終わる`() {
        assertTrue(scanReunionCandidates("> 引用の一文目です。\nこれも引用の続きなのか。").questions.isEmpty())
        assertEquals(listOf("これは地の文なのか。"), scanReunionCandidates("> 引用の一文目です。\n\nこれは地の文なのか。").questions)
        assertEquals(listOf("これは地の文なのか。"), scanReunionCandidates("> 引用の一文目です。\n>\nこれは地の文なのか。").questions)
        assertEquals(listOf("箇条の問いなのか。"), scanReunionCandidates("> 引用の一文目です。\n- 箇条の問いなのか。").questions)
        assertEquals(listOf("見出しの問いなのか。"), scanReunionCandidates("> 引用の一文目です。\n## 見出しの問いなのか。").questions)
    }

    /** 記号の無い続きの行を許すのは段落だけ。見出しだけの引用の後ろの地の文は、引用の外の書き手の文。 */
    @Test
    fun `見出しだけの引用の直後の地の文は引用の続きにしない`() {
        assertEquals(listOf("これも引用の続きなのか。"), scanReunionCandidates("> # 引用の見出し\nこれも引用の続きなのか。").questions)
        assertTrue(scanReunionCandidates("> 引用の一文です。\nこれも引用の続きなのか。").questions.isEmpty())
    }

    /** コードブロックを挟めば引用は終わる（コードは落とすが、段落の境目は残す）。 */
    @Test
    fun `コードブロックの後ろの地の文は引用の続きにしない`() {
        assertEquals(listOf("これは地の文なのか。"), scanReunionCandidates("> 引用の一文目です。\n```\ncode\n```\nこれは地の文なのか。").questions)
    }

    /** 箇条の中に置いた引用も引用。 */
    @Test
    fun `箇条の中の引用の問いは拾わない`() {
        assertTrue(scanReunionCandidates("- > リストの中の引用なのか。").questions.isEmpty())
    }

    /** 古い前提のカードは誰の言葉かを言わない（「今も有効か確認したい箇所があります」）ので、引用からも拾う。 */
    @Test
    fun `引用の中の古びうる記述は拾う`() {
        val candidates = scanReunionCandidates("> 現在の最新は v2.1.0 である。")

        assertEquals(listOf("現在の最新は v2.1.0 である。"), candidates.stalenessMarks)
    }

    /**
     * **引用の途中で切らない。** 素朴に終止符で割ると「これでいいのか？ と言われた。」が
     * 閉じ括弧の無い断片になり、カードにそのまま出てしまう。
     */
    @Test
    fun `鉤括弧の内側では文を切らない`() {
        val candidates = scanReunionCandidates("彼は「これでいいのか？」と言った。それが発端である。")
        assertTrue("括弧内で切れている: ${candidates.questions}", candidates.questions.isEmpty())
    }

    // --- 古びうる記述 ---------------------------------------------------------

    /** **裸の西暦は出来事の記録**であって、古びる前提ではない。実測で候補の大半を占めた。 */
    @Test
    fun `日付だけを含む記録は古びうる記述にしない`() {
        val candidates = scanReunionCandidates("2026-08-10 に中央値まで肥大していたのを圧縮した。")
        assertTrue(candidates.stalenessMarks.isEmpty())
    }

    @Test
    fun `西暦でも現状を述べていれば拾う`() {
        val candidates = scanReunionCandidates("2026年の現在はこの方式が主流である。")
        assertEquals(1, candidates.stalenessMarks.size)
    }

    /** **小数は版番号ではない。** `4.5:1` のような計測値が候補を埋めていた。 */
    @Test
    fun `単なる小数を版番号として拾わない`() {
        val candidates = scanReunionCandidates("文字のコントラストは 4.5:1 を満たしている。")
        assertTrue(candidates.stalenessMarks.isEmpty())
    }

    @Test
    fun `版番号とプレリリース語と金額は拾う`() {
        listOf(
            "いまは v2.1 を使っている。",
            "現行は 1.0.0-beta2 である。",
            "月額は $20 で据え置きである。"
        ).forEach { line ->
            assertEquals(line, 1, scanReunionCandidates(line).stalenessMarks.size)
        }
    }

    // --- 記法とノイズ ---------------------------------------------------------

    @Test
    fun `コードブロックの中は候補にしない`() {
        val candidates = scanReunionCandidates(
            "```\nval version = \"1.0.0-beta2\" // これでいいのか？\n```\n本文はこれだけである。"
        )
        assertTrue(candidates.questions.isEmpty())
        assertTrue(candidates.stalenessMarks.isEmpty())
    }

    @Test
    fun `frontmatterは候補にしない`() {
        val candidates = scanReunionCandidates("---\nupdated: 2026-08-10 の現在\n---\n本文である。")
        assertTrue(candidates.stalenessMarks.isEmpty())
    }

    /** **終止符で終わらない断片はラベル。** 見出し・表のセル・`最終検証:` 行を候補から外す。 */
    @Test
    fun `終止符を持たないラベル行は候補にしない`() {
        val candidates = scanReunionCandidates("## いまの方式でよいのか\n最終検証: 2026-08-12 の現在")
        assertTrue(candidates.questions.isEmpty())
        assertTrue(candidates.stalenessMarks.isEmpty())
    }

    @Test
    fun `見出しでも文の形なら拾う`() {
        val candidates = scanReunionCandidates("## この方式でよいのだろうか。")
        assertEquals(1, candidates.questions.size)
    }

    /** 下限は「単独で何も思い出せない断片」だけを切る位置に置く（→ MIN_CANDIDATE_CHARS）。 */
    @Test
    fun `短すぎる文と長すぎる文は候補にしない`() {
        assertTrue(scanReunionCandidates("そうか。").questions.isEmpty())
        assertEquals(1, scanReunionCandidates("本当にそうなのか。").questions.size)
        assertTrue(scanReunionCandidates("あ".repeat(200) + "だろうか。").questions.isEmpty())
    }

    // --- 排他と上限 -----------------------------------------------------------

    /** 同じ文が2種別に並ぶと、AIへ渡す候補が重複して見える。疑問文を優先して排他にする。 */
    @Test
    fun `両方に当たる文は疑問文としてだけ数える`() {
        val candidates = scanReunionCandidates("いまも v2.1 のままでよいのだろうか。")
        assertEquals(1, candidates.questions.size)
        assertTrue(candidates.stalenessMarks.isEmpty())
    }

    @Test
    fun `種別ごとに上限件数で打ち切る`() {
        val content = (1..40).joinToString("\n") { "これは第${it}の論点として妥当だろうか。" }
        assertEquals(REUNION_CANDIDATES_PER_KIND, scanReunionCandidates(content).questions.size)
    }

    // --- 種別の決定 -----------------------------------------------------------

    @Test
    fun `種別は問い優先で決まる`() {
        assertEquals(
            ReunionKind.Question,
            decideReunionKind(ReunionCandidates(listOf("問い"), listOf("前提")))
        )
        assertEquals(
            ReunionKind.Staleness,
            decideReunionKind(ReunionCandidates(emptyList(), listOf("前提")))
        )
        assertEquals(ReunionKind.Overview, decideReunionKind(ReunionCandidates.EMPTY))
    }

    @Test
    fun `俯瞰要約には候補を渡さない`() {
        val candidates = ReunionCandidates(listOf("問い"), listOf("前提"))
        assertEquals(listOf("問い"), candidates.forKind(ReunionKind.Question))
        assertEquals(listOf("前提"), candidates.forKind(ReunionKind.Staleness))
        assertTrue(candidates.forKind(ReunionKind.Overview).isEmpty())
    }

    @Test
    fun `本文が空でも落ちない`() {
        assertFalse(scanReunionCandidates("").questions.any())
        assertFalse(scanReunionCandidates("   \n\n").stalenessMarks.any())
    }
}
