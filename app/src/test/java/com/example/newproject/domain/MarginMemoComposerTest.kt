package com.example.newproject.domain

import com.example.newproject.model.ReadingTraceLimits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 余白メモの入力整形。
 *
 * **上限は壁だが、超えた入力を無かったことにはしない。**
 * 受け取ってから切り、切ったら必ず示す（→ features/reflect_margin_memo.md §5）。
 */
class MarginMemoComposerTest {

    @Test
    fun `前後の空白は落とす`() {
        assertEquals("いま思ったこと", composeMarginMemo("  いま思ったこと \n ").text)
    }

    @Test
    fun `空白だけの入力は置けない`() {
        assertTrue(composeMarginMemo("   \n\t ").isBlank)
    }

    /**
     * **改行とタブは残す。** 短い断片でも段を作ることはあるので、
     * 書いたとおりに残らないと「勝手に直された」になる。
     */
    @Test
    fun `改行とタブは残る`() {
        val draft = composeMarginMemo("一行目\n\t二行目")

        assertEquals("一行目\n\t二行目", draft.text)
        assertFalse(draft.wasTruncated)
    }

    /**
     * **それ以外の制御文字は落とす。** JSONが `\u00XX` の6バイトへ広げるので、
     * 落とさないと各欄の上限を満たしたままファイル全体の上限を超えられる
     * （→ 判断11。この正規化が「最悪2倍」という前提の根拠である）。
     */
    @Test
    fun `改行とタブ以外の制御文字は落とす`() {
        val draft = composeMarginMemo("前中後")

        assertEquals("前中後", draft.text)
    }

    @Test
    fun `上限を超えたら切り、切ったことを持ち帰る`() {
        val long = "あ".repeat(ReadingTraceLimits.MAX_MEMO_BYTES)

        val draft = composeMarginMemo(long)

        assertTrue("切ったのに知らせない", draft.wasTruncated)
        assertTrue(
            "上限を超えたまま返した",
            draft.text.toByteArray(Charsets.UTF_8).size <= ReadingTraceLimits.MAX_MEMO_BYTES
        )
    }

    /** マルチバイト文字の途中では切らない（切ると文字化けする）。 */
    @Test
    fun `切っても文字の途中では割らない`() {
        val draft = composeMarginMemo("あ".repeat(ReadingTraceLimits.MAX_MEMO_BYTES))

        assertTrue(draft.text.all { it == 'あ' })
    }

    /** **合図であって壁ではない。** 超えても切らないし、置けなくもならない。 */
    @Test
    fun `目安を超えても切らない`() {
        val draft = composeMarginMemo("あ".repeat(SOFT_MEMO_CHARS + 1))

        assertTrue("目安で合図が出ない", isMemoOverSoftLimit("あ".repeat(SOFT_MEMO_CHARS + 1)))
        assertFalse("目安で切ってしまった", draft.wasTruncated)
    }

    @Test
    fun `目安の内側では合図を出さない`() {
        assertFalse(isMemoOverSoftLimit("あ".repeat(SOFT_MEMO_CHARS)))
    }
}
