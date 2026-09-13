package com.example.newproject.testing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **実機で何を生成し、何を使い回すかを、端末を使わずに固定する。**
 *
 * 2026-09-13 の実機では27組を順に生成し、**12回が同じプロンプトの再生成**だった。
 * 13回目で AICore が `ErrorCode 9 / BUSY` を返したとき、そのプロンプトは40秒前に成功したものと
 * バイト単位で同じだった。**生成は決定的なので、再生成は情報を増やさず枠だけを使う。**
 */
class SummaryBaselinePlanTest {

    private val plan = planSummaryBaseline(FixedCorpus.notes())

    @Test
    fun `全組のうち生成するのは異なるプロンプトの数だけ`() {
        assertEquals("9本 × 3変種", 27, plan.items.size)
        assertEquals("別々のプロンプトは本番9本と、長文3本の旧方式・予算2倍", 15, plan.generations.size)
    }

    /**
     * **比べてよいノートを、生成せずに見分ける。** 入力が同じノートを合計に混ぜると、
     * 差の出ようがない組が「差が小さい」側へ結論を引っ張る。
     */
    @Test
    fun `入力が全変種で同じノートと、比べてよいノートを見分ける`() {
        assertEquals(
            listOf(
                "0100_constraints_heavy", "0300_bullet_list", "0400_tiny_memo",
                "0500_code_fence", "0600_frontmatter_table", "0700_no_heading"
            ),
            plan.identicalInputNotes
        )
        assertEquals(listOf("0200_long_nested", "0800_tail_conclusion", "0900_middle_key"), plan.comparableNotes)
    }

    @Test
    fun `同じプロンプトの組は、先に現れた本番の応答を使い回す`() {
        val headOnlyShort = plan.items.single { it.key == "head_only/0400_tiny_memo" }
        val headOnlyLong = plan.items.single { it.key == "head_only/0900_middle_key" }

        assertEquals("production/0400_tiny_memo", plan.sourceOf(headOnlyShort).key)
        assertEquals("長文は自分で生成する", headOnlyLong.key, plan.sourceOf(headOnlyLong).key)
    }

    /**
     * **使い回しは実行の中だけで決める。** 別の実行で採った本番の応答を当てにすると、
     * 途中から再開した実行が、その回には無い応答を参照することになる。
     */
    @Test
    fun `絞り込んだ実行では、その中で使い回しを決め直す`() {
        val headOnly = plan.select(variantKeys = "head_only", notes = null)

        assertEquals(9, headOnly.items.size)
        assertEquals("本番を含まないので、短いノートも自分で生成する", 9, headOnly.generations.size)
        assertEquals("変種が1つなら比べる対象は無い", emptyList<String>(), headOnly.identicalInputNotes)
    }

    /**
     * **分けて採っても、重複と欠落が無いことを照合できる。** BUSY で止まった実行を
     * 引数で再開したとき、欠けた組を足し、成功済みの組を採り直さない。
     */
    @Test
    fun `実行を分けたとき、組の和は全体と一致し重なりが無い`() {
        val first = plan.select(variantKeys = "production", notes = null).items.map { it.key }
        val rest = plan.select(variantKeys = "head_only,budget_x2", notes = null).items.map { it.key }

        assertTrue("分けた実行どうしで同じ組を採っています", first.intersect(rest.toSet()).isEmpty())
        assertEquals(plan.items.map { it.key }.sorted(), (first + rest).sorted())
    }

    /**
     * **再開する組には、失敗した組そのものを含める**（2026-09-13 机上レビュー P2-1）。
     * 先頭・途中・最後のどこで止まっても、成功済みとの和が全体になり、重なりが無い。
     */
    @Test
    fun `どこで止まっても、再開する組と成功済みの組の和は全体になる`() {
        val all = plan.items.map { it.key }
        listOf(0, all.size / 2, all.lastIndex).forEach { position ->
            val failed = all[position]
            val succeeded = all.take(position)
            val resume = plan.resumeKeysAfter(failed)

            assertTrue("$failed で止まったのに、再開する組に自分が入っていません", failed in resume)
            assertTrue("$failed: 成功済みの組を採り直します", succeeded.intersect(resume.toSet()).isEmpty())
            assertEquals("$failed: 欠けた組があります", all, succeeded + resume)
            // 再開する組はそのまま引数に渡せる
            assertEquals(resume, plan.select(null, null, keys = resume.joinToString(",")).items.map { it.key })
        }
    }

    @Test
    fun `組の指定は変種やノートの指定と混ぜられず、知らない組は失敗させる`() {
        assertThrows(IllegalArgumentException::class.java) {
            plan.select(variantKeys = "production", notes = null, keys = "production/0100_constraints_heavy")
        }
        assertThrows(IllegalArgumentException::class.java) {
            plan.select(variantKeys = null, notes = null, keys = "production/0100")
        }
    }

    @Test
    fun `ノートと変種の両方で絞り込める`() {
        val selected = plan.select(variantKeys = " head_only , budget_x2 ", notes = "0400_tiny_memo,0900_middle_key")

        assertEquals(
            listOf(
                "head_only/0400_tiny_memo", "head_only/0900_middle_key",
                "budget_x2/0400_tiny_memo", "budget_x2/0900_middle_key"
            ),
            selected.items.map { it.key }
        )
    }

    @Test
    fun `引数が無いか空なら絞り込まない`() {
        assertEquals(27, plan.select(null, null).items.size)
        assertEquals(27, plan.select("", " ").items.size)
    }

    /** **綴りを間違えた実行が「0件を採って成功」に見えないようにする。** */
    @Test
    fun `知らない変種やノートの名前は失敗させる`() {
        val unknownVariant = assertThrows(IllegalArgumentException::class.java) {
            plan.select(variantKeys = "production,headonly", notes = null)
        }
        assertTrue(unknownVariant.message!!.contains("headonly"))
        assertTrue("使える名前を示すこと", unknownVariant.message!!.contains("head_only"))

        assertThrows(IllegalArgumentException::class.java) {
            plan.select(variantKeys = null, notes = "0400_tiny_memo.md")
        }
    }

    @Test
    fun `タイトルは先頭の見出しから採り、見出しが無ければファイル名にする`() {
        assertEquals("鍵の預け先", baselineNoteTitle("0400_tiny_memo.md", "# 鍵の預け先\n\n本文"))
        assertEquals("0700_no_heading", baselineNoteTitle("0700_no_heading.md", "見出しの無い本文。"))
    }
}
