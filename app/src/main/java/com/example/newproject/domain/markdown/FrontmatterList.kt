package com.example.newproject.domain.markdown

// frontmatter の一覧（`tags`・`aliases`・`cssclasses`）の読み方。本文の解析とノートの索引が同じ読み方を共有する
// （同じ文字列を2つが読むなら解釈器を共有する → lessons L51）。読めない書き方は空を返し、推測して読まない。

/** 本文の頭の frontmatter（`---` で囲まれた先頭の塊）の行。無ければ null。本文の頭だけを読み、本文の長さに比例しない。 */
internal fun frontmatterLines(content: String): List<String>? {
    val lines = content.lineSequence().iterator()
    // 頭の BOM は frontmatter の印ではないので外して見る。
    if (!lines.hasNext() || lines.next().removePrefix("\uFEFF").trim() != "---") return null
    val out = mutableListOf<String>()
    while (lines.hasNext()) {
        val line = lines.next()
        if (line.trim() == "---") return out
        out += line.removeSuffix("\r")
    }
    return null
}

// **確実に読める書き方だけを読み、それ以外が1つでも混ざったらキーをまるごと使わない**（空を返す）。
// 推測して読むと、事実に反するタグや欠けた別名を出す（→ features/related_notes_ai.md §5）。
//
// 読むのは、行頭から始まる `tags:` がちょうど1つあり（入れ子の行はインデントがあるので当たらない）、
// それより前の行がすべて1行で閉じていて（→ closesOnItsLine）、値が次のどちらかのとき。
// - 1行で閉じるインライン一覧 `tags: [a, "b"]`。後ろに空白に続く `#` のコメントを置ける
// - 同じ深さにインデントした `- a` の並び。空行・コメントの行・値の無い `- #メモ` は飛ばし、次のルートのキーまでを見る
// 項目の値は、引用符で始まらず YAML の別の構文にならない語か、エスケープを含まない引用符つきの語。
// タブを含む行・1語の形（`tags: a`）・インデントの無い `- a` は読まない。
internal fun frontmatterList(lines: List<String>, key: String): List<String> {
    val keyLines = lines.indices.filter { lines[it] == "$key:" || lines[it].startsWith("$key: ") }
    if (keyLines.size != 1) return emptyList()
    val keyLine = keyLines.single()
    if (!lines.take(keyLine).all(::closesOnItsLine)) return emptyList()
    val rest = lines[keyLine].substringAfter("$key:")
    val block = lines.drop(keyLine + 1).takeWhile { it.isBlank() || it.first().isWhitespace() || it.startsWith("#") }
    if ('\t' in rest || block.any { '\t' in it }) return emptyList()
    val value = rest.trim()
    val items = when {
        value.startsWith("[") -> flowListItems(value).takeIf { block.all(::isBlankOrComment) }
        value.isEmpty() || value.startsWith("#") -> blockListItems(block)
        else -> null
    }
    return items ?: emptyList()
}

private fun isBlankOrComment(line: String): Boolean = line.isBlank() || line.trimStart().startsWith("#")

// 引用符や括弧を開いたまま次の行へ続く行があると、その後ろの行頭の `tags:` も文字列や一覧の中身になる。
// 1行で閉じたと確かめられる行だけを認め、閉じたかを決められない形（エスケープ・語の途中の引用符・タグや錨の印・タブ）は
// 閉じていないものとして扱う。ルートの行は `キー:` の形に限る（ルートが辞書でなければ、行頭の `tags:` はキーではない）。
private fun closesOnItsLine(line: String): Boolean {
    if (isBlankOrComment(line)) return true
    if ('\t' in line) return false
    val atRoot = !line.first().isWhitespace()
    var node = line.trim()
    if (!atRoot) while (node == "-" || node.startsWith("- ")) node = node.drop(1).trimStart()
    val key = PLAIN_KEY.find(node)
    if (key == null && atRoot) return false
    if (key != null) node = node.substring(key.range.last + 1).trim()
    return when {
        node.isEmpty() -> true
        node.first() == '"' || node.first() == '\'' -> quotedEnd(node, 0)?.let { isListEnd(node.substring(it + 1)) } ?: false
        node.first() == '[' || node.first() == '{' -> flowClosesOnItsLine(node)
        node.first() in "!&?%@`" -> false
        // 囲まない値に `: ` が残るのは、読めなかったキー（`Alice's key: "…`）の後ろを見ている。
        else -> node.substringBefore(" #").let { ": " !in it && !it.endsWith(":") }
    }
}

// `[a, "b"]`・`{a: b}`。引用符は値の先頭（`[` `{` `,` か `: ` の後ろ）でだけ認め、
// 語の途中の引用符と一覧の中のコメントがあれば閉じていないものとして扱う。
private fun flowClosesOnItsLine(node: String): Boolean {
    val closers = ArrayDeque<Char>()
    var index = 0
    while (index < node.length) {
        when (val c = node[index]) {
            '[' -> closers.addLast(']')
            '{' -> closers.addLast('}')
            ']', '}' -> {
                if (closers.removeLastOrNull() != c) return false
                if (closers.isEmpty()) return isListEnd(node.substring(index + 1))
            }
            '"', '\'' -> {
                val before = node.substring(0, index).trimEnd()
                val startsValue = before.last() in "[{," || (before.endsWith(":") && before.length < index)
                if (!startsValue) return false
                index = quotedEnd(node, index) ?: return false
            }
            '#' -> return false
        }
        index++
    }
    return false
}

// `start` の引用符を閉じる位置。単一引用符の中の `''` は引用符そのもの。二重引用符の中のバックスラッシュは
// エスケープで、閉じる位置を決められないので null。
private fun quotedEnd(text: String, start: Int): Int? {
    val quote = text[start]
    var index = start + 1
    while (index < text.length) {
        val c = text[index]
        if (quote == '"' && c == '\\') return null
        if (c == quote) {
            if (quote == '\'' && text.getOrNull(index + 1) == '\'') {
                index += 2
                continue
            }
            return index
        }
        index++
    }
    return null
}

// `[a, "b, c"] # コメント`。引用符は項目の先頭でだけ認め、区切りの `,` と閉じる `]` は引用符の外でだけ認める
// （引用符の中の `,` `]` `#` は値の一部）。その行で閉じない一覧は読めない。一覧の中のコメントは、項目の値として読めずに落ちる。
private fun flowListItems(text: String): List<String>? {
    if (text.drop(1).trimStart().startsWith("]")) {
        return if (isListEnd(text.substring(text.indexOf(']') + 1))) emptyList() else null
    }
    val items = mutableListOf<String>()
    var index = 1
    while (true) {
        while (index < text.length && text[index] == ' ') index++
        if (index >= text.length) return null
        val first = text[index]
        if (first == '"' || first == '\'') {
            val end = text.indexOf(first, startIndex = index + 1)
            if (end < 0) return null
            items += quotedValue(text.substring(index, end + 1)) ?: return null
            index = end + 1
            while (index < text.length && text[index] == ' ') index++
        } else {
            val stop = text.indexOfAny(charArrayOf(',', ']'), startIndex = index)
            if (stop < 0) return null
            items += plainValue(text.substring(index, stop)) ?: return null
            index = stop
        }
        if (index >= text.length) return null
        when (text[index]) {
            ',' -> index++
            ']' -> return if (isListEnd(text.substring(index + 1))) items else null
            else -> return null
        }
    }
}

// 一覧を閉じた後ろに置けるのは、空白に続く `#` のコメントだけ。
private fun isListEnd(after: String): Boolean = after.isEmpty() || TRAILING_COMMENT.matches(after)

// 同じ深さにインデントした `- a` の並び。深さの違う行・項目でない行（入れ子のキー・続きの行）は読めない。
private fun blockListItems(lines: List<String>): List<String>? {
    val items = mutableListOf<String>()
    var depth: Int? = null
    for (line in lines) {
        if (isBlankOrComment(line)) continue
        val indent = line.length - line.trimStart().length
        if (depth != null && indent != depth) return null
        depth = indent
        val trimmed = line.trim()
        if (trimmed != "-" && !trimmed.startsWith("- ")) return null
        val body = trimmed.removePrefix("-").trim()
        if (body.isEmpty() || body.startsWith("#")) continue
        val value = if (body.first() == '"' || body.first() == '\'') {
            // 閉じる引用符の後ろに置けるのは行末のコメントだけ。
            val end = body.indexOf(body.first(), startIndex = 1)
            if (end < 0) return null
            val after = body.substring(end + 1)
            if (!isListEnd(after)) return null
            quotedValue(body.substring(0, end + 1))
        } else {
            plainValue(body.substringBefore(" #"))
        }
        items += value ?: return null
    }
    return items
}

// 引用符で囲んだ値。二重引用符の中のバックスラッシュは YAML のエスケープなので読まない。
private fun quotedValue(token: String): String? {
    val inner = token.substring(1, token.length - 1)
    if (token.first() == '"' && '\\' in inner) return null
    return inner.trim().ifBlank { null }
}

// 囲まない値。読めなければ null。YAML の別の構文になる先頭の文字・辞書の形（`a: b`・`a:`）・括弧・行中のコメントを含む語と、
// YAML で値が無いことを表す語（`null`・`~`。文字列ではない）は読まない。
// `C#` や `Alice's` のように語の途中の `#` と引用符は値の一部。
private fun plainValue(raw: String): String? {
    val value = raw.trim()
    if (value.isEmpty()) return null
    if (value in YAML_NULLS) return null
    if (value.first() in UNREADABLE_VALUE_START) return null
    if (value == "-" || value.startsWith("- ") || value == "?" || value.startsWith("? ")) return null
    if (": " in value || value.endsWith(":") || " #" in value) return null
    if (value.any { it in "[]{}" }) return null
    return value
}

/** 一覧や引用符の後ろに置ける行末のコメント（空白に続く `#`）。タブは読む前に断っている。 */
private val TRAILING_COMMENT = Regex("\\s+#.*")

/** 囲まない値の先頭に来ると YAML の別の構文になる文字。 */
private const val UNREADABLE_VALUE_START = "#[]{},&*!|>%@`"

/** 囲まないキーの `キー:`。記号で始まらず、引用符・括弧・カンマ・`#`・`:` を含まないものだけ。 */
private val PLAIN_KEY = Regex("""^[^\s#"'\[\]{},&*!|>%@`?:-][^#"'\[\]{},:]*:(?= |$)""")

/** YAML 1.2 の Core Schema で null になる、囲まない語。 */
private val YAML_NULLS = setOf("null", "Null", "NULL", "~")
