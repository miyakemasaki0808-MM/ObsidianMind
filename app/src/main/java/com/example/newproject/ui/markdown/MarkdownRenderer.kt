package com.example.newproject.ui.markdown

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Divider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.newproject.ui.theme.CodePanel
import com.example.newproject.ui.theme.OnSurface

@Composable
internal fun MarkdownNoteContent(content: String, modifier: Modifier = Modifier) {
    val blocks = remember(content) { parseMarkdownBlocks(content) }

    SelectionContainer(modifier = modifier) {
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            blocks.forEach { block ->
                when (block) {
                    is MarkdownBlock.Heading       -> MarkdownHeading(block)
                    is MarkdownBlock.Paragraph     -> MarkdownParagraph(block.text)
                    is MarkdownBlock.ListBlock     -> MarkdownList(block.items)
                    is MarkdownBlock.CodeBlock     -> MarkdownCodeBlock(block.code)
                    is MarkdownBlock.HorizontalRule -> MarkdownHorizontalRule()
                    is MarkdownBlock.Blockquote    -> MarkdownBlockquote(block.lines)
                    is MarkdownBlock.TaskListBlock -> MarkdownTaskList(block.items)
                    is MarkdownBlock.Table         -> MarkdownTable(block.headers, block.rows)
                }
            }
        }
    }
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
        text = inlineMarkdown(block.text),
        color = if (block.level >= 5) Color(0xFF555555) else OnSurface,
        fontSize = size,
        lineHeight = (size.value + 6).sp,
        fontWeight = FontWeight.Bold,
        fontStyle = style,
        modifier = Modifier.padding(top = if (block.level <= 2) 8.dp else 4.dp)
    )
}

@Composable
internal fun MarkdownHorizontalRule() {
    Divider(
        color = Color(0xFFCCCCCC),
        thickness = 1.dp,
        modifier = Modifier.padding(vertical = 8.dp)
    )
}

@Composable
internal fun MarkdownBlockquote(lines: List<String>) {
    Row(modifier = Modifier.padding(vertical = 4.dp)) {
        Box(
            modifier = Modifier
                .width(4.dp)
                .height(with(LocalDensity.current) { (lines.size * 24).dp })
                .background(Color(0xFFAAAAAA), RoundedCornerShape(2.dp))
        )
        Column(modifier = Modifier.padding(start = 12.dp)) {
            lines.forEach { line ->
                Text(
                    text = inlineMarkdown(line),
                    color = Color(0xFF666666),
                    fontSize = 16.sp,
                    lineHeight = 24.sp,
                    fontStyle = FontStyle.Italic
                )
            }
        }
    }
}

@Composable
internal fun MarkdownTaskList(items: List<Pair<Boolean, String>>) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items.forEach { (checked, text) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = checked,
                    onCheckedChange = null,
                    modifier = Modifier
                        .width(20.dp)
                        .height(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = inlineMarkdown(text),
                    color = if (checked) Color(0xFF888888) else OnSurface,
                    fontSize = 16.sp,
                    lineHeight = 24.sp,
                    textDecoration = if (checked) TextDecoration.LineThrough else TextDecoration.None,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
internal fun MarkdownTable(headers: List<String>, rows: List<List<String>>) {
    val borderColor = Color(0xFFCCCCCC)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, borderColor, RoundedCornerShape(4.dp))
    ) {
        Row(modifier = Modifier.background(Color(0xFFF1F4F8))) {
            headers.forEachIndexed { i, header ->
                Text(
                    text = inlineMarkdown(header.trim()),
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
        Divider(color = borderColor, thickness = 1.dp)
        rows.forEach { row ->
            Row {
                val padded = if (row.size < headers.size) row + List(headers.size - row.size) { "" } else row
                padded.take(headers.size).forEachIndexed { i, cell ->
                    Text(
                        text = inlineMarkdown(cell.trim()),
                        color = OnSurface,
                        fontSize = 14.sp,
                        modifier = Modifier
                            .weight(1f)
                            .then(if (i > 0) Modifier.border(width = 1.dp, color = borderColor) else Modifier)
                            .padding(horizontal = 8.dp, vertical = 6.dp)
                    )
                }
            }
            Divider(color = borderColor, thickness = 0.5.dp)
        }
    }
}

@Composable
internal fun MarkdownParagraph(text: String) {
    Text(
        text = inlineMarkdown(text),
        color = OnSurface,
        fontSize = 16.sp,
        lineHeight = 24.sp
    )
}

@Composable
internal fun MarkdownList(items: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items.forEach { item ->
            Row {
                Text(
                    text = "\u2022",
                    color = OnSurface,
                    fontSize = 16.sp,
                    lineHeight = 24.sp
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = inlineMarkdown(item),
                    color = OnSurface,
                    fontSize = 16.sp,
                    lineHeight = 24.sp,
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
