package com.example.newproject.ui.screen

import com.example.newproject.ui.theme.PanelChip
import com.example.newproject.ui.theme.OnSurfaceFaint
import com.example.newproject.ui.theme.OnSurface
import androidx.compose.foundation.layout.Spacer
import com.example.newproject.ui.theme.Panel
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Surface
import com.example.newproject.ui.theme.AccentText
import com.example.newproject.ui.component.GradientHeader
import com.example.newproject.ui.component.IconPill
import com.example.newproject.ui.component.NoteContentPanel
import com.example.newproject.ui.markdown.NoteImageLoader
import com.example.newproject.ui.markdown.NoteImageMeasurements
import com.example.newproject.ui.markdown.SkippedImageMeasurement
import com.example.newproject.ui.component.ReadingProgressReporter
import com.example.newproject.ui.component.ReadingTraceCardPanel
import com.example.newproject.domain.sectionSummaryEntryDescription
import com.example.newproject.domain.sectionSummaryEntrySymbol
import com.example.newproject.domain.sectionSummaryStatus
import android.widget.Toast
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.width
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.newproject.model.state.NoteState
import com.example.newproject.model.NoteUiState
import com.example.newproject.model.MarginMemo
import com.example.newproject.domain.markdown.NoteSection
import com.example.newproject.domain.markdown.NoteSectionModel
import com.example.newproject.domain.reunionSlot
import kotlinx.coroutines.launch
import com.example.newproject.ui.theme.OnButtonPrimary
import com.example.newproject.ui.theme.OnButtonSecondary
import com.example.newproject.ui.theme.ButtonOutlineOnGradient
import com.example.newproject.ui.theme.ButtonPrimary
import com.example.newproject.ui.theme.ButtonSecondary
import com.example.newproject.ui.theme.OnVibrant
import com.example.newproject.ui.theme.OnVibrantMuted
import com.example.newproject.ui.theme.ReadingGradient

// ---------------------------------------------------------------------------
// タブ1: ノート（本文リーダー）
//
// 全画面読書は FullscreenNoteScreen.kt、タブと全画面で共用する部品は
// NoteComponents.kt にある。
// ---------------------------------------------------------------------------

@Composable
internal fun NoteReaderTab(
    uiState: NoteUiState,
    /**
     * 本文のパース結果。Main の外で1回だけ作られ全画面表示と共有する（→ NoteSectionController）。
     * 解析中は null で、その間は本文を描かない（描くと描画側がMainで解析し直してしまう）。
     */
    sectionModel: NoteSectionModel?,
    imageLoader: NoteImageLoader?,
    /** 画像の寸法を通常表示と全画面で共有する（→ NoteImageMeasurements）。 */
    imageMeasurements: NoteImageMeasurements?,
    onSelectVault: () -> Unit,
    onRandomNote: () -> Unit,
    /** 10枚を引いて冊子ルートへ入る。**ここでは記録もAIも始まらない**（→ booklet_mode 判断3）。 */
    onOpenBooklet: () -> Unit,
    onRetrySectionSummary: () -> Unit,
    onDismissSectionChat: () -> Unit,
    onEndSectionChat: () -> Unit,
    noteListState: LazyListState,
    onEnterFullscreen: () -> Unit,
    onOpenMarginMemo: () -> Unit,
    onSaveMarginMemo: (text: String, sectionTitle: String?) -> Unit,
    onDeleteMarginMemo: (MarginMemo) -> Unit,
    onDismissMarginMemo: () -> Unit,
    onReadingProgress: (blockIndex: Int, blockFraction: Float, totalBlocks: Int, sectionTitle: String?) -> Unit,
    onDismissReadingTrace: () -> Unit,
    /** 見出しの要約ボタン。今の節（見出しが無ければノート全体）の部分要約を開く。既にあれば再表示する。 */
    onOpenSection: (NoteSection) -> Unit
) {
    val context = LocalContext.current

    LaunchedEffect((uiState.noteState as? NoteState.Error)?.id) {
        if (uiState.noteState is NoteState.Error) {
            Toast.makeText(context, uiState.noteState.message, Toast.LENGTH_SHORT).show()
        }
    }

    val isLoading = uiState.noteState is NoteState.Loading
    val successState = uiState.noteState as? NoteState.Success
    val hasNote = successState != null

    val listState = noteListState
    val coroutineScope = rememberCoroutineScope()
    val currentSection by remember(sectionModel) {
        derivedStateOf { sectionModel?.sectionForBlockIndex(listState.firstVisibleItemIndex) }
    }

    ReadingProgressReporter(sectionModel, listState, imageMeasurements, onReadingProgress)
    SkippedImageMeasurement(sectionModel, imageLoader, imageMeasurements)

    // ノートを引くたびに本文パネルをふわっと出す（フェード＋0.95→1.0のスケール）。
    // AnimatedContent だと新旧リストが1つの listState を共有してしまうため graphicsLayer で行う。
    val noteAppear = remember { Animatable(1f) }
    LaunchedEffect(successState) {
        if (successState != null) {
            noteAppear.snapTo(0f)
            noteAppear.animateTo(1f, animationSpec = tween(300))
        }
    }

    val summaryStatus = sectionSummaryStatus(uiState.sectionChat)

    // 並べ方ごとに置き場所だけを変える。**中身は1か所で組み立てる**（2列と縦積みで食い違わせない）。
    val controls: @Composable ColumnScope.() -> Unit = {
        // 未選択時はVault案内、通常時はコンセプト文を出す。
        GradientHeader(
            title = "Rediscover",
            subtitle = if (!uiState.vaultSelected) "Vaultフォルダが未選択です"
            else "過去のノートから、思考をひとつ。",
            // **✎ を ⛶ の隣へ置く。** 本文のどこを読んでいても同じ位置にあり、
            // 本文スクロール・蒸留のつまみ・全画面の💬と縁を取り合わない
            // （→ features/reflect_margin_memo.md 判断7）。
            trailing = if (hasNote) {
                {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        // **要約の入口は ✎ と分ける。** ✎ は書く入口、💬 は AI の入口。
                        // 状態は記号で静かに示し、浮く通知は作らない（→ features/section_ai_chat.md）。
                        IconPill(
                            symbol = sectionSummaryEntrySymbol(summaryStatus),
                            contentDescription = sectionSummaryEntryDescription(summaryStatus)
                        ) {
                            successState?.let { note ->
                                onOpenSection(currentSection ?: NoteSection(note.title, 0, note.content))
                            }
                        }
                        IconPill(symbol = "✎", contentDescription = "このノートのメモ") {
                            onOpenMarginMemo()
                        }
                        IconPill(symbol = "⛶", contentDescription = "全画面表示") { onEnterFullscreen() }
                    }
                }
            } else null
        )

        // ボタンは画面の操作であってノートの操作ではないので、**状態によらず常にここ**。
        // ノートの有無で位置が動くと、同じボタンを毎回探し直すことになる。
        NoteActionButtons(
            vaultSelected = uiState.vaultSelected,
            isLoading = isLoading,
            onSelectVault = onSelectVault,
            onRandomNote = onRandomNote,
            onOpenBooklet = onOpenBooklet,
            modifier = Modifier.padding(top = 16.dp)
        )

        if (isLoading) {
            Surface(
                color = Panel,
                shape = CircleShape,
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 16.dp)
            ) {
                CircularProgressIndicator(
                    color = AccentText,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }
    }
    // ノートが出ていないときの案内。**内容なりの高さに留める** — 本文パネルと同じく `weight(1f)` で伸ばすと、
    // 中身の無い白が画面の大半を占める。
    val emptyNote: @Composable () -> Unit = {
        Surface(
            modifier = Modifier.fillMaxWidth().padding(top = 20.dp),
            color = Panel,
            shape = RoundedCornerShape(8.dp)
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Text(
                    text = "ノート未表示",
                    color = OnSurface,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = when (uiState.noteState) {
                        is NoteState.Empty -> "このVaultにMarkdownノートが見つかりませんでした。"
                        is NoteState.Error -> "Vaultを読み込めませんでした。"
                        else -> "Vaultフォルダを選択して「別のノートをひらく」をタップしてください。"
                    },
                    color = OnSurfaceFaint,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
    }
    // 「前回のあなた」カード。NoteContentPanel の外側に置くので全画面には出ない
    // （NoteContentPanel は全画面と共用。LazyColumn の index もずらさないので
    //  セクション判定とスクロール継承を壊さない）。
    val visibleTraceCard = uiState.readingTraceCard?.takeIf { hasNote && !it.isDismissed }
    val traceCard: @Composable (Modifier) -> Unit = { modifier ->
        if (visibleTraceCard != null) {
            ReadingTraceCardPanel(
                card = visibleTraceCard,
                // 枠の中身は純関数で決める（→ features/reunion_card.md 判断6）。
                slot = reunionSlot(visibleTraceCard, uiState.summaryState),
                modifier = modifier,
                onDismiss = onDismissReadingTrace,
                onOpenMemos = onOpenMarginMemo,
                onResume = {
                    // **畳むのは即時、送るのは別に走らせる。** 送りは suspend で完了の保証が無いので、
                    // その後ろに畳む操作を置かない（→ lessons L35）。
                    onDismissReadingTrace()
                    val target = visibleTraceCard.resumeBlockIndex
                    val blocks = sectionModel?.blocks
                    if (target != null && !blocks.isNullOrEmpty()) {
                        val clamped = target.coerceAtMost(blocks.lastIndex)
                        coroutineScope.launch { listState.scrollToItem(clamped) }
                        // **飛び越した画像は描画されないので測られない。** 測らないと、その先の読書の
                        // 報告が止まり続ける。止める検査は緩めず、測り終えれば報告が再開する。
                        // **ここで測らず、ノート単位の入れ物へ依頼する** — この画面のスコープで測ると、
                        // 測定待ちの間に全画面へ移っただけでキャンセルされる。
                        imageMeasurements?.requestSkippedMeasurement(clamped)
                    }
                }
            )
        }
    }
    val notePanel: @Composable (Modifier) -> Unit = { modifier ->
        NoteContentPanel(
            uiState = uiState,
            modifier = modifier
                .graphicsLayer {
                    alpha = noteAppear.value
                    val scale = 0.95f + 0.05f * noteAppear.value
                    scaleX = scale
                    scaleY = scale
                },
            listState = listState,
            precomputedBlocks = sectionModel?.blocks,
            imageLoader = imageLoader,
            imageMeasurements = imageMeasurements
        )
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(ReadingGradient)
            .safeDrawingPadding()
            .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 12.dp)
    ) {
        val sideColumnWidth = sideColumnWidthDp(maxWidth.value).dp
        when (readerLayoutFor(maxWidth.value, maxHeight.value)) {
            ReaderLayout.Stacked -> Column(modifier = Modifier.fillMaxSize()) {
                controls()
                if (!hasNote) {
                    emptyNote()
                    // 余りをカードではなく余白へ逃がす。
                    Spacer(modifier = Modifier.weight(1f))
                } else {
                    traceCard(Modifier.padding(top = 20.dp))
                    notePanel(
                        Modifier
                            .weight(1f)
                            .padding(top = if (isLoading || visibleTraceCard != null) 8.dp else 20.dp)
                    )
                }
            }
            // 左に操作とカード、右に本文。**左列だけをスクロールさせる** — カードが長くても本文の高さは削らない。
            ReaderLayout.SideBySide -> Row(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier
                        .width(sideColumnWidth)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState())
                ) {
                    controls()
                    traceCard(Modifier.padding(top = 16.dp))
                }
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    if (!hasNote) {
                        emptyNote()
                        Spacer(modifier = Modifier.weight(1f))
                    } else {
                        notePanel(Modifier.weight(1f))
                    }
                }
            }
        }
    }

    // 余白メモのボトムシート。**書いた場所は開いた時点の可視セクションから引く**
    // （紐づけではなく当時の記録 → 判断2）。
    if (uiState.isMarginMemoSheetVisible) {
        MarginMemoSheet(
            state = uiState.marginMemoState,
            onSave = { text -> onSaveMarginMemo(text, currentSection?.title) },
            onDelete = onDeleteMarginMemo,
            onDismiss = onDismissMarginMemo
        )
    }

    // セクションチャットのボトムシート
    if (uiState.isSectionChatSheetVisible) uiState.sectionChat?.let { chat ->
        SectionChatSheet(
            state = chat,
            onRetrySummary = onRetrySectionSummary,
            onDismiss = onDismissSectionChat,
            onEndSession = onEndSectionChat
        )
    }
}

/**
 * ノート画面の操作ボタン。
 *
 * **Vault未選択のときは「Vaultを選択」が主役になる。** その状態で唯一意味のある操作が
 * これで、「別のノートをひらく」は押しても開くノートが無い（無効にする）。
 * 以前は逆で、目立つピンクが機能しないほうに付いていた。
 *
 * このボタンは常にグラデーション直上に置かれるので、輪郭線を必ず描く。
 * 塗りの色をどう選んでも停止色との3:1は満たせない（→ ButtonOutlineOnGradient）。
 */
@Composable
private fun NoteActionButtons(
    vaultSelected: Boolean,
    isLoading: Boolean,
    onSelectVault: () -> Unit,
    onRandomNote: () -> Unit,
    onOpenBooklet: () -> Unit,
    modifier: Modifier = Modifier
) {
    val outline = BorderStroke(1.dp, ButtonOutlineOnGradient)
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Vault切替はオプションへ移動。初回セットアップ時だけここにも出す。
        if (!vaultSelected) {
            Button(
                onClick = onSelectVault,
                modifier = Modifier.weight(1f).height(48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = ButtonPrimary, contentColor = OnButtonPrimary),
                border = outline,
                shape = RoundedCornerShape(24.dp)
            ) { Text("Vaultを選択", color = OnButtonPrimary) }
        }
        Button(
            onClick = onRandomNote,
            enabled = !isLoading && vaultSelected,
            modifier = Modifier.weight(1f).height(48.dp),
            colors = if (vaultSelected) {
                ButtonDefaults.buttonColors(containerColor = ButtonPrimary, contentColor = OnButtonPrimary)
            } else {
                ButtonDefaults.buttonColors(
                    containerColor = ButtonSecondary,
                    contentColor = OnButtonSecondary,
                    // 無効時の既定はテーマ由来のαなので、明示して不透明に保つ。
                    disabledContainerColor = PanelChip,
                    disabledContentColor = OnSurfaceFaint
                )
            },
            border = outline,
            shape = RoundedCornerShape(24.dp)
        ) {
            Text(
                "別のノートをひらく",
                color = if (vaultSelected) OnButtonPrimary else OnSurfaceFaint
            )
        }
        // 副。**幅を weight で分けない** — 3つ並ぶ初回セットアップ時に主が痩せる。
        // 同格（どちらもピンク）に並べないのは、「同色ボタンが並ぶと区別できない」という
        // 実機フィードバックの形そのものになるため（→ features/booklet_mode.md §8 の落とし穴）。
        Button(
            onClick = onOpenBooklet,
            enabled = !isLoading && vaultSelected,
            modifier = Modifier
                .width(64.dp)
                .height(48.dp)
                .semantics { contentDescription = "冊子をひらく" },
            contentPadding = PaddingValues(0.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = ButtonSecondary,
                contentColor = OnButtonSecondary,
                disabledContainerColor = PanelChip,
                disabledContentColor = OnSurfaceFaint
            ),
            border = outline,
            shape = RoundedCornerShape(24.dp)
        ) {
            Text("📖", fontSize = 18.sp, modifier = Modifier.clearAndSetSemantics {})
        }
    }
}
