package com.example.newproject.domain

import com.example.newproject.domain.markdown.MarkdownBlock
import com.example.newproject.domain.markdown.scanInlineSyntax
import com.example.newproject.model.DocumentRef
import com.example.newproject.model.NoteFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 本文のリンクを押して開く（→ features/note_links.md）。記法の分け方・引き方・見出しの照合。
 *
 * 入力は Vault で数えたリンクの型を写している — 表の中の `\|`・名前に `.` を含むノート・相対の `.md`・
 * `%20`・GitHub 形式の目次のアンカー・`#` を付けた見出しリンク・添付・外部URL。
 */
class NoteLinksTest {

    private fun linkIn(text: String): NoteLink? = noteLinkOf(text, scanInlineSyntax(text).spans.single())

    /**
     * `[x](パス)` を組む。**`../` で始まるパスをソースに直接書かない** — KDoc の相対リンクの検査（`SourceDocSyncTest`）が
     * 実在するファイルへのリンクとして数える。
     */
    private fun md(path: String, label: String = "x") = "[$label]($path)"

    private fun note(path: String) = NoteFile(
        name = path.substringAfterLast('/'),
        ref = DocumentRef("ref:$path"),
        vaultRelativePath = path
    )

    private val cooking = note("0E00.Cooking.md")
    private val bento = note("0E00_Cooking/0E00_0001.Plan_Bento.md")
    private val templateInDocs = note("Kotlin/docs/dev/features/_template.md")
    private val templateAtRoot = note("_template.md")
    private val readmeAtRoot = note("README.md")
    private val readmeInSystem = note("Kotlin/docs/dev/system/README.md")
    private val bookletCases = note("Kotlin/docs/review/device_validation/booklet_mode.md")
    private val bookletDesign = note("Kotlin/docs/dev/features/booklet_mode.md")
    private val adr = note("Kotlin/docs/dev/decisions/ADR-0004.md")
    private val architecture = note("Kotlin/docs/dev/system/architecture.md")
    private val spaced = note("notes/my note.md")
    private val shortDuplicate = note("zz/dup.md")
    private val longDuplicate = note("aa/bb/dup.md")

    private val index = NoteLinkIndex(
        listOf(
            cooking, bento, templateInDocs, templateAtRoot, readmeAtRoot, readmeInSystem,
            bookletCases, bookletDesign, adr, architecture, spaced, shortDuplicate, longDuplicate
        )
    )

    private fun resolve(text: String, from: NoteFile?): NoteLinkResolution =
        resolveNoteLink(linkIn(text)!!, from?.ref, index)

    private fun opened(text: String, from: NoteFile?): NoteFile? =
        (resolve(text, from) as? NoteLinkResolution.Open)?.let { open ->
            index.atPath(index.pathOf(open.note.ref)!!)
        }

    // --- 記法の分け方 --------------------------------------------------------

    @Test
    fun `Obsidianリンクは名前で引き、表示名と表の中の縦棒の区切りを外す`() {
        assertEquals(NoteLink.ByName("0E00_0001.Plan_Bento", null), linkIn("[[0E00_0001.Plan_Bento]]"))
        assertEquals(NoteLink.ByName("ノート", null), linkIn("[[ノート|表示名]]"))
        assertEquals(NoteLink.ByName("0E00_0001.Plan_Bento", null), linkIn("[[0E00_0001.Plan_Bento\\|0E00_0001]]"))
        assertEquals(NoteLink.ByName("フォルダ/名前", null), linkIn("[[フォルダ/名前]]"))
    }

    @Test
    fun `名前に点を含むノートは添付と見なさない`() {
        assertEquals(NoteLink.ByName("1000_0001.Python3_Basic_Exam", null), linkIn("[[1000_0001.Python3_Basic_Exam]]"))
    }

    @Test
    fun `添付の拡張子を持つ名前は押せない`() {
        assertNull(linkIn("[[O_Reilly_AI_Engineering.png|300x400]]"))
        assertNull(linkIn("[[資料.pdf]]"))
        assertNull(linkIn("[[図/構成.canvas]]"))
    }

    @Test
    fun `見出しは最後の井桁の後ろで、ブロック参照は見出しにしない`() {
        assertEquals(NoteLink.ByName("ノート", "見出し"), linkIn("[[ノート#見出し]]"))
        assertEquals(NoteLink.ByName("ノート", "子"), linkIn("[[ノート#親#子]]"))
        assertEquals(NoteLink.ByName("ノート", null), linkIn("[[ノート#^abc123]]"))
        assertEquals(NoteLink.InNote("🧩 pickle（オブジェクトの直列化）"), linkIn("[[#🧩 pickle（オブジェクトの直列化）]]"))
        assertNull(linkIn("[[#^abc123]]"))
    }

    @Test
    fun `mdへのリンクはパスのまま持ち、見出しは解いて持つ`() {
        assertEquals(NoteLink.ByPath("../decisions/ADR-0004.md", null), linkIn(md("../decisions/ADR-0004.md", label = "ADR")))
        assertEquals(NoteLink.ByPath("my%20note.md", null), linkIn("[x](my%20note.md)"))
        assertEquals(
            NoteLink.ByPath("lessons.md", "l14-横展開は最後の1本を取り残す"),
            linkIn("[L14](lessons.md#l14-横展開は最後の1本を取り残す)")
        )
        assertEquals(NoteLink.ByPath("a.md", "見出し 1"), linkIn("[x](a.md#見出し%201)"))
        assertEquals(NoteLink.ByPath("my note.md", null), linkIn("[x](<my note.md>)"))
        assertEquals(NoteLink.ByPath("note.md", null), linkIn("[x](note.md \"題\")"))
    }

    @Test
    fun `井桁だけのリンクは同じノートの見出し`() {
        assertEquals(
            NoteLink.InNote("全体入口開発規約-core--governance"),
            linkIn("[全体入口・開発規約 (Core & Governance)](#全体入口開発規約-core--governance)")
        )
        assertNull(linkIn("[x](#)"))
    }

    @Test
    fun `スキームを持つものとmd以外のパスは押せない`() {
        assertNull(linkIn("[x](https://example.com/a.md)"))
        assertNull(linkIn("[x](file:///c:/Users/a/main.py)"))
        assertNull(linkIn("[x](mailto:a@example.com)"))
        assertNull(linkIn("[x](obsidian://open?vault=v&file=a.md)"))
        assertNull(linkIn(md("../../app/src/main/Foo.kt")))
        assertNull(linkIn("[x](docs/)"))
    }

    @Test
    fun `リンクでない記法は押せない`() {
        assertNull(linkIn("**太字**"))
        assertNull(linkIn("`[[コード]]`"))
    }

    // --- 引き方 -------------------------------------------------------------

    @Test
    fun `名前は大文字小文字とmdを区別せずに引く`() {
        assertEquals(bento, opened("[[0E00_0001.Plan_Bento]]", cooking))
        assertEquals(bento, opened("[[0e00_0001.plan_bento.md]]", cooking))
    }

    @Test
    fun `見出しと名前で引いたかを開く先へ渡す`() {
        val open = resolve("[[0E00_0001.Plan_Bento#材料]]", cooking) as NoteLinkResolution.Open
        assertEquals("材料", open.heading)
        assertTrue(open.note.isWikilinked)
        val byPath = resolve("[x](0E00_Cooking/0E00_0001.Plan_Bento.md)", cooking) as NoteLinkResolution.Open
        assertEquals(false, byPath.note.isWikilinked)
    }

    /** パスの長さでは負ける側を選ぶ — 同じフォルダを先に見ていなければ、根の `_template.md` が選ばれる。 */
    @Test
    fun `同じ名前は同じフォルダのものを先に選ぶ`() {
        assertEquals(templateInDocs, opened("[[_template]]", bookletDesign))
        assertEquals(templateAtRoot, opened("[[_template]]", cooking))
    }

    @Test
    fun `同じフォルダに無ければパスの短いものを選ぶ`() {
        assertEquals(bookletDesign, opened("[[booklet_mode]]", cooking))
        // 辞書順なら先に来る長いパスより、短いパスを選ぶ。
        assertEquals(shortDuplicate, opened("[[dup]]", cooking))
    }

    @Test
    fun `フォルダつきの名前はパスの末尾で引く`() {
        assertEquals(bookletCases, opened("[[device_validation/booklet_mode]]", cooking))
        // 末尾が合わなければ最後の名前で引く。
        assertEquals(bookletDesign, opened("[[どこか/booklet_mode]]", cooking))
    }

    /** 名前で引くと同じフォルダの `booklet_mode.md` になる組で、辿った先が選ばれることを見る。 */
    @Test
    fun `mdへのリンクは今のノートのフォルダから辿る`() {
        assertEquals(adr, opened(md("../decisions/ADR-0004.md"), architecture))
        assertEquals(bookletCases, opened(md("../../review/device_validation/booklet_mode.md"), bookletDesign))
        assertEquals(readmeInSystem, opened("[x](README.md)", architecture))
    }

    @Test
    fun `斜線で始まるパスはVaultの根から引く`() {
        assertEquals(readmeAtRoot, opened("[x](/README.md)", architecture))
    }

    @Test
    fun `辿って無ければVaultの根から、それでも無ければ名前で引く`() {
        assertEquals(bookletCases, opened("[x](Kotlin/docs/review/device_validation/booklet_mode.md)", architecture))
        assertEquals(adr, opened("[x](ADR-0004.md)", cooking))
    }

    @Test
    fun `パスの百分率の符号を解いて引く`() {
        assertEquals(spaced, opened("[x](notes/my%20note.md)", cooking))
    }

    @Test
    fun `今のノートの相対パスが分からなければ根から辿る`() {
        assertEquals(adr, resolveNoteLink(linkIn("[x](Kotlin/docs/dev/decisions/ADR-0004.md)")!!, null, index).let {
            index.atPath(index.pathOf((it as NoteLinkResolution.Open).note.ref)!!)
        })
    }

    @Test
    fun `無ければ書かれた名前で知らせる`() {
        assertEquals(NoteLinkResolution.Missing("まだ無いノート"), resolve("[[まだ無いノート]]", cooking))
        assertEquals(NoteLinkResolution.Missing("Foo Bar"), resolve(md("../nothing/Foo%20Bar.md"), cooking))
    }

    @Test
    fun `今のノートへのリンクは本文の中へ送る`() {
        assertEquals(NoteLinkResolution.InCurrent("材料"), resolve("[[0E00_0001.Plan_Bento#材料]]", bento))
        assertEquals(NoteLinkResolution.InCurrent(null), resolve("[[0E00_0001.Plan_Bento]]", bento))
    }

    @Test
    fun `同じノートの見出しは走査結果が無くても送れる`() {
        assertEquals(
            NoteLinkResolution.InCurrent("見出し"),
            resolveNoteLink(NoteLink.InNote("見出し"), cooking.ref, NoteLinkIndex.EMPTY)
        )
    }

    @Test
    fun `走査結果が無ければ引けないと返す`() {
        assertEquals(
            NoteLinkResolution.NotReady,
            resolveNoteLink(NoteLink.ByName("0E00.Cooking", null), cooking.ref, NoteLinkIndex.EMPTY)
        )
    }

    // --- 見出しの照合 ---------------------------------------------------------

    private val blocks = listOf(
        MarkdownBlock.Heading(2, "🧩 pickle（オブジェクトの直列化）"),
        MarkdownBlock.Paragraph("全体入口・開発規約 (Core & Governance)"),
        MarkdownBlock.Heading(2, "全体入口・開発規約 (Core & Governance)"),
        MarkdownBlock.Heading(3, "Owner & Analysis - `docs/owner/`"),
        MarkdownBlock.Heading(2, "重複"),
        MarkdownBlock.Heading(2, "重複")
    )

    @Test
    fun `Obsidianの見出しリンクは見出しをそのまま書いたもので当たる`() {
        assertEquals(0, headingBlockIndex(blocks, "🧩 pickle（オブジェクトの直列化）"))
    }

    @Test
    fun `GitHub形式のアンカーも文字と数字だけで当たる`() {
        assertEquals(2, headingBlockIndex(blocks, "全体入口開発規約-core--governance"))
        assertEquals(3, headingBlockIndex(blocks, "owner--analysis---docsowner"))
    }

    @Test
    fun `同じ見出しが複数あれば最初のもの`() {
        assertEquals(4, headingBlockIndex(blocks, "重複"))
    }

    @Test
    fun `無い見出しと文字の残らない見出しは当たらない`() {
        assertNull(headingBlockIndex(blocks, "無い見出し"))
        assertNull(headingBlockIndex(blocks, "🧩"))
    }
}
