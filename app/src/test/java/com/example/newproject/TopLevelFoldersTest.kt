package com.example.newproject

import com.example.newproject.data.ChildDoc
import com.example.newproject.data.SafChildren
import com.example.newproject.data.topLevelFoldersOf
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Explore の範囲のチップに出す第一階層のフォルダ（→ features/ai_picker.md §5）。
 * **読めなかった一覧を空一覧として返さない。** 返すと、選んでいたフォルダが外で消えたと見分けられず、範囲と結果を捨てる。
 */
class TopLevelFoldersTest {

    @Test
    fun `読めた一覧からフォルダだけを名前の順に返し、外すフォルダは出さない`() {
        val children = SafChildren.complete(
            listOf(
                ChildDoc("doc-notes", "notes", isDirectory = true, lastModified = null),
                ChildDoc("doc-memo", "memo.md", isDirectory = false, lastModified = null),
                ChildDoc("doc-obsidian", ".obsidian", isDirectory = true, lastModified = null),
                ChildDoc("doc-ideas", "ideas", isDirectory = true, lastModified = null)
            )
        )

        assertEquals(listOf("ideas", "notes"), topLevelFoldersOf(children).map { it.name })
    }

    @Test
    fun `空のVaultは空一覧で、読めなかった一覧は例外にする`() {
        assertEquals(emptyList<String>(), topLevelFoldersOf(SafChildren.complete(emptyList())).map { it.name })

        assertThrows(IOException::class.java) { topLevelFoldersOf(SafChildren.UNREADABLE) }
    }
}
