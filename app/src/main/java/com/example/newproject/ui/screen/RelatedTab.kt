package com.example.newproject.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.newproject.ui.component.GradientHeader
import com.example.newproject.ui.component.screenContentPadding
import com.example.newproject.model.NoteUiState
import com.example.newproject.model.state.RelatedNotesState
import com.example.newproject.model.RelatedNote
import com.example.newproject.model.state.RebuttalSentence
import com.example.newproject.ui.theme.OnAccentSurface
import com.example.newproject.ui.theme.Panel
import com.example.newproject.ui.theme.AiHeading
import com.example.newproject.ui.theme.OnSurfaceFaint
import com.example.newproject.ui.theme.OnSurfaceMetaBlue
import com.example.newproject.ui.theme.OnSurfaceMuted
import com.example.newproject.ui.theme.PanelDividerStrong
import com.example.newproject.ui.theme.RelatedHeading
import com.example.newproject.ui.theme.AppGradient
import com.example.newproject.ui.theme.AccentText
import com.example.newproject.ui.theme.OnSurface
import com.example.newproject.ui.theme.OnVibrant
import com.example.newproject.ui.theme.OnVibrantMuted
import com.example.newproject.ui.theme.PanelBlue
import com.example.newproject.ui.theme.PanelDivider
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ---------------------------------------------------------------------------
// タブ2: 関連ノート
// ---------------------------------------------------------------------------

@Composable
fun RelatedTab(
    uiState: NoteUiState,
    onOpenNote: (RelatedNote) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AppGradient)
            .safeDrawingPadding()
            .screenContentPadding()
    ) {
        GradientHeader(
            title = "Connect",
            subtitle = "いま読んでいるノートと、つながる思考。"
        )
        Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            if (uiState.relatedNotesState is RelatedNotesState.Idle) {
                Surface(color = Panel, shape = RoundedCornerShape(8.dp)) {
                    Text(
                        text = "ノートを開くと、リンクとAIの関連がここに集まります。",
                        color = OnSurfaceFaint,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            } else {
                RelatedNotesPanel(
                    state = uiState.relatedNotesState,
                    onNoteClick = onOpenNote
                )
            }
            // 選べたときだけ出す。探している途中も、見つからなかったことも出さない（自動で走る機能）。
            uiState.rebuttalSentence?.let { sentence ->
                Spacer(modifier = Modifier.height(12.dp))
                RebuttalPanel(sentence = sentence, onOpenSource = onOpenNote)
            }
        }
    }
}

/**
 * 反証の一文の枠（→ features/rebuttal_sentence.md §5.7）。**色も形も足さない** — 関連ノートと同じ面に載せる。
 * 出どころの行を押すと、関連ノートと同じくそのノートへ移る。
 */
@Composable
internal fun RebuttalPanel(
    sentence: RebuttalSentence,
    onOpenSource: (RelatedNote) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = PanelBlue,
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text(
                text = REBUTTAL_HEADING,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = AccentText,
                modifier = Modifier.semantics { heading() }
            )
            RebuttalSentenceBody(sentence = sentence, onOpenSource = onOpenSource)
        }
    }
}

/**
 * 反証の一文の中身。関連タブの枠と余白ペインで共有する。見出しの見え方だけが置き場で違う。
 *
 * **出どころは関連ノートの行をそのまま使う**（→ features/rebuttal_sentence.md 判断6）。
 * 同じ意味の行が2つの見た目で並ばないように、タップの先・linked・更新日を関連ノートと揃える。
 */
@Composable
internal fun RebuttalSentenceBody(sentence: RebuttalSentence, onOpenSource: (RelatedNote) -> Unit) {
    Text(
        text = "いま読んでいるノートと食い違う、別のノートの一文",
        fontSize = 11.sp,
        color = OnSurfaceFaint
    )
    Spacer(modifier = Modifier.height(8.dp))
    Text(
        text = sentence.text,
        fontSize = 14.sp,
        lineHeight = 19.sp,
        color = OnSurface
    )
    Spacer(modifier = Modifier.height(8.dp))
    HorizontalDivider(color = PanelDivider, thickness = 0.5.dp)
    RelatedNoteItem(note = sentence.source, onClick = { onOpenSource(sentence.source) })
}

internal const val REBUTTAL_HEADING = "反証の一文"

@Composable
internal fun RelatedNotesPanel(
    state: RelatedNotesState,
    onNoteClick: (RelatedNote) -> Unit,
    modifier: Modifier = Modifier
) {
    when (state) {
        is RelatedNotesState.Idle -> return
        is RelatedNotesState.Loading,
        is RelatedNotesState.Success -> Unit
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = PanelBlue,
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text(
                text = "🔗 関連ノート",
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = AccentText
            )
            Spacer(modifier = Modifier.height(8.dp))
            when (state) {
                is RelatedNotesState.Loading -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = AccentText
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("関連ノートを検索中…", fontSize = 13.sp, color = OnSurfaceMuted)
                    }
                }
                is RelatedNotesState.Success -> {
                    val hasRelated = state.relatedNotes.isNotEmpty()
                    val hasAi = state.aiNotes.isNotEmpty()
                    if (!hasRelated && !hasAi) {
                        Text("関連ノートは見つかりませんでした。", fontSize = 13.sp, color = OnSurfaceFaint)
                    } else {
                        if (hasRelated) {
                            Text(
                                text = "関連ノート",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = RelatedHeading
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            state.relatedNotes.forEachIndexed { index, note ->
                                RelatedNoteItem(note = note, onClick = { onNoteClick(note) })
                                if (index < state.relatedNotes.lastIndex) {
                                    HorizontalDivider(color = PanelDivider, thickness = 0.5.dp)
                                }
                            }
                        }
                        // **AI推薦が無いなら見出しごと出さない。** 自動で走る機能なので、
                        // 使えない理由も「見つかりませんでした」も画面に置かない。
                        if (hasAi) {
                            if (hasRelated) {
                                Spacer(modifier = Modifier.height(8.dp))
                                HorizontalDivider(color = PanelDividerStrong, thickness = 1.dp)
                                Spacer(modifier = Modifier.height(8.dp))
                            }
                            Text(
                                text = "AI推薦",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = AiHeading
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            state.aiNotes.forEachIndexed { index, note ->
                                RelatedNoteItem(note = note, onClick = { onNoteClick(note) })
                                if (index < state.aiNotes.lastIndex) {
                                    HorizontalDivider(color = PanelDivider, thickness = 0.5.dp)
                                }
                            }
                        }
                    }
                }
                else -> {}
            }
        }
    }
}

@Composable
internal fun RelatedNoteItem(note: RelatedNote, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 44.dp)
            .clickable(onClickLabel = "開く", onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = note.title,
                fontSize = 14.sp,
                lineHeight = 18.sp,
                color = OnSurface
            )
            note.lastModified?.let { millis ->
                Text(
                    text = "更新 ${formatNoteDate(millis)}",
                    fontSize = 11.sp,
                    color = OnSurfaceMetaBlue,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
        if (note.isWikilinked) {
            Surface(
                color = AccentText,
                shape = RoundedCornerShape(4.dp)
            ) {
                Text(
                    text = "linked",
                    color = OnAccentSurface,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }
    }
}

// UIスレッドからのみ呼ぶ前提（SimpleDateFormatはスレッドセーフでないため）
private val noteDateFormat = SimpleDateFormat("yyyy/MM/dd", Locale.JAPAN)

private fun formatNoteDate(epochMillis: Long): String =
    noteDateFormat.format(Date(epochMillis))
