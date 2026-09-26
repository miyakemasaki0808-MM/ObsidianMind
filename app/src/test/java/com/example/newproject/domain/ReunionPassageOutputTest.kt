package com.example.newproject.domain

import com.example.newproject.model.ReunionPassage
import org.junit.Assert.assertEquals
import org.junit.Test

// 前後の要約の生成結果から境目の印を落とす（→ features/reunion_card.md 判断6「前後の要約」）。
class ReunionPassageOutputTest {

    private val plain = ReunionPassage(before = "導入の段落。", after = "具体例の段落。")

    @Test
    fun `復唱された印を落とし、2文を保つ`() {
        assertEquals(
            "直前は導入を読んでいた。この先は具体例に入る。",
            cleanReunionPassageOutput("直前は導入を読んでいた。[READ UP TO HERE] この先は具体例に入る。", plain)
        )
    }

    @Test
    fun `印だけの行は行ごと消す`() {
        assertEquals(
            "直前は導入を読んでいた。\nこの先は具体例に入る。",
            cleanReunionPassageOutput("直前は導入を読んでいた。\n[READ UP TO HERE]\nこの先は具体例に入る。", plain)
        )
    }

    @Test
    fun `括弧・大小文字・鉤括弧の揺れも同じ印として落とす`() {
        listOf("「[READ UP TO HERE]」", "[read up to here]", "READ UP TO HERE", "（[Read  Up To Here]）").forEach { marker ->
            assertEquals(marker, "前は導入。後は例。", cleanReunionPassageOutput("前は導入。${marker}後は例。", plain))
        }
    }

    @Test
    fun `英単語に挟まれた印は空白1つで語を分け、印の外の空白には触れない`() {
        assertEquals(
            "Kotlin の Flow を読んでいた。 next part は例。",
            cleanReunionPassageOutput("Kotlin の Flow を読んでいた。 next [READ UP TO HERE]  part は例。", plain)
        )
        assertEquals("intro next", cleanReunionPassageOutput("intro [READ UP TO HERE] next", plain))
    }

    @Test
    fun `印の無い出力は前後の空白だけを落とす`() {
        assertEquals("前は導入。後は例。", cleanReunionPassageOutput("  前は導入。後は例。\n", plain))
    }

    /** 本文そのものが同じ言葉を含むなら、復唱か引用かを区別できない。本人の言葉として残す。 */
    @Test
    fun `本文が同じ言葉を含むときは消さない`() {
        val quoting = ReunionPassage(before = "付箋に [READ UP TO HERE] と書いて挟んだ。", after = "続きの段落。")

        assertEquals(
            "付箋に [READ UP TO HERE] と書いた話を読んでいた。この先は続き。",
            cleanReunionPassageOutput("付箋に [READ UP TO HERE] と書いた話を読んでいた。この先は続き。", quoting)
        )
    }

    @Test
    fun `後ろ側の本文に同じ言葉があっても消さない`() {
        val quoting = ReunionPassage(before = "前の段落。", after = "ここに read up to here と書く。")

        assertEquals("read up to here の話。", cleanReunionPassageOutput("read up to here の話。", quoting))
    }
}
