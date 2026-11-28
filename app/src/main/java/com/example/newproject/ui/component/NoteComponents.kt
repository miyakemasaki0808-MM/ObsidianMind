package com.example.newproject.ui.component

import com.example.newproject.model.ReadingTrace
import com.example.newproject.ui.quantizeReadingFraction
import com.example.newproject.ui.READING_FRACTION_STEPS
import com.example.newproject.ui.screen.FullscreenNoteScreen
import com.example.newproject.ui.screen.NoteReaderTab
import com.example.newproject.ui.visibleFractionOfBlock
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.LocalTextStyle
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.BoxWithConstraints
import com.example.newproject.ui.theme.ReadingPlateRim
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import com.example.newproject.ui.theme.ReadingAccent
import com.example.newproject.model.NotePaperTone
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.draw.drawBehind
import com.example.newproject.ui.theme.ReadingPlateLight
import com.example.newproject.ui.markdown.MarkdownBodyStyle
import com.example.newproject.ui.markdown.NoteTitleBlock
import com.example.newproject.ui.markdown.noteFolderLabel
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.newproject.model.state.NoteState
import com.example.newproject.model.NoteUiState
import com.example.newproject.domain.NoteLink
import com.example.newproject.domain.markdown.MarkdownBlock
import com.example.newproject.ui.markdown.QuoteOffers
import com.example.newproject.ui.markdown.QuoteSelection
import com.example.newproject.ui.markdown.MarkdownNoteContent
import com.example.newproject.ui.markdown.EndSpace
import com.example.newproject.ui.markdown.NoteImageLoader
import com.example.newproject.domain.markdown.NoteSectionModel
import com.example.newproject.ui.markdown.NoteImageMeasurements
import com.example.newproject.ui.markdown.firstUnmeasuredImageIndex
import com.example.newproject.ui.markdown.imageBlockRefs
import com.example.newproject.ui.shouldReportReadingProgress
import com.example.newproject.ui.theme.AccentGlass
import com.example.newproject.ui.theme.OnSurface
import com.example.newproject.ui.theme.OnVibrant
import com.example.newproject.ui.theme.ReadingSurfaceShape
import com.example.newproject.ui.theme.notePaperColor
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull

// ---------------------------------------------------------------------------
// ノート表示の共用部品。
// タブ（[NoteReaderTab]）と全画面（[FullscreenNoteScreen]）の両方から使うものだけを置く。
// 片方でしか使わないものはそれぞれのファイルに private で置く。
// ---------------------------------------------------------------------------

/**
 * 読書痕跡（ReadingTrace）へ「どこまで読んだか」を報告する。
 * 通常タブと全画面は別の [LazyListState] を持つため、両方から呼ぶ。
 *
 * 先頭可視ではなく**最終可視**ブロックを見るのは、先頭基準だと1画面に収まる分だけ
 * 末尾に届かず「読み切った」を表現できないため。
 *
 * ブロックの index だけでなく、そのブロックがどこまで見えているか（可視割合）も送る。
 * 長大な段落・コードブロックは1ブロックとして描画されるため、index だけでは冒頭しか
 * 見ていなくても最終ブロック＝到達率100%になってしまう（→ [visibleFractionOfBlock]）。
 */
@Composable
internal fun ReadingProgressReporter(
    sectionModel: NoteSectionModel?,
    listState: LazyListState,
    // 寸法未確定の画像がある間は、その後ろを報告しない（→ shouldReportReadingProgress）。
    // null なら抑止しない（画像を持たない文脈で使い回すため）。
    imageMeasurements: NoteImageMeasurements?,
    onReadingProgress: (blockIndex: Int, blockFraction: Float, totalBlocks: Int, sectionTitle: String?) -> Unit
) {
    // 長寿命ブロックから外部のラムダを呼ぶので rememberUpdatedState を通す
    // （pointerInput/LaunchedEffect が初回のクロージャを固定する問題への定石）。
    val report by rememberUpdatedState(onReadingProgress)
    // 画像の位置はモデルが変わらない限り動かない。**毎フレーム本文を走査しない。**
    val imageBlocks = remember(sectionModel) { imageBlockRefs(sectionModel?.blocks.orEmpty()) }
    LaunchedEffect(sectionModel, imageBlocks) {
        val model = sectionModel ?: return@LaunchedEffect
        snapshotFlow {
            val layout = listState.layoutInfo
            val last = layout.visibleItemsInfo.lastOrNull() ?: return@snapshotFlow null
            // **測れていないものの向こう側は「見えた」と言えない。**
            // 未確定より後ろを弾くのは、確保した仮の高さが元画像の高さの上限では
            // ないため（縦長画像は画面2〜3枚ぶんになり得る）。ここを通すと、
            // まだ読んでいない位置が最深到達点として永続化される。
            // **判定材料は本文の構造と測定キャッシュだけ。** 「いま描かれている画像」から
            // 判定すると、画面外へ出て破棄された瞬間に未確定でなくなり、
            // 測っていないのに後続が通ってしまう（実機で実際にそうなった）。
            val firstUnmeasured = imageMeasurements?.let {
                firstUnmeasuredImageIndex(imageBlocks, it.measuredReferences())
            }
            if (!shouldReportReadingProgress(last.index, firstUnmeasured)) return@snapshotFlow null
            val fraction = visibleFractionOfBlock(
                itemOffset = last.offset,
                itemSize = last.size,
                // 下端の contentPadding は本文が表示されない余白なので実表示域から外す。
                viewportEndOffset = layout.viewportEndOffset - layout.afterContentPadding
            )
            // 割合はスクロール中ずっと変わり続けるので、粗い段階へ落としてから
            // distinctUntilChanged に掛け、報告を必要な回数だけに絞る。
            last.index to quantizeReadingFraction(fraction)
        }
            .filterNotNull()
            .distinctUntilChanged()
            .collect { (index, step) ->
                report(
                    index,
                    step.toFloat() / READING_FRACTION_STEPS,
                    model.blocks.size,
                    model.sectionForBlockIndex(index)?.title
                )
            }
    }
}

/**
 * 丸いアイコンボタン（material-icons 依存を避けるため絵文字/記号を使用）。
 * 既定色はグラデーション背景に置く前提。明色パネルの上に置く場合は
 * containerColor で暗めの下地を指定しないと視認できない。
 */
@Composable
internal fun IconPill(
    symbol: String,
    contentDescription: String,
    modifier: Modifier = Modifier,
    containerColor: Color = AccentGlass,
    symbolSize: TextUnit = 18.sp,
    /** 記号に添える短い文字（進み具合など）。**渡すと丸が文字の幅まで伸びる。** 右寄せの並びでは左へ伸び、隣の丸を動かさない。 */
    label: String? = null,
    /**
     * false のあいだは押せない。**色は変えない** — 40dp の円に減光を足すと
     * 小さい部品に濃い装飾を移す失敗型に当たる（→ ui_design_principles §3）。
     * 止まっていることは記号と読み上げ文が伝える。
     */
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    // contentDescription を実際にSemanticsへ設定し、絵文字記号は読み上げ対象から外す。
    val description = contentDescription
    Surface(
        modifier = modifier
            .then(
                if (label == null) Modifier.size(ICON_PILL_SIZE)
                else Modifier.height(ICON_PILL_SIZE).widthIn(min = ICON_PILL_SIZE)
            )
            .clickable(enabled = enabled, onClick = onClick)
            .semantics {
                this.contentDescription = description
                role = Role.Button
            },
        shape = CircleShape,
        color = containerColor
    ) {
        Box(
            modifier = Modifier.clearAndSetSemantics {},
            contentAlignment = Alignment.Center
        ) {
            if (label == null) {
                Text(symbol, color = OnVibrant, fontSize = symbolSize, fontWeight = FontWeight.Bold)
            } else {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(symbol, color = OnVibrant, fontSize = symbolSize, fontWeight = FontWeight.Bold)
                    Text(label, color = OnVibrant, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                }
            }
        }
    }
}

/** [IconPill] の直径。見出しの題名の行はこの高さを最初から持つ（→ [GradientHeader]）。 */
internal val ICON_PILL_SIZE = 40.dp

/** プレートの中の余白（→ features/note_typesetting.md §5.1）。 */
private val PLATE_PADDING_TOP = 26.dp
private val PLATE_PADDING_HORIZONTAL = 22.dp
private val PLATE_PADDING_BOTTOM = 30.dp

/** 上端の光が消えるまでの深さ。 */
private val PLATE_LIGHT_DEPTH = 240.dp

/** プレートを場から浮かせる影。ダークでは影が見えないので、縁（`ReadingPlateRim`）で浮かせる。 */
private val PLATE_ELEVATION = 4.dp

/** 本文の紙の見た目の要素。[readingPlateLook] が浮かせるかどうかから決める。 */
internal data class ReadingPlateLook(val rounded: Boolean, val shadow: Boolean, val rim: Boolean, val topLight: Boolean)

/**
 * 本文の紙の見た目（→ features/note_typesetting.md §5.1）。浮かせる紙（通常表示）は角・影・縁・上端の光を持つ。
 * **浮かせない紙（全画面）は画面そのものなので、どれも持たない** — 角や光を残すと、安全領域の下から別の面が始まって見える。
 */
internal fun readingPlateLook(floating: Boolean): ReadingPlateLook =
    ReadingPlateLook(rounded = floating, shadow = floating, rim = floating, topLight = floating)

/** ノート本文パネル（通常表示・全画面で共用）。 */
@Composable
internal fun NoteContentPanel(
    uiState: NoteUiState,
    modifier: Modifier = Modifier,
    listState: LazyListState? = null,
    precomputedBlocks: List<MarkdownBlock>? = null,
    imageLoader: NoteImageLoader? = null,
    /** 画像の寸法を通常表示と全画面で共有する（→ NoteImageMeasurements）。 */
    imageMeasurements: NoteImageMeasurements? = null,
    /** 見出しの脇に置くもの（→ [MarkdownNoteContent]）。通常表示の余白メモの印だけが使う。 */
    headingAccessory: (@Composable (blockIndex: Int) -> Unit)? = null,
    /** 本文のリンクを押したとき（→ features/note_links.md）。null ならリンクは押せない。 */
    onLink: ((NoteLink) -> Unit)? = null,
    /** 選択メニューの「引用して書く」の受け手（→ features/margin_quote.md §5.1）。null の間は項目を出さない。 */
    onQuote: ((QuoteSelection) -> Unit)? = null,
    /** 選択メニューが出ている間の範囲の書き先。机の札だけが渡す（→ [QuoteOffers]）。 */
    quoteOffers: QuoteOffers? = null,
    /** 見出しを上端に置くための末尾の余白。[listState] の持ち主が同じ寿命で持つ（→ [EndSpace]）。 */
    endSpace: EndSpace? = null,
    /**
     * 場の上に浮かせるか（→ features/note_typesetting.md §5.1）。通常表示は浮かせ、全画面は画面全体が紙なので浮かせない
     * （影と縁を付けると、紙と同じ色の地の上に紙の輪郭だけが見える）。
     */
    floating: Boolean = true,
    /** 題の欄のフォルダの道筋（→ [noteFolderLabel]）。null なら出さない。 */
    folder: String? = null,
    /** 題の欄のタグ。本文の解析結果から渡す。 */
    tags: List<String> = emptyList()
) {
    val look = readingPlateLook(floating)
    Surface(
        modifier = modifier.fillMaxWidth(),
        // 紙の地色は放置期間で決まる。演出が無効なら現行の `Panel` と同値が返る。
        color = notePaperColor(uiState.notePaperTone),
        // **形は「読む面」の役割から引く。** 値を直に書くと、冊子との区別が
        // 「揃えたほうが綺麗」の一言で消える（→ system/bearing_channels.md §6）。
        shape = if (look.rounded) ReadingSurfaceShape else RectangleShape,
        shadowElevation = if (look.shadow) PLATE_ELEVATION else 0.dp,
        border = if (look.rim) BorderStroke(1.dp, ReadingPlateRim) else null
    ) {
        // 紙の上端からごく淡い光を重ねる（ダークだけ。ライトは透明で何も描かない）。光はどのノートでも同じで何も表さない。
        val plateLight = ReadingPlateLight
        BoxWithConstraints(
            modifier = if (look.topLight) {
                Modifier.drawBehind {
                    drawRect(Brush.verticalGradient(listOf(plateLight, Color.Transparent), endY = PLATE_LIGHT_DEPTH.toPx()))
                }
            } else {
                Modifier
            }
        ) {
            // 本文の列を最大幅で中央に置く。題も同じ余白で本文の頭にそろえる。
            val inset = MarkdownBodyStyle.columnInset(maxWidth - PLATE_PADDING_HORIZONTAL * 2)
            Column(
                modifier = Modifier.padding(
                    start = PLATE_PADDING_HORIZONTAL,
                    end = PLATE_PADDING_HORIZONTAL,
                    top = PLATE_PADDING_TOP,
                    bottom = PLATE_PADDING_BOTTOM
                )
            ) {
                val state = uiState.noteState
                if (state !is NoteState.Success) {
                    // 案内の文は最初の1枚を引く前の場と同じ1か所から読む。読み込み中は文を出さず、紙だけを出す。
                    emptyPlaceText(state, uiState.vaultSelected)?.let { EmptyPlaceSentence(it, Modifier.padding(horizontal = inset)) }
                    Spacer(modifier = Modifier.weight(1f))
                } else {
                    NoteTitleBlock(state.title, folder, tags, modifier = Modifier.padding(horizontal = inset))
                    // ノート本文は解析が届くまで描かない。ここを描くと [MarkdownNoteContent] の
                    // フォールバックが働き、**最大1MBの本文をMain上で解析し直してしまう**（Main外へ逃がした意味がなくなる）。
                    if (precomputedBlocks != null) {
                        if (listState != null) {
                            MarkdownNoteContent(
                                content = state.content,
                                modifier = Modifier.padding(top = MarkdownBodyStyle.TitleGap).weight(1f),
                                listState = listState,
                                precomputedBlocks = precomputedBlocks,
                                imageLoader = imageLoader,
                                imageMeasurements = imageMeasurements,
                                headingAccessory = headingAccessory,
                                onLink = onLink,
                                // 今のノートの本文はいつも置ける本文として組む。
                                quotable = true,
                                onQuote = onQuote,
                                quoteOffers = quoteOffers,
                                endSpace = endSpace,
                                columnInset = inset
                            )
                        } else {
                            MarkdownNoteContent(
                                content = state.content,
                                modifier = Modifier.padding(top = MarkdownBodyStyle.TitleGap).weight(1f),
                                precomputedBlocks = precomputedBlocks,
                                imageLoader = imageLoader,
                                imageMeasurements = imageMeasurements,
                                onLink = onLink,
                                columnInset = inset
                            )
                        }
                    } else {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

/** 「Vaultを選択」のボタンの文字。案内の文（[emptyPlaceText]）もこの名前で指す。 */
internal const val SELECT_VAULT_LABEL = "Vaultを選択"

/** 「別のノートをひらく」のボタンの文字。案内の文（[emptyPlaceText]）もこの名前で指す。 */
internal const val DRAW_NOTE_LABEL = "別のノートをひらく"

/**
 * ノートが出ていないときの案内の文（→ features/note_typesetting.md §5.7）。**最初の1枚を引く前の場と本文の紙が、ここ1か所から読む。**
 * 指すボタンの名前はボタンの文字と同じ定数から作るので、ボタンの名前を変えても文だけ古くならない。読み込み中とノートを出している間は null。
 * 失敗の2つは失敗が分かる言葉で伝える（色だけで伝えない）。
 */
internal fun emptyPlaceText(noteState: NoteState, vaultSelected: Boolean): String? = when (noteState) {
    is NoteState.Success, NoteState.Loading -> null
    NoteState.Empty -> "このVaultにMarkdownノートが見つかりませんでした"
    is NoteState.Error -> "Vaultを読み込めませんでした"
    NoteState.Idle -> if (vaultSelected) {
        "「$DRAW_NOTE_LABEL」で、今日の1枚を引きます"
    } else {
        "「$SELECT_VAULT_LABEL」から、読むノートのフォルダを選びます"
    }
}

/** 案内の1文。題は出さず、藍の短い線と文だけで静かに待つ。 */
@Composable
internal fun EmptyPlaceSentence(text: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Box(
            modifier = Modifier
                .size(MarkdownBodyStyle.HeadingMarkWidth, MarkdownBodyStyle.HeadingMarkHeight)
                .background(ReadingAccent, RoundedCornerShape(1.dp))
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(text = text, color = OnSurface, style = LocalTextStyle.current.merge(MarkdownBodyStyle.Paragraph))
    }
}

/**
 * 最初の1枚を引く前の場。本文の紙（[NoteContentPanel]）と同じ形・色・浮き・余白のプレートで、[text] があれば上に置く。
 * 呼び手は本文の紙と同じ位置・大きさ（伸ばした高さ）で置く。
 */
@Composable
internal fun EmptyPlacePanel(text: String?, modifier: Modifier = Modifier) {
    val plateLight = ReadingPlateLight
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = notePaperColor(NotePaperTone.Fresh),
        shape = ReadingSurfaceShape,
        shadowElevation = PLATE_ELEVATION,
        border = BorderStroke(1.dp, ReadingPlateRim)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawBehind { drawRect(Brush.verticalGradient(listOf(plateLight, Color.Transparent), endY = PLATE_LIGHT_DEPTH.toPx())) }
                .padding(
                    start = PLATE_PADDING_HORIZONTAL,
                    end = PLATE_PADDING_HORIZONTAL,
                    top = PLATE_PADDING_TOP,
                    bottom = PLATE_PADDING_BOTTOM
                )
        ) {
            if (text != null) EmptyPlaceSentence(text)
        }
    }
}
