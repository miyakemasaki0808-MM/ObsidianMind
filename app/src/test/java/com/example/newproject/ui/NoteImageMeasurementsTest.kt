package com.example.newproject.ui

import com.example.newproject.domain.markdown.MarkdownBlock
import com.example.newproject.ui.markdown.NoteImageBlockRef
import com.example.newproject.ui.markdown.firstUnmeasuredImageIndex
import com.example.newproject.ui.markdown.imageBlockRefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 「まだ寸法が取れていない画像」の判定を固定する。
 *
 * ## なぜ本文の構造から判定するのか
 *
 * 最初の実装は「いま描かれている画像」から判定していた。
 * **画面外へスクロールした画像は Composable ごと破棄され、測定も途中でキャンセルされる。**
 * 破棄を「確定した」と読んでしまい、**測っていないのに未確定でなくなって**
 * その先の報告が通った（実機で index 2 が未測定のまま index 29 が報告された）。
 *
 * 判定材料を**本文の構造と測定キャッシュだけ**にすれば、コンポジションの寿命から独立する。
 * ここで固定しているのはその性質である。
 */
class NoteImageMeasurementsTest {

    @Test
    fun `本文から画像ブロックの位置と参照先を拾う`() {
        val blocks = listOf(
            paragraph("段落0"),
            image("a.png"),
            paragraph("段落2"),
            image("b.png")
        )

        assertEquals(
            listOf(NoteImageBlockRef(1, "a.png"), NoteImageBlockRef(3, "b.png")),
            imageBlockRefs(blocks)
        )
    }

    @Test
    fun `画像が無ければ未測定も無い`() {
        assertNull(firstUnmeasuredImageIndex(imageBlockRefs(listOf(paragraph("段落0"))), emptySet()))
    }

    @Test
    fun `全部測れていれば未測定は無い`() {
        val refs = imageBlockRefs(listOf(image("a.png"), image("b.png")))

        assertNull(firstUnmeasuredImageIndex(refs, setOf("a.png", "b.png")))
    }

    @Test
    fun `未測定のうち最も手前を返す`() {
        val refs = imageBlockRefs(listOf(image("a.png"), paragraph("段落1"), image("b.png")))

        assertEquals(2, firstUnmeasuredImageIndex(refs, setOf("a.png")))
        assertEquals(0, firstUnmeasuredImageIndex(refs, setOf("b.png")))
    }

    /**
     * **ここが作り直しの理由。**
     *
     * 画面外へ出ても判定は変わらない — 材料に「描かれているか」が入っていないため。
     * 測定キャッシュへ記録されるまで、その画像は未測定のままである。
     */
    @Test
    fun `描かれているかは判定に影響しない`() {
        val refs = imageBlockRefs(listOf(paragraph("段落0"), paragraph("段落1"), image("a.png")))

        // 画像が画面外にあろうと、記録が無い限り未測定。
        assertEquals(2, firstUnmeasuredImageIndex(refs, emptySet()))
        // 記録された時点で初めて解ける。
        assertNull(firstUnmeasuredImageIndex(refs, setOf("a.png")))
    }

    /**
     * **未測定が複数あれば最も手前で止める。**
     *
     * 手前が未測定なら、その先の可視判定はどのみち信用できない。
     * 末尾を返すと、手前の画像を跨いだ報告が通ってしまう。
     */
    @Test
    fun `未測定が複数あっても最も手前で止まる`() {
        val refs = imageBlockRefs(listOf(image("a.png"), paragraph("段落1"), image("b.png")))

        assertEquals(0, firstUnmeasuredImageIndex(refs, emptySet()))
    }

    /** 同じ画像を2箇所から参照していれば、1回測れば両方とも解ける。 */
    @Test
    fun `同じ参照を共有する画像は1回の測定で解ける`() {
        val refs = imageBlockRefs(listOf(image("a.png"), paragraph("段落1"), image("a.png")))

        assertNull(firstUnmeasuredImageIndex(refs, setOf("a.png")))
    }

    private fun paragraph(text: String) = MarkdownBlock.Paragraph(text)

    private fun image(target: String) = MarkdownBlock.Image(alt = "", target = target, isEmbed = false)
}
