package com.example.newproject

import com.example.newproject.data.NoteRepository
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * frontmatter の `tags` と `aliases` の読み方。**YAML の構文（コメント・引用符）をタグの値に混ぜない。**
 * 混ぜると、関連ノートの根拠に実在しないタグが「共通」と出る（→ features/related_notes_ai.md §5）。
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
    fun `aliases も同じ読み方をする`() {
        val meta = NoteRepository().parseMeta("---\naliases:\n  - \"別名\"\n  # 古い名前\n---\n本文")

        assertEquals(listOf("別名"), meta.aliases)
    }
}
