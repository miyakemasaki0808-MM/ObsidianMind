package com.example.newproject.domain

import com.example.newproject.domain.image.NoteImageLimits
import com.example.newproject.domain.image.decodedPixels
import com.example.newproject.domain.image.isDecodableImageFileName
import com.example.newproject.domain.image.rejectionForBounds
import com.example.newproject.domain.image.sampleSizeFor
import com.example.newproject.model.NoteImageFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageDecodePolicyTest {

    // --- 復号可否 -----------------------------------------------------------

    @Test
    fun `一般的なラスタ形式は復号を試す`() {
        listOf("a.png", "a.JPG", "a.jpeg", "a.webp", "a.gif", "a.bmp").forEach {
            assertTrue(it, isDecodableImageFileName(it))
        }
    }

    @Test
    fun `SVGは復号対象にしない`() {
        // 認識はする（画像として扱う）が復号できない。「見つかりません」ではなく
        // 「形式が非対応」と言うために、認識の一覧より狭くしてある。
        assertFalse(isDecodableImageFileName("zu.svg"))
    }

    @Test
    fun `端末次第で失敗する形式は静的に落とさない`() {
        // 復号できる端末では出せるので、可否の判定は実際に試した結果に任せる。
        listOf("a.heic", "a.heif", "a.avif").forEach { assertTrue(it, isDecodableImageFileName(it)) }
    }

    // --- 寸法による拒否 -----------------------------------------------------

    @Test
    fun `通常の寸法は弾かない`() {
        assertNull(rejectionForBounds(1920, 1080))
    }

    @Test
    fun `寸法が取れないものは壊れている扱い`() {
        // BitmapFactory は形式を理解できないと -1 を返す。
        assertEquals(NoteImageFailure.Broken, rejectionForBounds(-1, -1))
        assertEquals(NoteImageFailure.Broken, rejectionForBounds(0, 100))
    }

    @Test
    fun `上限を超える寸法は復号する前に弾く`() {
        val over = NoteImageLimits.MAX_DIMENSION + 1
        assertEquals(NoteImageFailure.TooLarge, rejectionForBounds(over, 100))
        assertEquals(NoteImageFailure.TooLarge, rejectionForBounds(100, over))
    }

    // --- 間引き倍率 ---------------------------------------------------------

    @Test
    fun `表示幅より小さい画像は間引かない`() {
        assertEquals(1, sampleSizeFor(400, 300, targetWidth = 1080))
    }

    @Test
    fun `表示幅まで2の冪で落とす`() {
        // 4倍にすると 1000px で表示幅 1080 を下回る（＝眠い絵になる）ので 2倍で止める。
        assertEquals(2, sampleSizeFor(4000, 3000, targetWidth = 1080))
    }

    @Test
    fun `間引いた幅が表示幅を下回らない`() {
        // 画面より粗くすると眠い絵になる。倍率は「まだ表示幅以上」の間だけ上げる。
        val sample = sampleSizeFor(4000, 3000, targetWidth = 1080)
        assertTrue(4000 / sample >= 1080)
    }

    @Test
    fun `極端な縦横比はピクセル数の上限でさらに間引く`() {
        // 幅は表示幅基準では間引かれないのに、復号すると数億ピクセルになる形。
        val sample = sampleSizeFor(1000, 200_000, targetWidth = 1080)
        assertTrue("表示幅基準だけなら1のはず", sample > 1)
        assertTrue(
            decodedPixels(1000, 200_000, sample) <= NoteImageLimits.MAX_DECODED_PIXELS
        )
    }

    @Test
    fun `異常な寸法でも倍率の上限で止まる`() {
        val sample = sampleSizeFor(Int.MAX_VALUE, Int.MAX_VALUE, targetWidth = 1)
        assertTrue(sample <= NoteImageLimits.MAX_SAMPLE_SIZE)
    }

    @Test
    fun `寸法が不明なら間引かない`() {
        assertEquals(1, sampleSizeFor(0, 0, targetWidth = 1080))
        assertEquals(1, sampleSizeFor(100, 100, targetWidth = 0))
    }

    @Test
    fun `ピクセル数はIntで溢れない`() {
        // (46341)^2 は Int を超える。Long で数えていないと負になる。
        assertTrue(decodedPixels(50_000, 50_000, 1) > 0)
        assertEquals(2_500_000_000L, decodedPixels(50_000, 50_000, 1))
    }
}
