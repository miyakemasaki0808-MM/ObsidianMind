package com.example.newproject

import com.example.newproject.data.NoteRepository
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * frontmatter の `tags` と `aliases` の読み方。**YAML の構文（コメント・引用符）をタグの値に混ぜない。**
 * 混ぜると、関連ノートの根拠に実在しないタグが「共通」と出る（→ features/related_notes_ai.md §5）。
 *
 * **確実に読める書き方だけを読み、それ以外が1つでも混ざったらそのキーをまるごと使わない。**
 * 推測して読むと、事実に反するタグや欠けた別名を出す。使わなければ、根拠が出ないだけで済む。
 */
class FrontmatterTagsTest {

    private fun tagsOf(frontmatter: String): List<String> =
        NoteRepository().parseMeta("---\n$frontmatter\n---\n本文").tags

    @Test
    fun `ブロック形式は項目だけを読み、コメントの行と行末のコメントを外す`() {
        val tags = tagsOf(
            """
            tags:
              - 読書
              # タグは随時整理
              - 習慣 # 主に朝
            """.trimIndent()
        )

        assertEquals(listOf("読書", "習慣"), tags)
    }

    @Test
    fun `引用符で囲んだ値は引用符を外す`() {
        assertEquals(listOf("読書", "料理"), tagsOf("tags: [\"読書\", '料理']"))
        assertEquals(listOf("読書", "料理"), tagsOf("tags:\n  - \"読書\"\n  - '料理'"))
    }

    @Test
    fun `インライン形式の後ろのコメントを外す`() {
        assertEquals(listOf("読書", "習慣"), tagsOf("tags: [読書, 習慣] # 整理中"))
    }

    @Test
    fun `シャープで始まる項目は YAML ではコメントなので、タグに数えない`() {
        assertEquals(listOf("読書"), tagsOf("tags:\n  - 読書\n  - #メモ"))
    }

    @Test
    fun `インライン一覧の後ろのコメントにある括弧とカンマに左右されない`() {
        assertEquals(listOf("読書"), tagsOf("tags: [読書] # 旧: [資料, 下書き]"))
        assertEquals(listOf("料理"), tagsOf("tags: [料理] # 旧: [資料, 下書き]"))
    }

    @Test
    fun `引用符の中の シャープ・カンマ・括弧は値の一部で、外のコメントだけを外す`() {
        val meta = NoteRepository().parseMeta(
            "---\naliases:\n  - \"議事録 #1\"\n  - '議事録 #2'\n  - \"議事録 #3\" # 外のコメント\n---\n本文"
        )
        assertEquals(listOf("議事録 #1", "議事録 #2", "議事録 #3"), meta.aliases)

        val inline = NoteRepository().parseMeta("---\naliases: [\"議事録 #1\", '議事録, 第2回', \"[下書き]\"]\n---\n本文")
        assertEquals(listOf("議事録 #1", "議事録, 第2回", "[下書き]"), inline.aliases)
    }

    @Test
    fun `値の中のシャープと、引用符で囲んだシャープ始まりは保つ`() {
        assertEquals(listOf("C#", "#読書"), tagsOf("tags: [C#, \"#読書\"]"))
        assertEquals(listOf("C#", "#読書"), tagsOf("tags:\n  - C#\n  - \"#読書\"\n  - #メモ"))
    }

    @Test
    fun `aliases も同じ読み方をする`() {
        val meta = NoteRepository().parseMeta("---\naliases:\n  - \"別名\"\n  # 古い名前\n---\n本文")

        assertEquals(listOf("別名"), meta.aliases)
    }

    // ── 読めない形はキーごと使わない ─────────────────────────────────────────

    @Test
    fun `次の行へ続くインライン一覧は、コメントがあっても無くても使わない`() {
        assertEquals(emptyList<String>(), tagsOf("tags: [読書, # 旧: 資料, 下書き]\n  習慣]"))
        assertEquals(emptyList<String>(), tagsOf("tags: [読書,\n  習慣]"))
    }

    @Test
    fun `インライン一覧の中のコメントは使わない`() {
        assertEquals(emptyList<String>(), tagsOf("tags: [読書, # 旧: 資料]"))
    }

    @Test
    fun `引用符のエスケープを含む一覧は使わない`() {
        fun aliasesOf(frontmatter: String) = NoteRepository().parseMeta("---\n$frontmatter\n---\n本文").aliases

        assertEquals(emptyList<String>(), aliasesOf("aliases: [\"a\\\"b\", next]"))
        assertEquals(emptyList<String>(), aliasesOf("aliases: ['it''s', next]"))
        assertEquals(emptyList<String>(), aliasesOf("aliases:\n  - \"a\\\"b\" # comment"))
        assertEquals(emptyList<String>(), aliasesOf("aliases:\n  - 'it''s' # comment"))
        // 二重引用符の中のバックスラッシュは YAML ではエスケープなので、そのままの文字として読まない。
        assertEquals(emptyList<String>(), aliasesOf("aliases: [\"C:\\\\Users\"]"))
    }

    @Test
    fun `一覧の後ろのコメントでない文字や、入れ子の一覧は使わない`() {
        assertEquals(emptyList<String>(), tagsOf("tags: [読書] 習慣"))
        assertEquals(emptyList<String>(), tagsOf("tags:\n  - [読書]"))
        assertEquals(emptyList<String>(), tagsOf("tags:\n  - - 読書"))
        assertEquals(emptyList<String>(), tagsOf("tags: [a[b]"))
        // 1行で閉じた一覧の後ろに続きの行があるのも、読めない形。
        assertEquals(emptyList<String>(), tagsOf("tags: [読書]\n  習慣]"))
    }

    @Test
    fun `閉じない括弧や引用符、項目でない行を含む一覧は使わない`() {
        assertEquals(emptyList<String>(), tagsOf("tags: [読書, 習慣"))
        assertEquals(emptyList<String>(), tagsOf("tags:\n  - \"読書\n  - 習慣"))
        assertEquals(emptyList<String>(), tagsOf("tags:\n  - 読書\n  sub: 習慣"))
    }

    @Test
    fun `タブに続くコメントのある一覧は使わない`() {
        assertEquals(emptyList<String>(), tagsOf("tags: [read\t# old, fake]\n  ]"))
        assertEquals(emptyList<String>(), tagsOf("tags:\n  - read\t# old"))
    }

    @Test
    fun `引用符なしの辞書の項目を含む一覧は使わず、引用符つきなら文字列として読む`() {
        assertEquals(emptyList<String>(), tagsOf("tags: [safe, category: book]"))
        assertEquals(emptyList<String>(), tagsOf("tags:\n  - safe\n  - category: book"))
        assertEquals(listOf("category: book"), tagsOf("tags: [\"category: book\"]"))
    }

    @Test
    fun `深い続きの行や入れ子の一覧は使わない`() {
        assertEquals(emptyList<String>(), tagsOf("tags:\n  - 読書\n    - 習慣"))
        assertEquals(emptyList<String>(), tagsOf("tags:\n  -\n    - 読書"))
        assertEquals(listOf("読書", "習慣"), tagsOf("tags:\n  - 読書\n  - 習慣\n  - #メモ"))
    }

    @Test
    fun `語の途中の引用符は区切りを隠さない`() {
        val meta = NoteRepository().parseMeta("---\naliases: [Alice's, Bob's]\n---\n本文")
        assertEquals(listOf("Alice's", "Bob's"), meta.aliases)
        assertEquals(listOf("a'b", "c'd"), tagsOf("tags: [a'b,c'd]"))
    }

    @Test
    fun `ルートのキーだけを読み、文字列の中や入れ子の同名キーは採らない`() {
        assertEquals(emptyList<String>(), tagsOf("description: |\n  tags: [ghost]"))
        assertEquals(listOf("real"), tagsOf("extra:\n  tags: [ghost]\ntags: [real]"))
        assertEquals(emptyList<String>(), tagsOf("tags:[ghost]"))
        // ルートに同じキーが2つあるのは YAML として壊れている。どちらとも決めない。
        assertEquals(emptyList<String>(), tagsOf("tags: [a]\ntags: [b]"))
        val meta = NoteRepository().parseMeta("---\nnote: |\n  aliases: [ghost]\n---\n本文")
        assertEquals(emptyList<String>(), meta.aliases)
    }

    @Test
    fun `空行や行頭のコメントで打ち切らず、ブロックの全体を見てから読む`() {
        assertEquals(emptyList<String>(), tagsOf("tags:\n  - safe\n\n  - [nested]"))
        assertEquals(emptyList<String>(), tagsOf("tags:\n  - safe\n# comment\n  - [nested]"))
        assertEquals(listOf("safe", "second"), tagsOf("tags:\n  - safe\n\n  - second"))
        assertEquals(listOf("safe", "second"), tagsOf("tags:\n  - safe\n# comment\n  - second"))
    }

    @Test
    fun `読めないのは崩れたキーだけで、ほかのキーは読む`() {
        val meta = NoteRepository().parseMeta("---\ntags: [読書, # 旧]\naliases: [別名]\n---\n本文")

        assertEquals(emptyList<String>(), meta.tags)
        assertEquals(listOf("別名"), meta.aliases)
    }
}
