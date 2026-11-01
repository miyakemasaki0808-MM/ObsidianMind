package com.example.newproject.ui.screen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.newproject.ui.TitleBody
import com.example.newproject.ui.component.GradientHeader
import com.example.newproject.ui.component.ModeToggleChip
import com.example.newproject.ui.component.bleedScreenPadding
import com.example.newproject.ui.component.screenContentPadding
import com.example.newproject.ui.component.screenHorizontalPadding
import com.example.newproject.ui.titleBodyFor
import com.example.newproject.ui.titleMatchLocation
import com.example.newproject.model.HistoryEntry
import com.example.newproject.model.NoteFolder
import com.example.newproject.model.NoteUiState
import com.example.newproject.model.state.SearchState
import com.example.newproject.model.state.TitleMatch
import com.example.newproject.model.state.TitleSearchState
import com.example.newproject.model.RelatedNote
import com.example.newproject.ui.theme.PanelChip
import com.example.newproject.ui.theme.PanelDividerStrong
import com.example.newproject.ui.theme.OnButtonPrimary
import com.example.newproject.ui.theme.OnSurfaceFaint
import com.example.newproject.ui.theme.OnSurfaceMuted
import com.example.newproject.ui.theme.AppGradient
import com.example.newproject.ui.theme.ButtonOutlineOnGradient
import com.example.newproject.ui.theme.ButtonPrimary
import com.example.newproject.ui.theme.ErrorText
import com.example.newproject.ui.theme.AccentText
import com.example.newproject.ui.theme.OnSurface
import com.example.newproject.ui.theme.OnVibrant
import com.example.newproject.ui.theme.Panel
import com.example.newproject.ui.theme.PanelBlue
import com.example.newproject.ui.theme.PanelDivider

private enum class ExploreMode { Title, Ai }

/**
 * Explore（さがすタブ → features/ai_picker.md）。タイトルから探すか、ことばで Nano に3件選ばせる。
 *
 * タイトルの結果は Vault 全体の件数まで伸びるので、タブ全体を遅延リストで組む。
 * 打った言葉はここが持つ（日本語の変換中に入力が崩れないように状態の外へ置く）。
 * 言葉もモードも、タブを離れると組み立て直されて最初へ戻る。開くたびにタイトルから始めるのはこのため。
 */
@Composable
fun SearchTab(
    uiState: NoteUiState,
    onSelectFolder: (NoteFolder?) -> Unit,
    onSearchTitle: (String) -> Unit,
    onSearch: (String) -> Unit,
    onOpenNote: (RelatedNote) -> Unit
) {
    var query by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf(ExploreMode.Title) }
    val focusManager = LocalFocusManager.current
    val isLoading = uiState.searchState is SearchState.Loading

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(AppGradient)
            .safeDrawingPadding()
            .screenContentPadding()
            // リストは自分の枠で中身を切るので、見出しの霞が画面の端へ届くよう枠を広げ、各行で余白へ戻す。
            .bleedScreenPadding()
    ) {
        item(key = "header") {
            GradientHeader(
                title = "Explore",
                subtitle = "タイトルで探すか、ことばで手繰る。",
                modifier = Modifier.exploreRowPadding()
            )
        }
        item(key = "mode") {
            Row(
                modifier = Modifier
                    .exploreRowPadding()
                    .fillMaxWidth()
                    .padding(top = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ModeToggleChip(
                    label = "タイトル",
                    selected = mode == ExploreMode.Title,
                    modifier = Modifier.weight(1f)
                ) { mode = ExploreMode.Title }
                ModeToggleChip(
                    label = "AI",
                    selected = mode == ExploreMode.Ai,
                    modifier = Modifier.weight(1f)
                ) { mode = ExploreMode.Ai }
            }
        }
        // モードを替えても入力欄の位置とフォーカスを保つ。範囲のチップは入力欄の下に置く。
        item(key = "query") {
            OutlinedTextField(
                value = query,
                onValueChange = {
                    query = it
                    onSearchTitle(it)
                },
                modifier = Modifier
                    .exploreRowPadding()
                    .fillMaxWidth()
                    .padding(top = 14.dp),
                placeholder = {
                    Text(if (mode == ExploreMode.Title) "タイトルの一部" else "例: 習慣化について書いたやつ")
                },
                singleLine = true,
                // AI の検索は数十秒かかるので、確定キーでは走らせない。ボタンを押したときだけ。
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                shape = RoundedCornerShape(16.dp),
                // 入力欄も不透明な面を持たせる。透けたままだと入力文字のコントラストが
                // 背後の停止色で変わり、Aqua帯では白文字が 2.07 まで落ちる。
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = Panel,
                    unfocusedContainerColor = Panel,
                    focusedTextColor = OnSurface,
                    unfocusedTextColor = OnSurface,
                    cursorColor = AccentText,
                    focusedBorderColor = AccentText,
                    unfocusedBorderColor = PanelDividerStrong,
                    focusedPlaceholderColor = OnSurfaceFaint,
                    unfocusedPlaceholderColor = OnSurfaceFaint
                )
            )
        }
        when (mode) {
            ExploreMode.Title -> titleResults(
                query = query,
                uiState = uiState,
                onOpenNote = onOpenNote,
                onSearchWithAi = {
                    mode = ExploreMode.Ai
                    onSearch(query)
                }
            )
            ExploreMode.Ai -> {
                item(key = "scope") { ScopeChips(uiState = uiState, onSelectFolder = onSelectFolder) }
                item(key = "search") {
                    Button(
                        onClick = { onSearch(query) },
                        enabled = query.isNotBlank() && !isLoading,
                        modifier = Modifier
                            .exploreRowPadding()
                            .fillMaxWidth()
                            .padding(top = 12.dp)
                            .height(48.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = ButtonPrimary, contentColor = OnButtonPrimary),
                        border = BorderStroke(1.dp, ButtonOutlineOnGradient),
                        shape = RoundedCornerShape(24.dp)
                    ) { Text("🔎 ことばでさがす", color = OnButtonPrimary) }
                }
                item(key = "result") {
                    SearchResultPanel(
                        state = uiState.searchState,
                        onNoteClick = onOpenNote,
                        modifier = Modifier.exploreRowPadding().padding(top = 16.dp)
                    )
                }
                historyPanel(uiState.todayHistory, onOpenNote)
            }
        }
    }
}

private fun Modifier.exploreRowPadding(): Modifier = this.screenHorizontalPadding()

/** タイトルのモードの、入力欄より下。 */
private fun LazyListScope.titleResults(
    query: String,
    uiState: NoteUiState,
    onOpenNote: (RelatedNote) -> Unit,
    onSearchWithAi: () -> Unit
) {
    when (titleBodyFor(query, uiState.titleSearch)) {
        TitleBody.History -> historyPanel(uiState.todayHistory, onOpenNote)
        TitleBody.Scanning -> item(key = "title-message") {
            MessagePanel { LoadingRow("ノートの一覧を読んでいます…") }
        }
        TitleBody.Failed -> item(key = "title-message") {
            val message = (uiState.titleSearch as? TitleSearchState.Error)?.message.orEmpty()
            MessagePanel {
                Text("ノートの一覧を読めませんでした: $message", fontSize = 13.sp, color = ErrorText)
            }
        }
        TitleBody.NoMatch -> item(key = "title-message") {
            MessagePanel {
                Text("タイトルに見つかりませんでした。", fontSize = 13.sp, color = OnSurfaceFaint)
                TextButton(
                    onClick = onSearchWithAi,
                    colors = ButtonDefaults.textButtonColors(contentColor = AccentText)
                ) { Text("🔎 同じことばを AI でさがす") }
            }
        }
        TitleBody.Matches -> {
            val matches = (uiState.titleSearch as? TitleSearchState.Matches)?.matches.orEmpty()
            itemsIndexed(matches, key = { _, match -> match.note.ref.value }) { index, match ->
                TitleMatchRow(
                    match = match,
                    isFirst = index == 0,
                    isLast = index == matches.lastIndex,
                    onClick = { onOpenNote(match.note) }
                )
            }
        }
    }
}

private fun LazyListScope.historyPanel(entries: List<HistoryEntry>, onNoteClick: (RelatedNote) -> Unit) {
    if (entries.isEmpty()) return
    item(key = "history") {
        TodayHistoryPanel(
            entries = entries,
            onNoteClick = onNoteClick,
            modifier = Modifier.exploreRowPadding().padding(top = 16.dp)
        )
    }
}

/** 結果の1行。遅延リストの行どうしが1枚のパネルに見えるよう、端の行だけ角を丸める。 */
@Composable
private fun TitleMatchRow(match: TitleMatch, isFirst: Boolean, isLast: Boolean, onClick: () -> Unit) {
    val corner = 8.dp
    Surface(
        modifier = Modifier
            .exploreRowPadding()
            .fillMaxWidth()
            .padding(top = if (isFirst) 16.dp else 0.dp),
        color = PanelBlue,
        shape = RoundedCornerShape(
            topStart = if (isFirst) corner else 0.dp,
            topEnd = if (isFirst) corner else 0.dp,
            bottomStart = if (isLast) corner else 0.dp,
            bottomEnd = if (isLast) corner else 0.dp
        )
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp)) {
            RelatedNoteItem(
                note = match.note,
                detail = titleMatchLocation(match.folderPath),
                onClick = onClick
            )
            if (!isLast) HorizontalDivider(color = PanelDivider, thickness = 0.5.dp)
        }
    }
}

@Composable
private fun MessagePanel(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier
            .exploreRowPadding()
            .fillMaxWidth()
            .padding(top = 16.dp),
        color = PanelBlue,
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp), content = content)
    }
}

@Composable
private fun LoadingRow(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = AccentText)
        Spacer(modifier = Modifier.width(8.dp))
        Text(text, fontSize = 13.sp, color = OnSurfaceMuted)
    }
}

/** AI のモードの範囲。「すべて」の限界はここで言う — 候補はタイトルが近い40本に絞られる。 */
@Composable
private fun ScopeChips(uiState: NoteUiState, onSelectFolder: (NoteFolder?) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 14.dp)) {
        // 横に送るチップは画面の端まで届かせ、中身の余白で揃える。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .exploreRowPadding(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FolderChip(label = "すべて", selected = uiState.selectedFolder == null) {
                onSelectFolder(null)
            }
            uiState.folders.forEach { folder ->
                FolderChip(
                    label = folder.name,
                    selected = uiState.selectedFolder?.documentId == folder.documentId
                ) { onSelectFolder(folder) }
            }
        }
        if (uiState.selectedFolder == null) {
            ScopeNote("タイトルが近い40本から選びます。意味で探したいときはフォルダを選んでください。")
        }
        // フォルダ列挙に失敗しても「すべて」では検索できる。操作を止めない注記に留める。
        uiState.foldersError?.let { ScopeNote(it) }
    }
}

/** グラデーション直上に文字を置かない。注記も面を持たせる。 */
@Composable
private fun ScopeNote(message: String) {
    Surface(
        color = Panel,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.exploreRowPadding().padding(top = 6.dp)
    ) {
        Text(
            text = message,
            color = OnSurfaceFaint,
            fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
        )
    }
}

/** 当日分の閲覧履歴。タップで検索結果と同じ導線でノートを開き直す。 */
@Composable
private fun TodayHistoryPanel(
    entries: List<HistoryEntry>,
    onNoteClick: (RelatedNote) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
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
                val note = RelatedNote(title = entry.title, ref = entry.ref, isWikilinked = false)
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
        // 未選択も不透明にする。透過22%ではグラデーションが透けて、
        // 白文字が 1.82〜4.10 と下地しだいで変わっていた。
        color = if (selected) Panel else PanelChip
    ) {
        Text(
            text = label,
            color = OnSurface,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
        )
    }
}

@Composable
private fun SearchResultPanel(
    state: SearchState,
    onNoteClick: (RelatedNote) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = PanelBlue,
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            when (state) {
                is SearchState.Idle -> Text(
                    "ことばを入れてさがすと、ここに3件出ます。",
                    fontSize = 13.sp,
                    color = OnSurfaceFaint
                )
                is SearchState.Loading -> LoadingRow("さがしています…")
                is SearchState.Success -> {
                    // AIが選定に関与しなかったことだけを添える。**理由は書かない** —
                    // 非対応でも未取得でも取得失敗でも、ここに出ているものは同じだから。
                    // **0件でも出す。** ここを結果ありの枝に入れていたため、
                    // 「見つかりませんでした。」だけが出て、AIを使わずに探した事実が消えていた。
                    if (!state.isAiAssisted) {
                        Text(
                            "AIを使えないため、キーワード一致で表示しています。",
                            fontSize = 12.sp,
                            color = OnSurfaceFaint
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                    }
                    if (state.results.isEmpty()) {
                        Text("見つかりませんでした。", fontSize = 13.sp, color = OnSurfaceFaint)
                    } else {
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
                    color = ErrorText
                )
            }
        }
    }
}
