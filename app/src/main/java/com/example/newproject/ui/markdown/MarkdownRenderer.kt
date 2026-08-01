package com.example.newproject.ui.markdown

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.newproject.domain.markdown.ListItem
import com.example.newproject.domain.markdown.ListMarker
import com.example.newproject.domain.markdown.MarkdownBlock
import com.example.newproject.domain.markdown.parseMarkdownBlocks
import com.example.newproject.ui.theme.LinkText
import com.example.newproject.ui.theme.CheckboxOutline
import com.example.newproject.ui.theme.ContentDivider
import com.example.newproject.ui.theme.OnSurfaceFaint
import com.example.newproject.ui.theme.OnSurfaceMuted
import com.example.newproject.ui.theme.OnSurfaceSubtle
import com.example.newproject.ui.theme.CodePanel
import com.example.newproject.ui.theme.OnSurface

@Composable
internal fun MarkdownNoteContent(
    content: String,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    // セクションモデル側で既にパース済みなら渡して再パースを避ける
    precomputedBlocks: List<MarkdownBlock>? = null
) {
    val blocks = remember(content, precomputedBlocks) {
        precomputedBlocks ?: parseMarkdownBlocks(content)
    }

    SelectionContainer(modifier = modifier) {
        LazyColumn(
            state = listState,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(blocks.size) { i ->
                when (val block = blocks[i]) {
                    is MarkdownBlock.Heading       -> MarkdownHeading(block)
                    is MarkdownBlock.Paragraph     -> MarkdownParagraph(block.text)
                    is MarkdownBlock.ListBlock     -> MarkdownList(block.items)
                    is MarkdownBlock.CodeBlock     -> MarkdownCodeBlock(block.code)
                    is MarkdownBlock.HorizontalRule -> MarkdownHorizontalRule()
                    is MarkdownBlock.Blockquote    -> MarkdownBlockquote(block.lines)
                    is MarkdownBlock.Table         -> MarkdownTable(block.headers, block.rows)
                }
            }
        }
    }
}

// inlineMarkdown（AnnotatedString構築）は軽くないため、再コンポジションの
// たびに作り直さないようテキスト単位でメモ化する。
@Composable
private fun rememberInline(text: String): AnnotatedString {
    // 色はテーマ由来なので、非Composableな remember ブロックの外で読んでキーに含める。
    val colors = InlineMarkdownColors(
        strikethrough = OnSurfaceFaint,
        codeBackground = CodePanel,
        link = LinkText
    )
    return remember(text, colors) { inlineMarkdown(text, colors) }
}

@Composable
internal fun MarkdownHeading(block: MarkdownBlock.Heading) {
    val size = when (block.level) {
        1 -> 24.sp
        2 -> 21.sp
        3 -> 19.sp
        4 -> 17.sp
        5 -> 15.sp
        else -> 14.sp
    }
    val style = if (block.level >= 6) FontStyle.Italic else FontStyle.Normal

    Text(
        text = rememberInline(block.text),
        color = if (block.level >= 5) OnSurfaceMuted else OnSurface,
        fontSize = size,
        lineHeight = (size.value + 6).sp,
        fontWeight = FontWeight.Bold,
        fontStyle = style,
        modifier = Modifier.padding(top = if (block.level <= 2) 8.dp else 4.dp)
    )
}

@Composable
internal fun MarkdownHorizontalRule() {
    HorizontalDivider(
        color = ContentDivider,
        thickness = 1.dp,
        modifier = Modifier.padding(vertical = 8.dp)
    )
}

@Composable
internal fun MarkdownBlockquote(lines: List<String>) {
    // バーの高さはテキスト側に追従させる。以前は行数×固定値で計算しており、
    // 長い行が画面幅で折り返すとバーがテキストより短くなっていた。
    Row(
        modifier = Modifier
            .padding(vertical = 4.dp)
            .height(IntrinsicSize.Min)
    ) {
        Box(
            modifier = Modifier
                .width(4.dp)
                .fillMaxHeight()
                .background(CheckboxOutline, RoundedCornerShape(2.dp))
        )
        Column(modifier = Modifier.padding(start = 12.dp)) {
            lines.forEach { line ->
                Text(
                    text = rememberInline(line),
                    color = OnSurfaceSubtle,
                    fontSize = 16.sp,
                    lineHeight = 24.sp,
                    fontStyle = FontStyle.Italic
                )
            }
        }
    }
}

@Composable
internal fun MarkdownTable(headers: List<String>, rows: List<List<String>>) {
    val borderColor = ContentDivider
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, borderColor, RoundedCornerShape(4.dp))
    ) {
        Row(modifier = Modifier.background(CodePanel)) {
            headers.forEachIndexed { i, header ->
                Text(
                    text = rememberInline(header.trim()),
                    color = OnSurface,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .weight(1f)
                        .then(if (i > 0) Modifier.border(width = 1.dp, color = borderColor) else Modifier)
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                )
            }
        }
        HorizontalDivider(color = borderColor, thickness = 1.dp)
        rows.forEach { row ->
            Row {
                val padded = if (row.size < headers.size) row + List(headers.size - row.size) { "" } else row
                padded.take(headers.size).forEachIndexed { i, cell ->
                    Text(
                        text = rememberInline(cell.trim()),
                        color = OnSurface,
                        fontSize = 14.sp,
                        modifier = Modifier
                            .weight(1f)
                            .then(if (i > 0) Modifier.border(width = 1.dp, color = borderColor) else Modifier)
                            .padding(horizontal = 8.dp, vertical = 6.dp)
                    )
                }
            }
            HorizontalDivider(color = borderColor, thickness = 0.5.dp)
        }
    }
}

@Composable
internal fun MarkdownParagraph(text: String) {
    Text(
        text = rememberInline(text),
        color = OnSurface,
        fontSize = 16.sp,
        lineHeight = 24.sp
    )
}

/** \u5165\u308c\u5b501\u6bb5\u3042\u305f\u308a\u306e\u5b57\u4e0b\u3052\u5e45\u3002\u6df1\u3055\u306f\u5b57\u4e0b\u3052\u3060\u3051\u3067\u793a\u3057\u3001\u8a18\u53f7\u306f\u6bb5\u306b\u3088\u3089\u305a\u5909\u3048\u306a\u3044\u3002 */
private val ListIndentPerDepth = 16.dp

@Composable
internal fun MarkdownList(items: List<ListItem>) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items.forEach { item ->
            val checked = item.checked
            Row(
                modifier = Modifier.padding(start = ListIndentPerDepth * item.depth),
                verticalAlignment = if (checked == null) Alignment.Top else Alignment.CenterVertically
            ) {
                if (checked == null) {
                    Text(
                        // \u756a\u53f7\u4ed8\u304d\u306f\u539f\u6587\u306e\u8868\u8a18\u3092\u305d\u306e\u307e\u307e\u51fa\u3059\u3002\u81ea\u52d5\u63a1\u756a\u3059\u308b\u3068 `1. 1. 1.` \u3068
                        // \u66f8\u304b\u308c\u305f\u30ce\u30fc\u30c8\u3067\u8868\u793a\u304c\u539f\u6587\u3068\u98df\u3044\u9055\u3046\uff08\u672c\u30a2\u30d7\u30ea\u306f\u7de8\u96c6\u5668\u3067\u306f\u306a\u3044\uff09\u3002
                        text = when (val marker = item.marker) {
                            is ListMarker.Bullet -> "\u2022"
                            is ListMarker.Ordered -> "${marker.number}${marker.delimiter}"
                        },
                        color = OnSurface,
                        fontSize = 16.sp,
                        lineHeight = 24.sp
                    )
                } else {
                    Checkbox(
                        checked = checked,
                        onCheckedChange = null,
                        modifier = Modifier
                            .width(20.dp)
                            .height(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = rememberInline(item.text),
                    color = if (checked == true) OnSurfaceFaint else OnSurface,
                    fontSize = 16.sp,
                    lineHeight = 24.sp,
                    textDecoration = if (checked == true) TextDecoration.LineThrough else TextDecoration.None,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
internal fun MarkdownCodeBlock(code: String) {
    Surface(
        color = CodePanel,
        shape = RoundedCornerShape(6.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = code.trimEnd(),
            color = OnSurface,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(12.dp)
        )
    }
}
