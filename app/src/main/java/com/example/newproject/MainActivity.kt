package com.example.newproject

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

private val Indigo = Color(0xFF4D3DFF)
private val Aqua = Color(0xFF00C2FF)
private val Coral = Color(0xFFFF6B8A)
private val OnVibrant = Color.White
private val OnVibrantMuted = Color(0xFFEAF7FF)
private val OnSurface = Color(0xFF202124)
private val Panel = Color(0xFFFDFEFF)
private val CodePanel = Color(0xFFF1F4F8)
private val LinkBlue = Color(0xFF2563EB)
private val ButtonPrimary = Color(0xFFFF3D71)
private val ButtonSecondary = Color(0xFF16B8A6)

private val HeadingRegex = Regex("^(#{1,6})\\s+(.+)$")
private val UnorderedListRegex = Regex("^\\s*[-*+]\\s+(.+)$")
private val OrderedListRegex = Regex("^\\s*\\d+[.)]\\s+(.+)$")

class MainActivity : ComponentActivity() {

    private val viewModel: NoteViewModel by viewModels()

    private val openVault = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri ?: return@registerForActivityResult
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        contentResolver.takePersistableUriPermission(uri, flags)
        viewModel.saveVault(uri)
        viewModel.loadRandomNote(contentResolver)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            RandomNoteScreen(
                uiState = uiState,
                onSelectVault = { openVault.launch(null) },
                onRandomNote = {
                    if (viewModel.vaultUri != null) viewModel.loadRandomNote(contentResolver)
                    else openVault.launch(null)
                }
            )
        }
    }
}

@Composable
fun RandomNoteScreen(
    uiState: NoteUiState,
    onSelectVault: () -> Unit,
    onRandomNote: () -> Unit
) {
    val context = LocalContext.current

    LaunchedEffect((uiState.noteState as? NoteState.Error)?.id) {
        if (uiState.noteState is NoteState.Error) {
            Toast.makeText(context, uiState.noteState.message, Toast.LENGTH_SHORT).show()
        }
    }

    val gradient = Brush.linearGradient(
        colors = listOf(Indigo, Aqua, Coral),
        start = Offset(0f, Float.POSITIVE_INFINITY),
        end = Offset(Float.POSITIVE_INFINITY, 0f)
    )

    val isLoading = uiState.noteState is NoteState.Loading

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(gradient)
            .padding(24.dp)
    ) {
        Text(
            text = stringResource(R.string.random_note_title),
            color = OnVibrant,
            fontSize = 30.sp,
            fontWeight = FontWeight.Bold
        )

        Text(
            text = stringResource(
                if (uiState.vaultSelected) R.string.vault_selected
                else R.string.vault_not_selected
            ),
            color = OnVibrantMuted,
            fontSize = 14.sp,
            modifier = Modifier.padding(top = 10.dp)
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                onClick = onSelectVault,
                modifier = Modifier.weight(1f).height(48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = ButtonSecondary),
                shape = RoundedCornerShape(24.dp)
            ) {
                Text(stringResource(R.string.select_vault), color = OnVibrant)
            }
            Button(
                onClick = onRandomNote,
                enabled = !isLoading,
                modifier = Modifier.weight(1f).height(48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = ButtonPrimary),
                shape = RoundedCornerShape(24.dp)
            ) {
                Text(stringResource(R.string.show_random_note), color = OnVibrant)
            }
        }

        if (isLoading) {
            CircularProgressIndicator(
                color = OnVibrant,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(top = 16.dp)
            )
        }

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(top = if (isLoading) 8.dp else 24.dp),
            color = Panel,
            shape = RoundedCornerShape(8.dp)
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                val (noteTitle, noteContent) = when (val state = uiState.noteState) {
                    is NoteState.Success -> state.title to state.content
                    is NoteState.Empty   -> stringResource(R.string.no_note_loaded) to stringResource(R.string.no_markdown_notes)
                    is NoteState.Error   -> stringResource(R.string.no_note_loaded) to stringResource(R.string.vault_read_error)
                    else                 -> stringResource(R.string.no_note_loaded) to stringResource(R.string.random_note_empty_state)
                }

                Text(
                    text = noteTitle,
                    color = OnSurface,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )

                MarkdownNoteContent(
                    content = noteContent,
                    modifier = Modifier
                        .padding(top = 12.dp)
                        .weight(1f)
                )
            }
        }
    }
}

@Composable
private fun MarkdownNoteContent(content: String, modifier: Modifier = Modifier) {
    val blocks = remember(content) { parseMarkdownBlocks(content) }

    SelectionContainer(modifier = modifier) {
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            blocks.forEach { block ->
                when (block) {
                    is MarkdownBlock.Heading -> MarkdownHeading(block)
                    is MarkdownBlock.Paragraph -> MarkdownParagraph(block.text)
                    is MarkdownBlock.ListBlock -> MarkdownList(block.items)
                    is MarkdownBlock.CodeBlock -> MarkdownCodeBlock(block.code)
                }
            }
        }
    }
}

@Composable
private fun MarkdownHeading(block: MarkdownBlock.Heading) {
    val size = when (block.level) {
        1 -> 24.sp
        2 -> 21.sp
        3 -> 19.sp
        else -> 17.sp
    }

    Text(
        text = inlineMarkdown(block.text),
        color = OnSurface,
        fontSize = size,
        lineHeight = (size.value + 6).sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = if (block.level <= 2) 8.dp else 4.dp)
    )
}

@Composable
private fun MarkdownParagraph(text: String) {
    Text(
        text = inlineMarkdown(text),
        color = OnSurface,
        fontSize = 16.sp,
        lineHeight = 24.sp
    )
}

@Composable
private fun MarkdownList(items: List<String>) {
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
private fun MarkdownCodeBlock(code: String) {
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

private sealed class MarkdownBlock {
    data class Heading(val level: Int, val text: String) : MarkdownBlock()
    data class Paragraph(val text: String) : MarkdownBlock()
    data class ListBlock(val items: List<String>) : MarkdownBlock()
    data class CodeBlock(val code: String) : MarkdownBlock()
}

private fun parseMarkdownBlocks(content: String): List<MarkdownBlock> {
    val lines = content.replace("\r\n", "\n").lines()
    val blocks = mutableListOf<MarkdownBlock>()
    var index = 0

    while (index < lines.size) {
        val line = lines[index]

        if (line.isBlank()) {
            index++
            continue
        }

        if (line.trimStart().startsWith("```")) {
            val codeLines = mutableListOf<String>()
            index++
            while (index < lines.size && !lines[index].trimStart().startsWith("```")) {
                codeLines.add(lines[index])
                index++
            }
            if (index < lines.size) index++
            blocks.add(MarkdownBlock.CodeBlock(codeLines.joinToString("\n")))
            continue
        }

        val headingMatch = HeadingRegex.matchEntire(line)
        if (headingMatch != null) {
            blocks.add(MarkdownBlock.Heading(headingMatch.groupValues[1].length, headingMatch.groupValues[2]))
            index++
            continue
        }

        val unorderedMatch = UnorderedListRegex.matchEntire(line)
        val orderedMatch = OrderedListRegex.matchEntire(line)
        if (unorderedMatch != null || orderedMatch != null) {
            val items = mutableListOf<String>()
            while (index < lines.size) {
                val current = lines[index]
                val item = UnorderedListRegex.matchEntire(current)?.groupValues?.get(1)
                    ?: OrderedListRegex.matchEntire(current)?.groupValues?.get(1)
                    ?: break
                items.add(item)
                index++
            }
            blocks.add(MarkdownBlock.ListBlock(items))
            continue
        }

        val paragraphLines = mutableListOf(line.trim())
        index++
        while (index < lines.size) {
            val current = lines[index]
            if (
                current.isBlank() ||
                current.trimStart().startsWith("```") ||
                HeadingRegex.matches(current) ||
                UnorderedListRegex.matches(current) ||
                OrderedListRegex.matches(current)
            ) {
                break
            }
            paragraphLines.add(current.trim())
            index++
        }
        blocks.add(MarkdownBlock.Paragraph(paragraphLines.joinToString(" ")))
    }

    return blocks
}

private fun inlineMarkdown(text: String) = buildAnnotatedString {
    var index = 0

    while (index < text.length) {
        when {
            text.startsWith("**", index) -> {
                val end = text.indexOf("**", startIndex = index + 2)
                if (end != -1) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(text.substring(index + 2, end))
                    }
                    index = end + 2
                } else {
                    append(text[index])
                    index++
                }
            }
            text[index] == '*' -> {
                val end = text.indexOf('*', startIndex = index + 1)
                if (end != -1) {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                        append(text.substring(index + 1, end))
                    }
                    index = end + 1
                } else {
                    append(text[index])
                    index++
                }
            }
            text[index] == '`' -> {
                val end = text.indexOf('`', startIndex = index + 1)
                if (end != -1) {
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = CodePanel)) {
                        append(text.substring(index + 1, end))
                    }
                    index = end + 1
                } else {
                    append(text[index])
                    index++
                }
            }
            text[index] == '[' -> {
                val closeLabel = text.indexOf("](", startIndex = index)
                val closeUrl = if (closeLabel != -1) text.indexOf(')', startIndex = closeLabel + 2) else -1
                if (closeLabel != -1 && closeUrl != -1) {
                    withStyle(SpanStyle(color = LinkBlue, textDecoration = TextDecoration.Underline)) {
                        append(text.substring(index + 1, closeLabel))
                    }
                    index = closeUrl + 1
                } else {
                    append(text[index])
                    index++
                }
            }
            else -> {
                append(text[index])
                index++
            }
        }
    }
}
