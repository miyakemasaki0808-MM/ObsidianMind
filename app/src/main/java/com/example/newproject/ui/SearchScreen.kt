package com.example.newproject.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.newproject.HistoryEntry
import com.example.newproject.NoteFolder
import com.example.newproject.NoteUiState
import com.example.newproject.SearchState
import com.example.newproject.domain.AiRecommendationStatus
import com.example.newproject.domain.RelatedNote
import com.example.newproject.ui.theme.AppGradient
import com.example.newproject.ui.theme.ButtonPrimary
import com.example.newproject.ui.theme.ButtonSecondary
import com.example.newproject.ui.theme.ErrorRed
import com.example.newproject.ui.theme.Indigo
import com.example.newproject.ui.theme.OnSurface
import com.example.newproject.ui.theme.OnVibrant
import com.example.newproject.ui.theme.OnVibrantMuted
import com.example.newproject.ui.theme.Panel
import com.example.newproject.ui.theme.PanelBlue
import com.example.newproject.ui.theme.PanelDivider

/**
 * さがすタブ（AIピッカー）。
 * フォルダ選択を共通土台に、🔎キーワード（Nano が3件選定）と🎰ランダム（AI不使用3件）の2モード。
 */
@Composable
fun SearchTab(
    uiState: NoteUiState,
    onSelectFolder: (NoteFolder?) -> Unit,
    onSearch: (String) -> Unit,
    onRandom: () -> Unit,
    onOpenNote: (RelatedNote) -> Unit
) {
    var query by remember { mutableStateOf("") }
    val isLoading = uiState.searchState is SearchState.Loading

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AppGradient)
            .safeDrawingPadding()
            // 検索結果＋当日履歴で縦に伸びるため、タブ全体をスクロール可能にする
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 12.dp)
    ) {
        Text("Explore", color = OnVibrant, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Text(
            text = "フォルダを選んで、言葉で手繰るか、偶然にまかせる。",
            color = OnVibrantMuted,
            fontSize = 13.sp,
            modifier = Modifier.padding(top = 4.dp)
        )

        // フォルダchips（横スクロール）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(top = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FolderChip(label = "ルート直下", selected = uiState.selectedFolder == null) {
                onSelectFolder(null)
            }
            uiState.folders.forEach { folder ->
                FolderChip(
                    label = folder.name,
                    selected = uiState.selectedFolder?.documentId == folder.documentId
                ) { onSelectFolder(folder) }
            }
        }

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 14.dp),
            placeholder = { Text("例: 習慣化について書いたやつ") },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = OnVibrant,
                unfocusedTextColor = OnVibrant,
                cursorColor = OnVibrant,
                focusedBorderColor = OnVibrant,
                unfocusedBorderColor = OnVibrant.copy(alpha = 0.5f),
                focusedPlaceholderColor = OnVibrantMuted,
                unfocusedPlaceholderColor = OnVibrantMuted
            )
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                onClick = onRandom,
                enabled = !isLoading,
                modifier = Modifier.weight(1f).height(48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = ButtonSecondary),
                shape = RoundedCornerShape(24.dp)
            ) { Text("🎰 ランダムに引く", color = OnVibrant) }
            Button(
                onClick = { onSearch(query) },
                enabled = query.isNotBlank() && !isLoading,
                modifier = Modifier.weight(1f).height(48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = ButtonPrimary),
                shape = RoundedCornerShape(24.dp)
            ) { Text("🔎 ことばでさがす", color = OnVibrant) }
        }

        Spacer(modifier = Modifier.height(16.dp))
        SearchResultPanel(state = uiState.searchState, onNoteClick = onOpenNote)

        if (uiState.todayHistory.isNotEmpty()) {
            Spacer(modifier = Modifier.height(16.dp))
            TodayHistoryPanel(entries = uiState.todayHistory, onNoteClick = onOpenNote)
        }
    }
}

/** 当日分の閲覧履歴。タップで検索結果と同じ導線でノートを開き直す。 */
@Composable
private fun TodayHistoryPanel(
    entries: List<HistoryEntry>,
    onNoteClick: (RelatedNote) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = PanelBlue,
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                "🕐 今日読んだノート",
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = OnSurface
            )
            Spacer(modifier = Modifier.height(4.dp))
            entries.forEachIndexed { index, entry ->
                val note = RelatedNote(title = entry.title, uri = entry.uri, isWikilinked = false)
                RelatedNoteItem(note = note, onClick = { onNoteClick(note) })
                if (index < entries.lastIndex) {
                    HorizontalDivider(color = PanelDivider, thickness = 0.5.dp)
                }
            }
        }
    }
}

@Composable
private fun FolderChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(999.dp),
        color = if (selected) Panel else Panel.copy(alpha = 0.22f)
    ) {
        Text(
            text = label,
            color = if (selected) OnSurface else OnVibrant,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
        )
    }
}

@Composable
private fun SearchResultPanel(state: SearchState, onNoteClick: (RelatedNote) -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = PanelBlue,
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            when (state) {
                is SearchState.Idle -> Text(
                    "フォルダを選んで検索すると、ここに3件出ます。",
                    fontSize = 13.sp,
                    color = Color(0xFF777777)
                )
                is SearchState.Loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Indigo)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("さがしています…", fontSize = 13.sp, color = Color(0xFF555555))
                }
                is SearchState.Success -> {
                    if (state.results.isEmpty()) {
                        Text("見つかりませんでした。", fontSize = 13.sp, color = Color(0xFF777777))
                    } else {
                        fallbackNotice(state.aiStatus)?.let { notice ->
                            Text(notice, fontSize = 12.sp, color = Color(0xFF888888))
                            Spacer(modifier = Modifier.height(6.dp))
                        }
                        state.results.forEachIndexed { index, note ->
                            RelatedNoteItem(note = note, onClick = { onNoteClick(note) })
                            if (index < state.results.lastIndex) {
                                HorizontalDivider(color = PanelDivider, thickness = 0.5.dp)
                            }
                        }
                    }
                }
                is SearchState.Error -> Text(
                    "検索に失敗しました: ${state.message}",
                    fontSize = 13.sp,
                    color = ErrorRed
                )
            }
        }
    }
}

// AI が使えないときは素のキーワード一致で表示している旨を添える。
private fun fallbackNotice(status: AiRecommendationStatus): String? = when (status) {
    AiRecommendationStatus.Ready -> null
    AiRecommendationStatus.Unavailable -> "AI非対応のため、キーワード一致で表示しています。"
    AiRecommendationStatus.NeedsDownload -> "AI準備中のため、キーワード一致で表示しています。"
    AiRecommendationStatus.Error -> "AIエラーのため、キーワード一致で表示しています。"
}
