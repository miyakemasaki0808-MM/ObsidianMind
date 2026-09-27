package com.example.newproject.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class CrystalResponseParserTest {

    private val valid = setOf("N1", "N2", "N3", "N4", "N5", "N6")

    private fun parse(response: String, ids: Set<String> = valid) = parseCrystalResponse(response, ids)

    @Test
    fun `2行の応答を採る。今のノートを先頭に並べる`() {
        val answer = parse("選択: N3, N1\n結晶: どちらも習慣を小さく始める工夫を扱っている。")
        assertEquals(
            CrystalAnswer.Chosen(listOf("N1", "N3"), "どちらも習慣を小さく始める工夫を扱っている。"),
            answer
        )
    }

    @Test
    fun `NONE の揺れは空振り`() {
        listOf("NONE", " none。", "「NONE」", "None.").forEach { response ->
            assertEquals(response, CrystalAnswer.None, parse(response))
        }
    }

    @Test
    fun `全角のコロン・ID・前後の余計な行を許す`() {
        val answer = parse("以下が答えです。\n選択：Ｎ１、ｎ２\n結晶：読んできた本はどれも設計の判断を扱っていた。\n以上です。")
        assertEquals(CrystalAnswer.Chosen(listOf("N1", "N2"), "読んできた本はどれも設計の判断を扱っていた。"), answer)
    }

    @Test
    fun `句点が無ければ足す`() {
        val answer = parse("選択: N1, N2\n結晶: どちらも習慣を小さく始める工夫を扱っている")
        assertEquals(CrystalAnswer.Chosen(listOf("N1", "N2"), "どちらも習慣を小さく始める工夫を扱っている。"), answer)
    }

    @Test
    fun `括弧で囲まれた文は括弧を外す`() {
        val answer = parse("選択: N1, N2\n結晶: 「どちらも習慣を小さく始める工夫を扱っている。」")
        assertEquals(CrystalAnswer.Chosen(listOf("N1", "N2"), "どちらも習慣を小さく始める工夫を扱っている。"), answer)
    }

    @Test
    fun `今のノートを含まない選択は不正`() {
        assertEquals(CrystalAnswer.Invalid, parse("選択: N2, N3\n結晶: どちらも習慣を小さく始める工夫を扱っている。"))
    }

    @Test
    fun `他が0件か4件以上なら不正`() {
        assertEquals(CrystalAnswer.Invalid, parse("選択: N1\n結晶: 習慣を小さく始める工夫を扱っている。"))
        assertEquals(
            CrystalAnswer.Invalid,
            parse("選択: N1, N2, N3, N4, N5\n結晶: どれも習慣を小さく始める工夫を扱っている。")
        )
    }

    @Test
    fun `提示していないIDは不正`() {
        assertEquals(
            CrystalAnswer.Invalid,
            parse("選択: N1, N4\n結晶: どちらも習慣を小さく始める工夫を扱っている。", ids = setOf("N1", "N2", "N3"))
        )
    }

    @Test
    fun `2文以上・疑問文は不正`() {
        assertEquals(CrystalAnswer.Invalid, parse("選択: N1, N2\n結晶: 習慣の本だった。設計の本でもあった。"))
        assertEquals(CrystalAnswer.Invalid, parse("選択: N1, N2\n結晶: どちらも習慣を扱っていたのではないか？"))
        assertEquals(CrystalAnswer.Invalid, parse("選択: N1, N2\n結晶: どちらも習慣を扱っていたのだろうか。"))
    }

    @Test
    fun `長さの両端で分ける`() {
        assertEquals(CrystalAnswer.Invalid, parse("選択: N1, N2\n結晶: 短い文。"))
        val tenCharacters = "あ".repeat(9) + "。"
        assertEquals(CrystalAnswer.Chosen(listOf("N1", "N2"), tenCharacters), parse("選択: N1, N2\n結晶: $tenCharacters"))
        val longest = "あ".repeat(119) + "。"
        assertEquals(CrystalAnswer.Chosen(listOf("N1", "N2"), longest), parse("選択: N1, N2\n結晶: $longest"))
        assertEquals(CrystalAnswer.Invalid, parse("選択: N1, N2\n結晶: ${"あ".repeat(120)}。"))
    }

    @Test
    fun `本文へ漏れた候補IDは不正`() {
        assertEquals(CrystalAnswer.Invalid, parse("選択: N1, N2\n結晶: N1とN2はどちらも習慣を扱っている。"))
    }

    @Test
    fun `どちらかの行が無ければ不正`() {
        assertEquals(CrystalAnswer.Invalid, parse("選択: N1, N2"))
        assertEquals(CrystalAnswer.Invalid, parse("結晶: どちらも習慣を小さく始める工夫を扱っている。"))
        assertEquals(CrystalAnswer.Invalid, parse(""))
    }
}
