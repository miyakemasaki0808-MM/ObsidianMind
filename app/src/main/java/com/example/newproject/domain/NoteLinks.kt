package com.example.newproject.domain

import com.example.newproject.domain.image.normalizeVaultImagePath
import com.example.newproject.domain.image.percentDecode
import com.example.newproject.domain.markdown.InlineSpan
import com.example.newproject.domain.markdown.InlineSpanKind
import com.example.newproject.domain.markdown.MarkdownBlock
import com.example.newproject.model.DocumentRef
import com.example.newproject.model.IMAGE_FILE_EXTENSIONS
import com.example.newproject.model.NoteFile
import com.example.newproject.model.RelatedNote

// ---------------------------------------------------------------------------
// 本文のリンクを押して開く（→ features/note_links.md）。
// 記法からリンク先を読む・走査結果から引く・見出しのブロックを探す。どれも Android 型を持たない純関数。
// ---------------------------------------------------------------------------

/**
 * 本文のリンク1つが指す先。**記法だけから読んだもの**で、Vault に在るかはまだ分からない。
 * [heading] は見出しつきのリンクの見出しで、無ければ null。
 */
internal sealed interface NoteLink {
    val heading: String?

    /** `[[名前]]`・`[[フォルダ/名前]]`。名前で引く。 */
    data class ByName(val name: String, override val heading: String?) : NoteLink

    /** `[文字](パス.md)`。[path] は書かれたまま（`%20` も解かない）で、引くときに今のノートのフォルダから辿る。 */
    data class ByPath(val path: String, override val heading: String?) : NoteLink

    /** `[[#見出し]]`・`[文字](#見出し)`。同じノートの見出し。 */
    data class InNote(override val heading: String) : NoteLink
}

/**
 * [span] が押せるリンクなら、その行き先。**押せるかは記法だけで決め、Vault は見ない**（→ note_links §8 判断2）。
 *
 * 範囲は [com.example.newproject.domain.markdown.scanInlineSyntax] が決めたものをそのまま使い、記法を別に解釈しない。
 * 外部URL・添付ファイル・`.md` 以外のパスは null（押せない）。
 */
internal fun noteLinkOf(text: String, span: InlineSpan): NoteLink? = when (span.kind) {
    InlineSpanKind.WikiLink -> wikiLinkOf(text.substring(span.contentStart, span.contentEnd))
    // `[ラベル](URL)` の URL は、ラベルの閉じ `](` の後ろから最後の `)` の手前まで。
    InlineSpanKind.Link -> markdownLinkOf(text.substring(span.contentEnd + 2, span.endExclusive - 1))
    else -> null
}

/**
 * `[[行き先|表示名]]` の行き先。**表のセルでは `\|` で区切る**ので、行き先の末尾の `\` を落とす（→ 表の解析が `\|` を残す）。
 * 見出しは最後の `#` の後ろ（`#親#子` は子で引く）。`#^id` はブロック参照で、位置へは送らない。
 */
private fun wikiLinkOf(inner: String): NoteLink? {
    val target = inner.substringBefore('|').removeSuffix("\\").trim()
    val name = target.substringBefore('#').trim()
    val heading = headingOf(target.substringAfter('#', missingDelimiterValue = ""))
    return when {
        name.isEmpty() -> heading?.let { NoteLink.InNote(it) }
        isAttachmentName(name) -> null
        else -> NoteLink.ByName(name, heading)
    }
}

private fun markdownLinkOf(rawUrl: String): NoteLink? {
    val url = rawUrl.trim()
        .replace(LINK_TITLE, "")
        .let { if (it.startsWith('<') && it.endsWith('>')) it.substring(1, it.length - 1) else it }
        .trim()
    if (url.isEmpty() || URL_SCHEME.containsMatchIn(url)) return null
    val heading = headingOf(percentDecode(url.substringAfter('#', missingDelimiterValue = "")))
    val path = url.substringBefore('#')
    return when {
        path.isEmpty() -> heading?.let { NoteLink.InNote(it) }
        path.endsWith(".md", ignoreCase = true) -> NoteLink.ByPath(path, heading)
        else -> null
    }
}

private fun headingOf(fragment: String): String? =
    fragment.substringAfterLast('#').trim().takeIf { it.isNotEmpty() && !it.startsWith('^') }

private fun isAttachmentName(name: String): Boolean =
    name.substringAfterLast('/').substringAfterLast('.', missingDelimiterValue = "").lowercase() in ATTACHMENT_EXTENSIONS

/**
 * Obsidian がノートでなく添付として開く拡張子。**名前に `.` を含むノート**（`1000_0001.Python3_Basic_Exam`）があるので、
 * 「拡張子を持つか」ではなくこの一覧で分ける。
 */
private val ATTACHMENT_EXTENSIONS: Set<String> = IMAGE_FILE_EXTENSIONS + setOf(
    "pdf", "canvas", "base",
    "mp3", "wav", "m4a", "ogg", "flac", "3gp",
    "mp4", "mov", "mkv", "ogv", "webm"
)

/** スキーム（`https:`・`file:`・`mailto:` など）。**2文字以上を求める**ので、Windows のドライブ（`C:`）は含めない。 */
private val URL_SCHEME = Regex("^[a-zA-Z][a-zA-Z0-9+.\\-]+:")

/** `(パス "題")` の題。 */
private val LINK_TITLE = Regex("""\s+"[^"]*"$""")

/**
 * リンクを引くための走査結果の索引。走査を公開するたびに作り直し、Vault 切替で捨てる（→ note_links §6）。
 * **表は初めて引いたときに組む** — 走査のたびに組むと、押されないリンクのために全ノートを数え直す。
 */
internal class NoteLinkIndex(private val notes: List<NoteFile>) {
    val isEmpty: Boolean get() = notes.isEmpty()

    private val byRef by lazy { notes.associateBy { it.ref } }
    private val byPath by lazy {
        notes.filter { it.vaultRelativePath.isNotEmpty() }.associateBy { it.vaultRelativePath.lowercase() }
    }
    private val byTitle by lazy { notes.groupBy { it.name.toNormalizedObsidianTitle() } }

    fun pathOf(ref: DocumentRef): String? = byRef[ref]?.vaultRelativePath?.takeIf { it.isNotEmpty() }

    fun atPath(vaultPath: String): NoteFile? = byPath[vaultPath.lowercase()]

    fun named(title: String): List<NoteFile> = byTitle[title.toNormalizedObsidianTitle()].orEmpty()

    /** パスの末尾が [suffix]（小文字にした Vault 相対のパス）で終わるノート。 */
    fun endingWith(suffix: String): List<NoteFile> =
        byPath.filterKeys { it == suffix || it.endsWith("/$suffix") }.values.toList()

    companion object {
        val EMPTY = NoteLinkIndex(emptyList())
    }
}

/** 押したリンクを引いた結果。 */
internal sealed interface NoteLinkResolution {
    /** 別のノートを開く。見出しがあればそこから。 */
    data class Open(val note: RelatedNote, val heading: String?) : NoteLinkResolution

    /** 今のノートの中。見出しが無ければ先頭へ。 */
    data class InCurrent(val heading: String?) : NoteLinkResolution

    /** 走査結果に無かった。[label] は知らせに出す名前。 */
    data class Missing(val label: String) : NoteLinkResolution

    /** 走査結果がまだ無い。 */
    data object NotReady : NoteLinkResolution
}

/**
 * [link] を [index] から引く。[source] はリンクを押したノート（相対パスの起点と、同じノートの判定に使う）。
 *
 * **同じ名前が複数あれば1つに決める**（→ note_links §8 判断3）。画像の解決と違って選ぶのは、
 * 開いた先の題名が見えて、違っていても黙って誤らないため。
 */
internal fun resolveNoteLink(link: NoteLink, source: DocumentRef?, index: NoteLinkIndex): NoteLinkResolution {
    // 同じノートの見出しは走査結果を使わないので、まだ無くても送れる。
    if (index.isEmpty && link !is NoteLink.InNote) return NoteLinkResolution.NotReady
    val sourcePath = source?.let(index::pathOf).orEmpty()
    val found = when (link) {
        is NoteLink.InNote -> return NoteLinkResolution.InCurrent(link.heading)
        is NoteLink.ByName -> findByName(link.name, sourcePath, index)
        is NoteLink.ByPath -> findByPath(link.path, sourcePath, index)
    } ?: return NoteLinkResolution.Missing(labelOf(link))
    if (found.ref == source) return NoteLinkResolution.InCurrent(link.heading)
    return NoteLinkResolution.Open(
        RelatedNote(
            title = found.name,
            ref = found.ref,
            isWikilinked = link is NoteLink.ByName,
            lastModified = found.lastModified
        ),
        link.heading
    )
}

/** `[[フォルダ/名前]]` はパスの末尾で引き、無ければ最後の名前で引く。 */
private fun findByName(name: String, sourcePath: String, index: NoteLinkIndex): NoteFile? {
    if ('/' in name) {
        val suffix = normalizeVaultImagePath(name).lowercase().let { if (it.endsWith(".md")) it else "$it.md" }
        pickClosest(index.endingWith(suffix), sourcePath)?.let { return it }
    }
    return pickClosest(index.named(name.substringAfterLast('/')), sourcePath)
}

/** 今のノートのフォルダから辿る。`/` で始まれば Vault の根から。どちらにも無ければ名前で引く。 */
private fun findByPath(path: String, sourcePath: String, index: NoteLinkIndex): NoteFile? {
    val fromRoot = normalizeVaultImagePath(path)
    val relative = if (path.startsWith('/')) fromRoot else normalizeVaultImagePath("${folderOf(sourcePath)}/$path")
    return index.atPath(relative)
        ?: index.atPath(fromRoot)
        ?: pickClosest(index.named(fromRoot.substringAfterLast('/')), sourcePath)
}

/** 今のノートと同じフォルダのものを先に、無ければパスの短いもの、それでも並べば辞書順。 */
private fun pickClosest(candidates: List<NoteFile>, sourcePath: String): NoteFile? {
    if (candidates.size <= 1) return candidates.firstOrNull()
    val folder = folderOf(sourcePath)
    val pool = candidates.filter { folderOf(it.vaultRelativePath) == folder }.ifEmpty { candidates }
    return pool.minWith(compareBy<NoteFile>({ it.vaultRelativePath.length }, { it.vaultRelativePath }))
}

private fun folderOf(vaultPath: String): String = vaultPath.substringBeforeLast('/', missingDelimiterValue = "")

private fun labelOf(link: NoteLink): String = when (link) {
    is NoteLink.ByName -> link.name
    is NoteLink.ByPath -> percentDecode(link.path).substringAfterLast('/').removeSuffix(".md")
    is NoteLink.InNote -> link.heading
}

/**
 * [heading] の見出しのブロック番号。無ければ null。同じ見出しが複数あれば最初のもの。
 *
 * **文字と数字だけを残して比べる**（→ note_links §5）。Obsidian の見出しリンクは見出しをそのまま書き、
 * GitHub 形式のアンカーは記号を落として空白を `-` にする。どちらも残る文字と数字は見出しと同じになる。
 */
internal fun headingBlockIndex(blocks: List<MarkdownBlock>, heading: String): Int? {
    val key = headingKey(heading)
    if (key.isEmpty()) return null
    return blocks.indexOfFirst { it is MarkdownBlock.Heading && headingKey(it.text) == key }.takeIf { it >= 0 }
}

private fun headingKey(text: String): String = buildString {
    text.lowercase().forEach { if (it.isLetterOrDigit()) append(it) }
}
