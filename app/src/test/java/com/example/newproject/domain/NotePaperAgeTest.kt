package com.example.newproject.domain

import com.example.newproject.model.DocumentRef
import com.example.newproject.model.NoteFile
import com.example.newproject.model.NotePaperTone
import com.example.newproject.model.RelatedNote
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 紙の地色の段階を Vault 内の相対順位で決めるロジック。
 *
 * ここで守るのは「材料が揃わないときに勝手な色を付けないこと」と
 * 「どんなVaultでも段階が分布すること」の2つ。
 */
class NotePaperAgeTest {

    /** 1..n の連番を古い順に並べた分布。索引が小さいほど古い。 */
    private fun vault(size: Int): List<Long?> = (1..size).map { it.toLong() }

    @Test
    fun `分布の四分位で段階が決まる`() {
        val all = vault(8)
        // 自分より新しいものの割合で決まる。8件中、値1は7件が新しい（0.875）＝最も古い。
        assertEquals(NotePaperTone.Weathered, notePaperTone(1L, all))
        assertEquals(NotePaperTone.Weathered, notePaperTone(2L, all)) // 0.750
        assertEquals(NotePaperTone.Aged, notePaperTone(3L, all))      // 0.625
        assertEquals(NotePaperTone.Aged, notePaperTone(4L, all))      // 0.500
        assertEquals(NotePaperTone.Settling, notePaperTone(5L, all))  // 0.375
        assertEquals(NotePaperTone.Settling, notePaperTone(6L, all))  // 0.250
        assertEquals(NotePaperTone.Fresh, notePaperTone(7L, all))     // 0.125
        assertEquals(NotePaperTone.Fresh, notePaperTone(8L, all))     // 0.000
    }

    @Test
    fun `最終更新が不明なノートは色を付けない`() {
        assertEquals(NotePaperTone.Fresh, notePaperTone(null, vault(8)))
    }

    @Test
    fun `分布が空なら色を付けない`() {
        // 走査キャッシュが冷えている経路。誤った色を出すより何も起きないほうがよい。
        assertEquals(NotePaperTone.Fresh, notePaperTone(100L, emptyList()))
    }

    @Test
    fun `分布が不明値だけなら色を付けない`() {
        assertEquals(NotePaperTone.Fresh, notePaperTone(100L, listOf(null, null, null)))
    }

    @Test
    fun `ノートが1本だけのVaultでは最も新しい扱いになる`() {
        assertEquals(NotePaperTone.Fresh, notePaperTone(50L, listOf(50L)))
    }

    @Test
    fun `全ノートが同じ時刻なら段差を作らない`() {
        // 「自分より新しいもの」が誰も居ないので全員 Fresh。
        // 人工的な地層を作らないための性質で、絶対閾値との一番大きな違いになる。
        val all = listOf<Long?>(7L, 7L, 7L, 7L)
        assertEquals(NotePaperTone.Fresh, notePaperTone(7L, all))
    }

    @Test
    fun `不明値は分母から外す`() {
        // 値ありは4件（1,2,3,4）。値1は3件が新しいので 0.75 ＝ Weathered。
        // null を分母に数えていると 3/7 = 0.43 で Settling になり、判定がずれる。
        val all = listOf<Long?>(1L, 2L, 3L, 4L, null, null, null)
        assertEquals(NotePaperTone.Weathered, notePaperTone(1L, all))
    }

    @Test
    fun `分布に対象自身が入っているかで結果が変わる`() {
        // **分母は一覧の件数**なので、対象を含めるかどうかで割合がずれる。
        // レビュー指摘（2026-08-02）で判明。以前はここに「影響しない」と書いており、
        // たまたま同じ段階に落ちる値でだけ通っていた。
        val all = vault(5)
        assertEquals(NotePaperTone.Fresh, notePaperTone(4L, all))        // 1/5 = 0.20
        assertEquals(NotePaperTone.Settling, notePaperTone(4L, all - 4L)) // 1/4 = 0.25
    }

    @Test
    fun `対象がキャッシュに無い経路では1件補って分母を揃える`() {
        // さがす経由（RelatedNote は持つが走査キャッシュには居ない）で通る形。
        // 補わないと分母が1件少ないまま「自分より新しい」だけを数えることになる。
        val cached = listOf<Long?>(1L, 2L, 3L, 5L)
        val target = 4L
        assertEquals(NotePaperTone.Fresh, notePaperTone(target, cached + target)) // 1/5
    }

    @Test
    fun `更新日時が複数あるVaultなら幅の広狭によらず全段階が現れる`() {
        // 絶対年数の閾値を採らなかった理由そのもの。
        // 1日しか幅の無いVaultでも、10年幅のVaultでも、分布は同じ形になる。
        // **「どんなVaultでも」ではない** — 1本だけ・全件同時刻の場合は上のテストのとおり
        // 段階が分かれず、それが正しい振る舞い。
        val day = 24L * 60 * 60 * 1000
        val narrow = (0 until 8).map { it * (day / 8) }
        val wide = (0 until 8).map { it * 365L * 10 * day / 8 }
        listOf(narrow, wide).forEach { all ->
            val tones = all.map { notePaperTone(it, all) }
            assertEquals(
                setOf(
                    NotePaperTone.Fresh,
                    NotePaperTone.Settling,
                    NotePaperTone.Aged,
                    NotePaperTone.Weathered
                ),
                tones.toSet()
            )
        }
    }

    // ── さがす・関連ノート経由（材料が2箇所に散る）────────────────────────

    private fun noteFile(id: String, lastModified: Long?) =
        NoteFile(name = id, ref = DocumentRef("content://$id"), lastModified = lastModified)

    private fun candidate(id: String, lastModified: Long?) =
        RelatedNote(
            title = id,
            ref = DocumentRef("content://$id"),
            isWikilinked = false,
            lastModified = lastModified
        )

    @Test
    fun `候補自身の更新日時をキャッシュより優先する`() {
        // 検索結果は新しい値を持ち、キャッシュは古い値のまま、という状況。
        // キャッシュ側（1L＝最古）を採ると Weathered、候補側（9L＝最新）なら Fresh。
        val cached = listOf(
            noteFile("a", 1L), noteFile("b", 2L), noteFile("c", 3L), noteFile("d", 4L)
        )
        assertEquals(NotePaperTone.Fresh, notePaperToneForCandidate(candidate("a", 9L), cached))
    }

    @Test
    fun `候補が更新日時を持たなければキャッシュへ落ちる`() {
        // 当日履歴から作られた候補は lastModified を持たない（SearchScreen の経路）。
        val cached = listOf(
            noteFile("a", 1L), noteFile("b", 2L), noteFile("c", 3L), noteFile("d", 4L)
        )
        assertEquals(NotePaperTone.Weathered, notePaperToneForCandidate(candidate("a", null), cached))
    }

    @Test
    fun `キャッシュに居ない候補は分母へ1件補われる`() {
        // 補わないと分母が4件（1/4 = 0.25 で Settling）になる。
        // 補えば5件（1/5 = 0.20 で Fresh）で、Vault全体を渡した場合と一致する。
        val cached = listOf(
            noteFile("a", 1L), noteFile("b", 2L), noteFile("c", 3L), noteFile("e", 5L)
        )
        assertEquals(NotePaperTone.Fresh, notePaperToneForCandidate(candidate("d", 4L), cached))
    }

    @Test
    fun `材料が何も無ければ色を付けない`() {
        assertEquals(NotePaperTone.Fresh, notePaperToneForCandidate(candidate("a", null), emptyList()))
    }

    @Test
    fun `キャッシュが空でも候補の更新日時だけで落ち着く`() {
        // 補った1件だけになるので Fresh（＝現行の色）。誤った段階を出さない。
        assertEquals(NotePaperTone.Fresh, notePaperToneForCandidate(candidate("a", 9L), emptyList()))
    }
}
