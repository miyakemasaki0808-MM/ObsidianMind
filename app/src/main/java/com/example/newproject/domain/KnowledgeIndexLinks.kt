package com.example.newproject.domain

import com.example.newproject.domain.image.normalizeVaultImagePath
import com.example.newproject.domain.markdown.InlineSpan
import com.example.newproject.domain.markdown.InlineSpanKind
import com.example.newproject.domain.markdown.MarkdownBlock
import com.example.newproject.domain.markdown.inlineVisibleText
import com.example.newproject.domain.markdown.parseMarkdownBlocks
import com.example.newproject.domain.markdown.renderedRows
import com.example.newproject.domain.markdown.scanInlineSyntax
import java.security.MessageDigest

// ---------------------------------------------------------------------------
// 知識索引に入れるリンクを本文から取り出す（→ docs/dev/system/knowledge_index.md §3・§6）。
// どれも Android 型を持たない純関数。本文に比例するので、呼ぶ側が Main の外で呼ぶ。
// ---------------------------------------------------------------------------

internal object KnowledgeIndexLimits {
    /** 1つのノートから取るリンクの数。超えた分は数えない。 */
    const val MAX_LINKS_PER_NOTE = 500

    /** 添える1文の長さ。超えたら末尾を「…」で切る。 */
    const val MAX_SENTENCE_CHARACTERS = 160
}

internal enum class IndexedLinkKind { NAME, PATH }

/**
 * 索引に入れる1つのリンク。**行き先は書かれたまま持ち、索引の時点で解かない**
 * （同じ名前のノートが増減すると行き先が変わるため → knowledge_index.md §6）。
 *
 * @property ordinal 本文の中の順番（0 から）
 * @property heading リンク自身の見出し（`[[名前#見出し]]` の見出し）
 * @property sectionHeading リンクのある節の見出し。見出しより前なら null
 * @property sectionHeadingOccurrence 出どころの中で、同じ見出し（[headingMatchKey] で照合）のうち何番目か（0 から）
 * @property keys 候補を絞る名前の鍵（[linkKeysOf]）
 */
internal data class IndexedLink(
    val ordinal: Int,
    val kind: IndexedLinkKind,
    val target: String,
    val heading: String?,
    val sectionHeading: String?,
    val sectionHeadingOccurrence: Int,
    val sentence: String,
    val keys: List<String>
) {
    /** 本文のリンクと同じ規則（[resolveNoteLink]）で解くための形。 */
    fun toNoteLink(): NoteLink = when (kind) {
        IndexedLinkKind.NAME -> NoteLink.ByName(target, heading)
        IndexedLinkKind.PATH -> NoteLink.ByPath(target, heading)
    }
}

/**
 * [content] から、本文で押せるリンクを取り出す。**押せるかの判定は本文のリンクと同じ**
 * （[parseMarkdownBlocks] と [scanInlineSyntax] と [noteLinkOf]）なので、コードの中・画像・外部URL・同じノートの見出しは入らない。
 */
internal fun extractIndexedLinks(content: String): List<IndexedLink> {
    val links = mutableListOf<IndexedLink>()
    val headingCounts = HashMap<String, Int>()
    var section: String? = null
    var occurrence = 0

    fun collect(text: String) {
        if (links.size >= KnowledgeIndexLimits.MAX_LINKS_PER_NOTE) return
        val scan = scanInlineSyntax(text)
        scan.flatten()
            .filter { it.kind == InlineSpanKind.WikiLink || it.kind == InlineSpanKind.Link }
            .forEach { span ->
                if (links.size >= KnowledgeIndexLimits.MAX_LINKS_PER_NOTE) return
                val link = noteLinkOf(text, span) ?: return@forEach
                val (kind, target) = when (link) {
                    is NoteLink.ByName -> IndexedLinkKind.NAME to link.name
                    is NoteLink.ByPath -> IndexedLinkKind.PATH to link.path
                    is NoteLink.InNote -> return@forEach
                }
                links += IndexedLink(
                    ordinal = links.size,
                    kind = kind,
                    target = target,
                    heading = link.heading,
                    sectionHeading = section,
                    sectionHeadingOccurrence = occurrence,
                    sentence = sentenceAround(text, scan.spans, span),
                    keys = linkKeysOf(link)
                )
            }
    }

    parseMarkdownBlocks(content).forEach { block ->
        when (block) {
            is MarkdownBlock.Heading -> {
                val key = headingMatchKey(block.text)
                occurrence = headingCounts[key] ?: 0
                headingCounts[key] = occurrence + 1
                section = block.text
                collect(block.text)
            }
            is MarkdownBlock.Paragraph -> collect(block.text)
            is MarkdownBlock.ListBlock -> block.items.forEach { collect(it.text) }
            is MarkdownBlock.Blockquote -> block.lines.forEach { collect(it) }
            // 描かれるセルだけ。見出しの列より多いセルは描画が捨てるので数えない。
            is MarkdownBlock.Table -> (block.headers + block.renderedRows().flatten()).forEach { collect(it) }
            is MarkdownBlock.CodeBlock, is MarkdownBlock.Image, MarkdownBlock.HorizontalRule -> Unit
        }
    }
    return links
}

/**
 * 候補を絞る名前の鍵。**解決の規則から導く**（→ knowledge_index.md §6）。
 *
 * 解決は、パスのリンクと `[[フォルダ/名前]]` を復号してから引き、名前へ戻るときは書かれたままの末尾で引く。
 * そこで、書かれたままの末尾と、[normalizeVaultImagePath] を通した末尾の両方に名前の正規化をかける。
 * 一律に復号すると `100%` のように文字としての `%` を含む名前を落とす。**多めに拾っても、最後に [resolveNoteLink] で絞る。**
 */
internal fun linkKeysOf(link: NoteLink): List<String> {
    val written = when (link) {
        is NoteLink.ByName -> link.name
        is NoteLink.ByPath -> link.path
        is NoteLink.InNote -> return emptyList()
    }
    return listOf(
        written.substringAfterLast('/').toNormalizedObsidianTitle(),
        normalizeVaultImagePath(written).substringAfterLast('/').toNormalizedObsidianTitle()
    ).filter { it.isNotEmpty() }.distinct()
}

/** ノートの名前の鍵。リンクの鍵と同じ正規化（解決が名前で引く表と同じ）。 */
internal fun noteKeyOf(noteName: String): String = noteName.toNormalizedObsidianTitle()

/** 本文の指紋。更新日時を返さないプロバイダで、読み直した本文が変わったかを見分ける。 */
internal fun contentFingerprint(content: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(content.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

/**
 * [link] を含む1文を、本文に見える文字で返す。文の切れ目は [splitIntoSentences] に従い、
 * **記法（リンク・コード）の内側で切れる切れ目は使わない** — 切ると記法が割れて、見える文字が変わる。
 */
private fun sentenceAround(text: String, spans: List<InlineSpan>, link: InlineSpan): String {
    val ends = mutableListOf<Int>()
    var offset = 0
    splitIntoSentences(text).forEach { piece ->
        offset += piece.length
        ends += offset
    }
    val boundaries = ends.filter { end -> spans.none { it.start < end && end < it.endExclusive } }
    val start = boundaries.lastOrNull { it <= link.start } ?: 0
    val end = boundaries.firstOrNull { it > link.start } ?: text.length
    val sentence = inlineVisibleText(text.substring(start, end.coerceAtMost(text.length))).trim()
    return if (sentence.length <= KnowledgeIndexLimits.MAX_SENTENCE_CHARACTERS) {
        sentence
    } else {
        sentence.take(KnowledgeIndexLimits.MAX_SENTENCE_CHARACTERS - 1).trimEnd() + "…"
    }
}
