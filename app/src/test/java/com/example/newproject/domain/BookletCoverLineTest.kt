package com.example.newproject.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 扉（冊子の代表文）の受け入れ条件を固定する。
 *
 * **ここが冊子の印象をほぼ決める**ので、除外・決定性・長さ・フォールバックを
 * 設計の受け入れ条件（features/booklet_mode.md §10）と1対1で並べている。
 */
class BookletCoverLineTest {

    @Test
    fun `frontmatter は扉にしない`() {
        val content = """
            ---
            tags: [読書, 設計]
            created: 2026-08-30
            ---
            本文の最初の文である。
        """.trimIndent()

        assertEquals("本文の最初の文である。", selectCoverLine(content, "タイトル"))
    }

    @Test
    fun `見出しは扉にしない`() {
        val content = """
            # 大見出し
            ## 小見出し
            見出しではない文がここにある。
        """.trimIndent()

        assertEquals("見出しではない文がここにある。", selectCoverLine(content, "タイトル"))
    }

    @Test
    fun `コードフェンスの中は扉にしない`() {
        val content = """
            ```kotlin
            val answer = 42
            ```
            コードの外の文を選ぶ。
        """.trimIndent()

        assertEquals("コードの外の文を選ぶ。", selectCoverLine(content, "タイトル"))
    }

    @Test
    fun `閉じていないフェンス以降は本文とみなさない`() {
        val content = """
            ```
            val leaked = "これは扉にならない"
            これもコードの続きとみなす。
        """.trimIndent()

        assertEquals("タイトル", selectCoverLine(content, "タイトル"))
    }

    @Test
    fun `リンクだけの行は扉にしない`() {
        val content = """
            [[別のノート]]
            [参考](https://example.com/a)
            ![図](image.png)
            リンクではない文を選ぶ。
        """.trimIndent()

        assertEquals("リンクではない文を選ぶ。", selectCoverLine(content, "タイトル"))
    }

    @Test
    fun `リンクを含むだけの文は扉にできる`() {
        val content = "詳しくは [[設計メモ]] に書いた。"

        assertEquals("詳しくは 設計メモ に書いた。", selectCoverLine(content, "タイトル"))
    }

    @Test
    fun `表の区切り行は扉にしない`() {
        val content = """
            | 項目 | 値 |
            |---|:--|
        """.trimIndent()

        assertEquals("項目 値", selectCoverLine(content, "タイトル"))
    }

    @Test
    fun `罫線だけの行は扉にしない`() {
        val content = """
            ---
            ***
            罫線の後の文を選ぶ。
        """.trimIndent()

        assertEquals("罫線の後の文を選ぶ。", selectCoverLine(content, "タイトル"))
    }

    @Test
    fun `Markdown記法は表示文字列に残らない`() {
        val content = "- **強調**した `コード` と ~~取り消し~~ を含む文。"

        assertEquals("強調した コード と 取り消し を含む文。", selectCoverLine(content, "タイトル"))
    }

    @Test
    fun `1行に複数の文があれば最初の1文だけを出す`() {
        val content = "最初の文である。次の文は出さない。"

        assertEquals("最初の文である。", selectCoverLine(content, "タイトル"))
    }

    @Test
    fun `括弧の内側の終止符では切らない`() {
        val content = "問いは「これでいいのか？」だった。"

        assertEquals("問いは「これでいいのか？」だった。", selectCoverLine(content, "タイトル"))
    }

    @Test
    fun `同じ本文からは何度呼んでも同じ文を返す`() {
        val content = """
            # 見出し
            最初の文である。
            二番目の文である。
        """.trimIndent()

        val results = (1..5).map { selectCoverLine(content, "タイトル") }

        assertEquals(setOf("最初の文である。"), results.toSet())
    }

    /**
     * **上限そのものを固定する。** 他のテストは `BOOKLET_COVER_MAX_CHARS` を期待値に使うので
     * 定数を変えても一緒に動いてしまい、**40という受け入れ条件は誰も見ていなかった**
     * （変異確認で 40→39 が緑のまま通った）。正本は features/booklet_mode.md §10。
     */
    @Test
    fun `扉の上限は全角40字`() {
        assertEquals(40, BOOKLET_COVER_MAX_CHARS)
    }

    @Test
    fun `上限ちょうどは切らない`() {
        val content = "あ".repeat(BOOKLET_COVER_MAX_CHARS)

        val cover = selectCoverLine(content, "タイトル")

        assertEquals(content, cover)
        assertEquals(BOOKLET_COVER_MAX_CHARS, cover.length)
    }

    @Test
    fun `上限を超えたら省略記号つきで上限に収める`() {
        val content = "あ".repeat(BOOKLET_COVER_MAX_CHARS + 10)

        val cover = selectCoverLine(content, "タイトル")

        assertEquals(BOOKLET_COVER_MAX_CHARS, cover.length)
        assertTrue("末尾が省略記号ではない: $cover", cover.endsWith("…"))
    }

    @Test
    fun `絵文字は途中で割らない`() {
        // 上限をまたぐ位置がサロゲートペア（1文字が2 UTF-16 単位）になるよう並べる。
        val content = "あ".repeat(20) + "🌱".repeat(BOOKLET_COVER_MAX_CHARS - 19)

        val cover = selectCoverLine(content, "タイトル")

        assertEquals(BOOKLET_COVER_MAX_CHARS, cover.codePointCount(0, cover.length))
        assertTrue("片割れのサロゲートが残っている: $cover", cover.hasNoLoneSurrogate())
        assertTrue(cover.endsWith("…"))
    }

    /** **絵文字だけの行は文字を含まないので扉にしない。** 記号だけの行と同じ扱い。 */
    @Test
    fun `絵文字だけの行は扉にしない`() {
        val content = """
            🌱🌱🌱
            絵文字の後の文を選ぶ。
        """.trimIndent()

        assertEquals("絵文字の後の文を選ぶ。", selectCoverLine(content, "タイトル"))
    }

    @Test
    fun `選べる文が無ければタイトルを出す`() {
        val content = """
            ---
            tags: [空]
            ---
            # 見出しだけ
            [[リンクだけ]]
        """.trimIndent()

        assertEquals("見出しだけのノート", selectCoverLine(content, "見出しだけのノート"))
    }

    @Test
    fun `本文が空でもタイトルを出す`() {
        assertEquals("空のノート", selectCoverLine("", "空のノート"))
    }

    @Test
    fun `フォールバックしたタイトルも上限で切る`() {
        val title = "長".repeat(BOOKLET_COVER_MAX_CHARS + 5)

        val cover = selectCoverLine("", title)

        assertEquals(BOOKLET_COVER_MAX_CHARS, cover.length)
        assertTrue(cover.endsWith("…"))
    }

    /** 片割れだけのサロゲートが残っていないか。**位置ごとに見る**（同じ絵文字が並ぶため）。 */
    private fun String.hasNoLoneSurrogate(): Boolean {
        var index = 0
        while (index < length) {
            val char = this[index]
            when {
                char.isHighSurrogate() -> {
                    if (index + 1 >= length || !this[index + 1].isLowSurrogate()) return false
                    index += 2
                }
                char.isLowSurrogate() -> return false
                else -> index++
            }
        }
        return true
    }
}
