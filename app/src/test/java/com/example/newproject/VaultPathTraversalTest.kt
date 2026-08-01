package com.example.newproject

import com.example.newproject.data.ChildDoc
import com.example.newproject.data.SafChildren
import com.example.newproject.data.joinVaultPath
import com.example.newproject.data.traverseMarkdownPaths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VaultPathTraversalTest {

    @Test
    fun `root level note keeps file name as relative path`() {
        val tree = FakeTree(mapOf("root" to listOf(file("n1", "habit.md"))))

        val result = traverseMarkdownPaths(startId = "root", listChildren = tree::list).entries

        assertEquals(listOf("habit.md"), result.map { it.vaultRelativePath })
    }

    @Test
    fun `nested note joins parent folder names`() {
        val tree = FakeTree(
            mapOf(
                "root" to listOf(dir("d1", "ideas")),
                "d1" to listOf(dir("d2", "2026"), file("n1", "habit.md")),
                "d2" to listOf(file("n2", "review.md"))
            )
        )

        val result = traverseMarkdownPaths(startId = "root", listChildren = tree::list).entries

        assertEquals(
            setOf("ideas/habit.md", "ideas/2026/review.md"),
            result.map { it.vaultRelativePath }.toSet()
        )
    }

    @Test
    fun `excluded folders are not descended into`() {
        val tree = FakeTree(
            mapOf(
                "root" to listOf(dir("d1", "_ReadingTraces"), dir("d2", "ideas")),
                "d1" to listOf(file("n1", "leak.md")),
                "d2" to listOf(file("n2", "keep.md"))
            )
        )

        val result = traverseMarkdownPaths(
            startId = "root",
            excludeFolderNames = setOf("_ReadingTraces"),
            listChildren = tree::list
        ).entries

        assertEquals(listOf("ideas/keep.md"), result.map { it.vaultRelativePath })
    }

    @Test
    fun `non markdown files are ignored`() {
        val tree = FakeTree(
            mapOf(
                "root" to listOf(
                    file("n1", "trace.json"),
                    file("n2", "photo.png"),
                    file("n3", "note.md")
                )
            )
        )

        val result = traverseMarkdownPaths(startId = "root", listChildren = tree::list).entries

        assertEquals(listOf("note.md"), result.map { it.vaultRelativePath })
    }

    @Test
    fun `start path prefixes results when traversal begins below root`() {
        val tree = FakeTree(
            mapOf(
                "d1" to listOf(dir("d2", "2026")),
                "d2" to listOf(file("n1", "review.md"))
            )
        )

        val result = traverseMarkdownPaths(
            startId = "d1",
            startPath = "ideas",
            listChildren = tree::list
        ).entries

        assertEquals(listOf("ideas/2026/review.md"), result.map { it.vaultRelativePath })
    }

    @Test
    fun `document id and last modified are carried through`() {
        val tree = FakeTree(mapOf("root" to listOf(file("n1", "habit.md", lastModified = 4242L))))

        val entry = traverseMarkdownPaths(startId = "root", listChildren = tree::list).entries.single()

        assertEquals("n1", entry.documentId)
        assertEquals("habit.md", entry.name)
        assertEquals(4242L, entry.lastModified)
    }

    // プロバイダが循環（子が親を指す）を返しても止まること。パスを持つようになった
    // ことで、循環すると相対パスとメモリが無限に伸びるため visited 集合が必要。
    @Test
    fun `cycles in the provider tree terminate`() {
        val tree = FakeTree(
            mapOf(
                "root" to listOf(dir("d1", "a")),
                "d1" to listOf(dir("root", "back"), file("n1", "note.md"))
            )
        )

        val result = traverseMarkdownPaths(startId = "root", listChildren = tree::list).entries

        assertEquals(listOf("a/note.md"), result.map { it.vaultRelativePath })
    }

    @Test
    fun `same folder name under different parents stays distinct`() {
        val tree = FakeTree(
            mapOf(
                "root" to listOf(dir("d1", "a"), dir("d2", "b")),
                "d1" to listOf(dir("d3", "shared")),
                "d2" to listOf(dir("d4", "shared")),
                "d3" to listOf(file("n1", "note.md")),
                "d4" to listOf(file("n2", "note.md"))
            )
        )

        val result = traverseMarkdownPaths(startId = "root", listChildren = tree::list).entries

        assertEquals(
            setOf("a/shared/note.md", "b/shared/note.md"),
            result.map { it.vaultRelativePath }.toSet()
        )
    }

    @Test
    fun `join vault path omits separator at root`() {
        assertEquals("habit.md", joinVaultPath("", "habit.md"))
        assertEquals("ideas/habit.md", joinVaultPath("ideas", "habit.md"))
        assertEquals("ideas/2026/habit.md", joinVaultPath("ideas/2026", "habit.md"))
    }

    @Test
    fun `empty vault yields no entries`() {
        val result = traverseMarkdownPaths(startId = "root", listChildren = FakeTree(emptyMap())::list)

        assertTrue(result.entries.isEmpty())
        // 空フォルダは「読めた結果が空」。不完全ではない。
        assertEquals(emptySet<String>(), result.unreadableFolderPaths)
    }

    // --- 列挙の失敗（SafChildren.isComplete = false）の扱い ---------------------
    //
    // ここが段階0 の本体。以前は失敗が空リストへ畳まれていたため、
    // 「読めなかった」と「本当に空」が区別できず、配下のノートが
    // 走査成功のまま静かに0件になっていた。

    @Test
    fun `unreadable folder is recorded by its path and other branches still collected`() {
        val tree = FakeTree(
            children = mapOf(
                "root" to listOf(dir("d1", "ideas"), dir("d2", "journal")),
                "d2" to listOf(file("n2", "keep.md"))
            ),
            unreadable = setOf("d1")
        )

        val result = traverseMarkdownPaths(startId = "root", listChildren = tree::list)

        // 読めた枝のノートは失われない（表示系はこれで動き続ける）。
        assertEquals(listOf("journal/keep.md"), result.entries.map { it.vaultRelativePath })
        // 読めなかったフォルダは相対パスで記録される。
        assertEquals(setOf("ideas"), result.unreadableFolderPaths)
    }

    @Test
    fun `unreadable root is recorded as the empty path`() {
        val tree = FakeTree(children = emptyMap(), unreadable = setOf("root"))

        val result = traverseMarkdownPaths(startId = "root", listChildren = tree::list)

        assertTrue(result.entries.isEmpty())
        // ルートが読めなかったことを空文字で表す。空集合（＝完全）と区別できる。
        assertEquals(setOf(""), result.unreadableFolderPaths)
    }

    @Test
    fun `nested unreadable folder keeps its full relative path`() {
        val tree = FakeTree(
            children = mapOf(
                "root" to listOf(dir("d1", "ideas")),
                "d1" to listOf(dir("d2", "2026"), file("n1", "habit.md"))
            ),
            unreadable = setOf("d2")
        )

        val result = traverseMarkdownPaths(startId = "root", listChildren = tree::list)

        assertEquals(listOf("ideas/habit.md"), result.entries.map { it.vaultRelativePath })
        assertEquals(setOf("ideas/2026"), result.unreadableFolderPaths)
    }

    @Test
    fun `excluded folders are never listed so they cannot be reported unreadable`() {
        val tree = FakeTree(
            children = mapOf("root" to listOf(dir("d1", "_ReadingTraces"), dir("d2", "ideas"))),
            unreadable = setOf("d1", "d2")
        )

        val result = traverseMarkdownPaths(
            startId = "root",
            excludeFolderNames = setOf("_ReadingTraces"),
            listChildren = tree::list
        )

        // 潜らないフォルダは列挙もしないので、失敗として数えない
        // （数えると、除外フォルダがあるだけで走査が常に不完全になる）。
        assertEquals(setOf("ideas"), result.unreadableFolderPaths)
    }

    @Test
    fun `startPath prefixes the unreadable path when traversal begins below root`() {
        val tree = FakeTree(
            children = mapOf("d1" to listOf(dir("d2", "2026"))),
            unreadable = setOf("d2")
        )

        val result = traverseMarkdownPaths(
            startId = "d1",
            startPath = "ideas",
            listChildren = tree::list
        )

        assertEquals(setOf("ideas/2026"), result.unreadableFolderPaths)
    }

    @Test
    fun `complete traversal reports no unreadable folders`() {
        val tree = FakeTree(
            mapOf(
                "root" to listOf(dir("d1", "ideas")),
                "d1" to listOf(file("n1", "habit.md"))
            )
        )

        val result = traverseMarkdownPaths(startId = "root", listChildren = tree::list)

        assertEquals(emptySet<String>(), result.unreadableFolderPaths)
    }
}

private class FakeTree(
    private val children: Map<String, List<ChildDoc>>,
    /** 列挙に失敗する documentId。空リストを返すのではなく isComplete = false を返す。 */
    private val unreadable: Set<String> = emptySet()
) {
    fun list(documentId: String): SafChildren =
        if (documentId in unreadable) {
            SafChildren.UNREADABLE
        } else {
            SafChildren.complete(children[documentId] ?: emptyList())
        }
}

private fun dir(id: String, name: String) =
    ChildDoc(documentId = id, name = name, isDirectory = true, lastModified = null)

private fun file(id: String, name: String, lastModified: Long? = null) =
    ChildDoc(documentId = id, name = name, isDirectory = false, lastModified = lastModified)
