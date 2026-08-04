package com.example.newproject.ui

import com.example.newproject.domain.markdown.MarkdownBlock
import com.example.newproject.model.NoteImageFailure
import com.example.newproject.ui.markdown.NOTE_IMAGE_MIN_HEIGHT_DP
import com.example.newproject.ui.markdown.NOTE_IMAGE_PENDING_HEIGHT_DP
import com.example.newproject.ui.markdown.noteImageContentDescription
import com.example.newproject.ui.markdown.noteImageDisplayName
import com.example.newproject.ui.markdown.noteImageFailureText
import com.example.newproject.ui.markdown.reservedImageHeightDp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteImageTextTest {

    // --- 高さの確保 ---------------------------------------------------------
    //
    // ここが本機能で唯一「見た目ではなく永続データ」に効く。高さ0のブロックは
    // visibleFractionOfBlock が全可視として扱うため、読書痕跡の到達率が水増しされ、
    // 最深到達点として固着したままサイドカーへ書かれる。

    @Test
    fun `寸法が分かれば縦横比どおりの高さを確保する`() {
        assertEquals(200, reservedImageHeightDp(widthDp = 400, sourceWidth = 800, sourceHeight = 400))
    }

    @Test
    fun `寸法が分からなくても高さを0にしない`() {
        assertEquals(
            NOTE_IMAGE_PENDING_HEIGHT_DP,
            reservedImageHeightDp(widthDp = 400, sourceWidth = 0, sourceHeight = 0)
        )
    }

    @Test
    fun `寸法が分かるまでは渡された画面の高さを確保する`() {
        // **誤るなら大きい側へ誤る。** 小さすぎる確保は後続ブロックを一瞬だけ
        // 画面へ入れ、最深到達点は下がらないので誤った到達率が固着する。
        assertEquals(
            900,
            reservedImageHeightDp(widthDp = 400, sourceWidth = 0, sourceHeight = 0, pendingHeightDp = 900)
        )
    }

    @Test
    fun `画面の高さが取れなくても最低高さは割らない`() {
        assertEquals(
            NOTE_IMAGE_MIN_HEIGHT_DP,
            reservedImageHeightDp(widthDp = 400, sourceWidth = 0, sourceHeight = 0, pendingHeightDp = 0)
        )
    }

    @Test
    fun `寸法が負でも高さを0にしない`() {
        // BitmapFactory は形式を理解できないと -1 を返す。
        assertTrue(reservedImageHeightDp(widthDp = 400, sourceWidth = -1, sourceHeight = -1) > 0)
    }

    @Test
    fun `極端に横長でも最低の高さを割らない`() {
        // 縦横比どおりだと 1dp 未満になる形。0へ落ちると到達率が壊れる。
        val height = reservedImageHeightDp(widthDp = 400, sourceWidth = 100_000, sourceHeight = 1)
        assertEquals(NOTE_IMAGE_MIN_HEIGHT_DP, height)
    }

    @Test
    fun `表示幅が取れていない段階でも高さを0にしない`() {
        assertTrue(reservedImageHeightDp(widthDp = 0, sourceWidth = 800, sourceHeight = 400) > 0)
    }

    @Test
    fun `高さの計算がIntで溢れない`() {
        // widthDp * sourceHeight を Int で掛けると溢れて負になり、下限へ丸められて
        // **実際より極端に低い高さ**が返る（＝到達率の水増しが起きる側へ倒れる）。
        //
        // 呼び出し側は rejectionForBounds を通した寸法しか渡さないので production では
        // この大きさに到達しないが、**この関数自身は入力を制約していない**ため、
        // 契約の水準で固定しておく（decodedPixels と同じ扱い）。
        val height = reservedImageHeightDp(
            widthDp = 1_000_000,
            sourceWidth = 1,
            sourceHeight = 1_000_000
        )
        assertTrue(height.toString(), height > 1_000_000)
    }

    // --- 参照先の表示名 -----------------------------------------------------

    @Test
    fun `埋め込みのサイズヒントは表示名に出さない`() {
        val block = MarkdownBlock.Image("", "zu.png|400", isEmbed = true)
        assertEquals("zu.png", noteImageDisplayName(block))
    }

    @Test
    fun `パス付きの参照はファイル名だけを表示名にする`() {
        val block = MarkdownBlock.Image("", "attachments/%E5%9B%B3.png", isEmbed = false)
        assertEquals("図.png", noteImageDisplayName(block))
    }

    @Test
    fun `外部URLはURLをそのまま表示名にする`() {
        val url = "https://example.com/a.png"
        assertEquals(url, noteImageDisplayName(MarkdownBlock.Image("", url, isEmbed = false)))
    }

    // --- 失敗の文面 ---------------------------------------------------------

    @Test
    fun `不在と確認できないことは別の文面にする`() {
        // 同じ文面にすると、同期の途中を「ファイルを消してしまった」と読み違える。
        val notFound = noteImageFailureText(NoteImageFailure.NotFound, "zu.png")
        val unverifiable = noteImageFailureText(NoteImageFailure.Unverifiable, "zu.png")
        assertNotEquals(notFound, unverifiable)
    }

    @Test
    fun `どの理由でも参照先を必ず添える`() {
        // どの画像の話か分からないと、長いノートでは直しようがない。
        val reasons = listOf(
            NoteImageFailure.NotFound,
            NoteImageFailure.Unverifiable,
            NoteImageFailure.Ambiguous(2),
            NoteImageFailure.Unsupported,
            NoteImageFailure.TooLarge,
            NoteImageFailure.Broken
        )
        reasons.forEach { reason ->
            assertTrue(
                reason.toString(),
                "attachments/zu.png" in noteImageFailureText(reason, "attachments/zu.png")
            )
        }
    }

    @Test
    fun `曖昧なときは候補数を出す`() {
        val text = noteImageFailureText(NoteImageFailure.Ambiguous(3), "zu.png")
        assertTrue(text, "3" in text)
    }

    @Test
    fun `外部URLはURLを出す`() {
        val url = "https://example.com/a.png"
        assertTrue(url in noteImageFailureText(NoteImageFailure.External(url), url))
    }

    @Test
    fun `どの理由でも空文字にはしない`() {
        // 文字が唯一の識別手段なので（色だけで伝えない・WCAG 1.4.1）、空を許さない。
        val reasons = listOf(
            NoteImageFailure.NotFound,
            NoteImageFailure.Unverifiable,
            NoteImageFailure.Ambiguous(2),
            NoteImageFailure.External("https://example.com/a.png"),
            NoteImageFailure.Empty,
            NoteImageFailure.Unsupported,
            NoteImageFailure.TooLarge,
            NoteImageFailure.Broken
        )
        reasons.forEach { assertTrue(it.toString(), noteImageFailureText(it, "zu.png").isNotBlank()) }
    }

    @Test
    fun `内部語を文面に出さない`() {
        val words = listOf("索引", "解決", "復号", "デコード", "インデックス")
        val text = noteImageFailureText(NoteImageFailure.Unverifiable, "zu.png")
        words.forEach { assertTrue(it, it !in text) }
    }

    // --- 読み上げ -----------------------------------------------------------

    @Test
    fun `altがあればそれを読む`() {
        assertEquals("図の説明", noteImageContentDescription("図の説明", "zu.png"))
    }

    @Test
    fun `altが空ならファイル名を読む`() {
        // ビューアなので、ノートに置かれた画像は装飾ではなく内容。読み飛ばさない。
        assertEquals("zu.png", noteImageContentDescription("", "zu.png"))
        assertEquals("zu.png", noteImageContentDescription("   ", "zu.png"))
    }
}
