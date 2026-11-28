package com.example.newproject.ui.markdown

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.draw.alpha
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.newproject.domain.NoteLink
import com.example.newproject.domain.markdown.ListItem
import com.example.newproject.domain.markdown.ListMarker
import com.example.newproject.domain.markdown.listMarkerText
import com.example.newproject.domain.markdown.MarkdownBlock
import com.example.newproject.domain.markdown.QuoteContent
import com.example.newproject.domain.markdown.renderedRows
import com.example.newproject.domain.markdown.parseMarkdownBlocks
import com.example.newproject.domain.markdown.sourceText
import com.example.newproject.ui.theme.ReadingAccent
import com.example.newproject.ui.theme.ReadingChip
import com.example.newproject.ui.theme.ContentDivider
import com.example.newproject.ui.theme.OnSurfaceFaint
import com.example.newproject.ui.theme.OnSurfaceMuted
import com.example.newproject.ui.theme.OnSurfaceSubtle
import com.example.newproject.ui.theme.CodePanel
import com.example.newproject.ui.theme.ChatDivider
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.TextUnit
import androidx.compose.foundation.layout.defaultMinSize
import com.example.newproject.ui.theme.ReadingBadge
import com.example.newproject.ui.theme.CalloutFaces
import com.example.newproject.ui.theme.CalloutFaceTone
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.foundation.text.InlineTextContent
import com.example.newproject.ui.theme.CodeNumber
import com.example.newproject.ui.theme.CodeComment
import com.example.newproject.ui.theme.CodeString
import com.example.newproject.ui.theme.CodeKeyword
import com.example.newproject.domain.markdown.CodeTokenKind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import com.example.newproject.ui.theme.OnCodeWindow
import com.example.newproject.ui.theme.CodeWindowRule
import com.example.newproject.ui.theme.CodeWindowLabel
import com.example.newproject.ui.theme.CodeWindow
import com.example.newproject.ui.theme.OnSurface

@Composable
internal fun MarkdownNoteContent(
    content: String,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    // セクションモデル側で既にパース済みなら渡して再パースを避ける
    precomputedBlocks: List<MarkdownBlock>? = null,
    // null なら画像を読み込まず原文のまま出す（画像を持たない文脈で使い回すため）
    imageLoader: NoteImageLoader? = null,
    // 画像の寸法を通常表示と全画面で共有する入れ物。null なら共有しない
    // （画像を持たない文脈や、進捗を報告しない画面で使い回すため）。
    imageMeasurements: NoteImageMeasurements? = null,
    // 見出しの脇に置くもの（ブロック番号を受け取る）。null なら見出しだけを描く。
    // **ブロックを増やさずに脇へ置く** — LazyColumn の index がずれると、節の判定と進捗の報告が壊れる。
    headingAccessory: (@Composable (blockIndex: Int) -> Unit)? = null,
    // ノートと見出しへのリンクを押したとき（→ features/note_links.md）。null ならリンクは押せない文字列のまま描く。
    onLink: ((NoteLink) -> Unit)? = null,
    // 選択メニューに「引用して書く」を置ける本文か（→ features/margin_quote.md §5.1）。**今のノートの本文だけが真にし、呼ぶ場所ごとに固定する。**
    quotable: Boolean = false,
    // 「引用して書く」の受け手。null の間は項目を出さない。
    onQuote: ((QuoteSelection) -> Unit)? = null,
    // 選択メニューが出ている間の範囲の書き先。机の札だけが渡す（→ QuoteOffers）。
    quoteOffers: QuoteOffers? = null,
    // 見出しを上端に置くための末尾の余白（→ EndSpace）。null なら末尾に余白を足さない。
    endSpace: EndSpace? = null,
    // 本文の列を中央に置く左右の余白（→ MarkdownBodyStyle.columnInset）。一覧は幅いっぱいのまま内側に足す。
    columnInset: Dp = 0.dp,
    // 本文の末尾に並べるタグの札（→ NoteTagRow）。**最後のブロックと同じ項目の中に置き、一覧の項目を増やさない** —
    // 項目の番号はブロックの番号として節の判定・進捗の報告・末尾の余白が読むので、ブロックでない項目を足すと番号の外を指す。
    tags: List<String> = emptyList()
) {
    val blocks = remember(content, precomputedBlocks) {
        precomputedBlocks ?: parseMarkdownBlocks(content)
    }
    // リンクの装飾は文字列ごとに覚えるので、口は作り直さずに中身だけ差し替える。
    val currentOnLink by rememberUpdatedState(onLink)
    val linkTaps = remember(onLink != null) { if (onLink != null) NoteLinkTaps { currentOnLink?.invoke(it) } else null }

    // 範囲の矩形（窓の座標）から始まりのブロックを決める材料。描き直しには使わないので状態にしない。
    val quoteGeometry = remember { QuoteGeometry() }
    val selectionAt: (String, Rect) -> QuoteSelection = { text, rect ->
        val boxes = listState.layoutInfo.visibleItemsInfo.map { item ->
            BlockBox(item.index, quoteGeometry.listTop + item.offset, quoteGeometry.listTop + item.offset + item.size)
        }
        // 一覧のいちばん上で張り付いているなら、始まりは見えている（→ quoteSourceAt）。
        val contentAbove = listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0
        val at = quoteSourceAt(rect.top, quoteGeometry.visibleTop, boxes, contentAbove)
        QuoteSelection(text, at.source, at.hint)
    }

    val endRequest = endSpace?.wanted
    if (endSpace != null && endRequest != null) {
        LaunchedEffect(endSpace, endRequest) { settleAtTop(listState, endSpace, endRequest) }
    }
    val endPadding = with(LocalDensity.current) { (endSpace?.bottomPx ?: 0).toDp() }

    QuoteSelectionContainer(quotable = quotable, onQuote = onQuote, selectionAt = selectionAt, modifier = modifier, offers = quoteOffers) {
        CompositionLocalProvider(LocalNoteLinkTaps provides linkTaps) {
            // **長押しの見張りは選択の容器の修飾子に付けない。** 付けると押下の途中で見張りが作り直され
            // （PointerInputResetException）、指離しを受け取れずに長押しでもリンクが開く。容器の修飾子は選択の状態で並びが変わる。
            // 見張りだけを持つ内側の層に置き、最小の大きさも渡して本文の並べ方を変えない。
            Box(
                modifier = (linkTaps?.let { Modifier.watchLinkPresses(it) } ?: Modifier)
                    .onGloballyPositioned { quoteGeometry.visibleTop = it.boundsInRoot().top },
                propagateMinConstraints = true
            ) {
                // **幅は内容で決めず、置かれた幅いっぱいに組む。** 机の画面は一覧の幅の変化で、広がる途中の縮みと送りを見分ける
                // （→ lazyFaceMoved）。内容で決めると、表示中の段落が替わるだけで幅が変わり、リンクの送りを縮みと取り違える。
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .onGloballyPositioned { quoteGeometry.listTop = it.positionInRoot().y },
                    contentPadding = PaddingValues(start = columnInset, end = columnInset, bottom = endPadding),
                    verticalArrangement = Arrangement.spacedBy(MarkdownBodyStyle.BlockSpacing)
                ) {
                    items(blocks.size) { i ->
                        if (i == blocks.lastIndex && tags.isNotEmpty()) {
                            Column {
                                MarkdownBlockItem(blocks, i, headingAccessory, imageLoader, imageMeasurements)
                                Spacer(modifier = Modifier.height(MarkdownBodyStyle.BlockSpacing))
                                NoteTagRow(tags)
                            }
                        } else {
                            MarkdownBlockItem(blocks, i, headingAccessory, imageLoader, imageMeasurements)
                        }
                    }
                }
            }
        }
    }
}

/** 本文の一覧の1項目。[i] 番目のブロックを描く。 */
@Composable
private fun MarkdownBlockItem(
    blocks: List<MarkdownBlock>,
    i: Int,
    headingAccessory: (@Composable (blockIndex: Int) -> Unit)?,
    imageLoader: NoteImageLoader?,
    imageMeasurements: NoteImageMeasurements?
) {
    when (val block = blocks[i]) {
        is MarkdownBlock.Heading       -> if (headingAccessory == null) {
            MarkdownHeading(block)
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.weight(1f)) { MarkdownHeading(block) }
                // 脇の印は本文ではないので、選択のコピーに入れない（→ features/margin_quote.md §11）。
                DisableSelection { headingAccessory(i) }
            }
        }
        is MarkdownBlock.Paragraph     -> MarkdownParagraph(block.text, lead = isLeadParagraph(i, block))
        is MarkdownBlock.Image         -> MarkdownImage(block, imageLoader, imageMeasurements)
        is MarkdownBlock.ListBlock     -> MarkdownList(block.items)
        is MarkdownBlock.CodeBlock     -> MarkdownCodeBlock(rememberCodeText(block), block.language)
        is MarkdownBlock.HorizontalRule -> MarkdownHorizontalRule()
        is MarkdownBlock.Blockquote    -> MarkdownBlockquote(block.content)
        is MarkdownBlock.Table         -> MarkdownTable(block.headers, block.rows)
    }
}

// inlineMarkdown（AnnotatedString構築）は軽くないため、再コンポジションの
// たびに作り直さないようテキスト単位でメモ化する。
@Composable
private fun rememberInline(text: String): AnnotatedString {
    // 色はテーマ由来なので、非Composableな remember ブロックの外で読んでキーに含める。
    val colors = InlineMarkdownColors(
        strikethrough = OnSurfaceFaint,
        codeBackground = ReadingChip,
        link = ReadingAccent
    )
    val linkTaps = LocalNoteLinkTaps.current
    return remember(text, colors, linkTaps) { inlineMarkdown(text, colors, linkTaps) }
}

@Composable
internal fun MarkdownHeading(block: MarkdownBlock.Heading) {
    MarkdownHeadingText(block.level, rememberInline(block.text))
}

/**
 * 見出しの文字。見開きは割った切れ端（[text] の一部）をこれで描く。
 * 文字より上に積む高さ（上の余白・線・線の下の間）は、見開きの計測が [MarkdownBodyStyle.headingSpaceAbovePx] で同じだけ足す。
 */
@Composable
internal fun MarkdownHeadingText(level: Int, text: AnnotatedString, modifier: Modifier = Modifier) {
    Column(modifier = modifier.padding(top = MarkdownBodyStyle.headingTopPadding(level))) {
        if (MarkdownBodyStyle.hasHeadingMark(level)) {
            // 線は飾りなので、読み上げと選択のコピーに入れない（Box は文字を持たない）。
            Box(
                modifier = Modifier
                    .size(MarkdownBodyStyle.HeadingMarkWidth, MarkdownBodyStyle.HeadingMarkHeight)
                    .background(ReadingAccent, RoundedCornerShape(1.dp))
            )
            Spacer(modifier = Modifier.height(MarkdownBodyStyle.HeadingMarkGap))
        }
        Text(
            text = text,
            color = if (level >= 5) OnSurfaceMuted else OnSurface,
            style = LocalTextStyle.current.merge(MarkdownBodyStyle.heading(level))
        )
    }
}

@Composable
internal fun MarkdownHorizontalRule() {
    HorizontalDivider(
        color = ContentDivider,
        thickness = MarkdownBodyStyle.RuleThickness,
        modifier = Modifier.padding(vertical = MarkdownBodyStyle.RuleVerticalPadding)
    )
}

/**
 * 引用。コールアウトの頭と中のブロックを、縦線の内側か、種類ごとの面の内側に並べる（→ docs/dev/system/markdown_rendering.md §8・
 * features/note_typesetting.md §5.4）。中の並べ方は見開きの計測（`measureSpreadBlock`）と同じ寸法で組む。
 */
@Composable
internal fun MarkdownBlockquote(content: QuoteContent) {
    val face = calloutFaceOf(content.callout)
    QuoteOrFaceFrame(face) {
        content.callout?.let { MarkdownCalloutHeader(calloutLabelOf(it), rememberInline(it.title), showLabel = true, face = face) }
        content.blocks.forEach { MarkdownQuoteChild(it, pull = face == null) }
    }
}

/**
 * [face] があればコールアウトの面で、無ければ引用符の字下げで囲む。見開きは割った切れ端をこれで囲み、
 * 引用符は最初の切れ端だけに描く（[showMark]）。
 */
@Composable
internal fun QuoteOrFaceFrame(face: CalloutFace?, showMark: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    if (face == null) QuoteFrame(showMark, content) else CalloutFaceFrame(face, content)
}

/**
 * コールアウトの面。紙に薄い面を敷き、枠線は付けない（大きい面に輪郭を足さない → ui_design_principles §3）。
 * 内側の幅と上下の余白は、見開きの計測（[quoteInnerWidthPx]・`measureSpreadBlock`）が同じ値で引く。
 */
@Composable
internal fun CalloutFaceFrame(face: CalloutFace, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(calloutFaceTone(face).surface, RoundedCornerShape(MarkdownBodyStyle.CalloutCorner))
            .padding(horizontal = MarkdownBodyStyle.CalloutPaddingHorizontal, vertical = MarkdownBodyStyle.CalloutPaddingVertical),
        verticalArrangement = Arrangement.spacedBy(MarkdownBodyStyle.BlockSpacing),
        content = content
    )
}

/** 面の種類の色。 */
@Composable
@ReadOnlyComposable
internal fun calloutFaceTone(face: CalloutFace): CalloutFaceTone = with(CalloutFaces) {
    when (face) {
        CalloutFace.Summary -> summary
        CalloutFace.Question -> question
        CalloutFace.Example -> example
        CalloutFace.Caution -> caution
    }
}

/**
 * 面を敷かない引用。頭に藍の大きな引用符を置き、中身を字下げする（→ features/note_typesetting.md §5.4）。
 * **引用符は字下げの中に描くだけで、並べ方の寸法に入らない**（見開きの計測は字下げと上下の余白だけを足す）。
 * 引用符は本文ではないので、選択のコピーと読み上げに入れない（描いているだけで、文字として置いていない）。
 */
@Composable
internal fun QuoteFrame(showMark: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    val measurer = rememberTextMeasurer()
    val markStyle = LocalTextStyle.current.merge(MarkdownBodyStyle.QuoteMarkText)
    val mark = remember(markStyle) { measurer.measure("\u201C", markStyle) }
    val markColor = ReadingAccent
    Column(
        modifier = Modifier
            .padding(vertical = MarkdownBodyStyle.QuoteVerticalPadding)
            .drawBehind { if (showMark) drawText(mark, color = markColor) }
            .padding(start = MarkdownBodyStyle.QuoteIndent),
        verticalArrangement = Arrangement.spacedBy(MarkdownBodyStyle.BlockSpacing),
        content = content
    )
}

/** 引用の中の1ブロック。段落は行ごとに、リストは引用の文字で描く。[pull] なら面を敷かない引用の行の書式で描く。 */
@Composable
private fun MarkdownQuoteChild(block: MarkdownBlock, pull: Boolean) {
    when (block) {
        is MarkdownBlock.Paragraph      -> MarkdownQuoteLines(block.text.lines().map { rememberInline(it) }, pull)
        is MarkdownBlock.ListBlock      -> MarkdownList(block.items, quoted = true)
        is MarkdownBlock.Blockquote     -> MarkdownBlockquote(block.content)
        is MarkdownBlock.Heading        -> MarkdownHeading(block)
        is MarkdownBlock.Table          -> MarkdownTable(block.headers, block.rows)
        is MarkdownBlock.CodeBlock      -> MarkdownCodeBlock(rememberCodeText(block), block.language)
        MarkdownBlock.HorizontalRule    -> MarkdownHorizontalRule()
        // 中身の解析が画像を段落へ替えているので届かない。届いても原文の文字で出す。
        is MarkdownBlock.Image          -> MarkdownQuoteLines(listOf(rememberInline(block.sourceText())), pull)
    }
}

/** 引用の中の行。**1行ずつ別の文字として描く**（照らし直しの文字は行の間に何も挟まない → `MemoPassages`）。見開きは割った切れ端の行をこれで描く。 */
@Composable
internal fun MarkdownQuoteLines(lines: List<AnnotatedString>, pull: Boolean) {
    val style = LocalTextStyle.current.merge(quoteLineStyle(pull))
    val color = if (pull) OnSurface else OnSurfaceSubtle
    Column {
        lines.forEach { line -> Text(text = line, color = color, style = style) }
    }
}

/** 引用の中の行の書式。面を敷かない引用は [MarkdownBodyStyle.PullQuote]、コールアウトの中身は [MarkdownBodyStyle.Quote]。見開きの計測も同じものを読む。 */
internal fun quoteLineStyle(pull: Boolean): TextStyle = if (pull) MarkdownBodyStyle.PullQuote else MarkdownBodyStyle.Quote

/**
 * コールアウトの頭。種類の記号と言葉の後に題を続け、言葉と題の基線を揃える。**記号と言葉は選べない**（ノートに無い文字 → [CalloutLabel]）。
 * 言葉が長ければ題を次の行へ送る（→ [calloutTitleBelow]）。題はいつも正の幅を持つ。
 * [showLabel] が false なら見開きで前のページから続く題の続きで、同じ行なら言葉の幅だけ空け、次の行へ送った形なら言葉を置かない。
 * 見開きの計測（`measureCalloutHeader`）はこの並べ方と同じ寸法で測る。
 */
@Composable
internal fun MarkdownCalloutHeader(label: CalloutLabel, title: AnnotatedString, showLabel: Boolean, face: CalloutFace?) {
    val gap = MarkdownBodyStyle.CalloutLabelGap
    // 面を持つ種類は、記号の代わりに線画を言葉と同じ色で置く。言葉の幅は線画の置き場所込みで測る（→ calloutLabelWidthPx）。
    val labelColor = face?.let { calloutFaceTone(it).title } ?: OnSurfaceSubtle
    val inlineContent = if (face == null) {
        emptyMap()
    } else {
        val placeholder = calloutLabelPlaceholders(label, face).single().item
        mapOf(CALLOUT_ICON_ID to InlineTextContent(placeholder) { CalloutIcon(face, labelColor) })
    }
    Layout(
        content = {
            DisableSelection {
                Text(
                    text = calloutLabelText(label, face),
                    color = labelColor,
                    inlineContent = inlineContent,
                    style = LocalTextStyle.current.merge(MarkdownBodyStyle.CalloutLabelText),
                    modifier = if (showLabel) Modifier.clearAndSetSemantics { contentDescription = label.text } else Modifier.alpha(0f).clearAndSetSemantics { }
                )
            }
            if (title.isNotEmpty()) {
                Text(text = title, color = OnSurface, style = LocalTextStyle.current.merge(MarkdownBodyStyle.CalloutTitleText))
            }
        }
    ) { measurables, constraints ->
        val labelMeasurable = measurables[0]
        val titleMeasurable = measurables.getOrNull(1)
        val rowWidth = if (constraints.hasBoundedWidth) constraints.maxWidth else Constraints.Infinity
        if (titleMeasurable == null) {
            val labelPlaceable = labelMeasurable.measure(Constraints(maxWidth = rowWidth))
            return@Layout layout(labelPlaceable.width, labelPlaceable.height) { labelPlaceable.place(0, 0) }
        }
        val below = constraints.hasBoundedWidth &&
            calloutTitleBelow(labelMeasurable.maxIntrinsicWidth(Constraints.Infinity).toFloat(), rowWidth.toFloat())
        if (below) {
            val labelPlaceable = if (showLabel) labelMeasurable.measure(Constraints(maxWidth = rowWidth)) else null
            val titlePlaceable = titleMeasurable.measure(Constraints(maxWidth = rowWidth))
            val labelHeight = labelPlaceable?.height ?: 0
            layout(rowWidth, labelHeight + titlePlaceable.height) {
                labelPlaceable?.place(0, 0)
                titlePlaceable.place(0, labelHeight)
            }
        } else {
            val labelPlaceable = labelMeasurable.measure(Constraints(maxWidth = rowWidth))
            val gapPx = gap.roundToPx()
            val titleWidth = if (constraints.hasBoundedWidth) (rowWidth - labelPlaceable.width - gapPx).coerceAtLeast(1) else Constraints.Infinity
            val titlePlaceable = titleMeasurable.measure(Constraints(maxWidth = titleWidth))
            val top = maxOf(labelPlaceable[FirstBaseline], titlePlaceable[FirstBaseline])
            val labelY = top - labelPlaceable[FirstBaseline]
            val titleY = top - titlePlaceable[FirstBaseline]
            val width = if (constraints.hasBoundedWidth) rowWidth else labelPlaceable.width + gapPx + titlePlaceable.width
            layout(width, maxOf(labelY + labelPlaceable.height, titleY + titlePlaceable.height)) {
                labelPlaceable.place(0, labelY)
                titlePlaceable.place(labelPlaceable.width + gapPx, titleY)
            }
        }
    }
}

@Composable
internal fun MarkdownTable(headers: List<String>, rows: List<List<String>>) {
    val headerTexts = headers.map { rememberInline(it.trim()) }
    val rowTexts = MarkdownBlock.Table(headers, rows).renderedRows().map { row -> row.map { rememberInline(it.trim()) } }
    MarkdownTableView(listOf(headerTexts), rowTexts)
}

/**
 * 表。[headers] は見出しの書式で描く行で、ふつうは1行。見開きでは割れた見出しの行の切れ端が並び、
 * 見出しの行より後ろだけを出す切れ端では空になる。[rows] はセルの文字で、列の数は全行でそろっている前提。
 */
@Composable
internal fun MarkdownTableView(headers: List<List<AnnotatedString>>, rows: List<List<AnnotatedString>>) {
    val borderColor = ChatDivider
    val headerStyle = LocalTextStyle.current.merge(MarkdownBodyStyle.TableHeader)
    val cellStyle = LocalTextStyle.current.merge(MarkdownBodyStyle.TableCell)
    val cellPadding = Modifier.padding(
        horizontal = MarkdownBodyStyle.TableCellPaddingHorizontal,
        vertical = MarkdownBodyStyle.TableCellPaddingVertical
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(MarkdownBodyStyle.TableCorner))
            .border(1.dp, borderColor, RoundedCornerShape(MarkdownBodyStyle.TableCorner))
    ) {
        headers.forEach { header ->
            Row(modifier = Modifier.background(ReadingChip)) {
                header.forEachIndexed { i, text ->
                    Text(
                        text = text,
                        color = OnSurfaceMuted,
                        style = headerStyle,
                        modifier = Modifier
                            .weight(1f)
                            .then(if (i > 0) Modifier.border(width = 1.dp, color = borderColor) else Modifier)
                            .then(cellPadding)
                    )
                }
            }
            HorizontalDivider(color = borderColor, thickness = 1.dp)
        }
        rows.forEach { row ->
            Row {
                row.forEachIndexed { i, text ->
                    Text(
                        text = text,
                        color = OnSurface,
                        style = cellStyle,
                        modifier = Modifier
                            .weight(1f)
                            .then(if (i > 0) Modifier.border(width = 1.dp, color = borderColor) else Modifier)
                            .then(cellPadding)
                    )
                }
            }
            HorizontalDivider(color = borderColor, thickness = 0.5.dp)
        }
    }
}

/** [lead] ならリード文の書式で組む（→ [isLeadParagraph]）。 */
@Composable
internal fun MarkdownParagraph(text: String, lead: Boolean = false) {
    MarkdownParagraphText(rememberInline(text), lead)
}

/** 段落の文字。見開きは割った切れ端（[text] の一部）をこれで描く。 */
@Composable
internal fun MarkdownParagraphText(text: AnnotatedString, lead: Boolean = false) {
    Text(
        text = text,
        color = OnSurface,
        style = LocalTextStyle.current.merge(if (lead) MarkdownBodyStyle.Lead else MarkdownBodyStyle.Paragraph)
    )
}

/** [quoted] なら引用の中のリストで、文字を引用の書式と色で描く。 */
@Composable
internal fun MarkdownList(items: List<ListItem>, quoted: Boolean = false) {
    MarkdownListRows(items.map { MarkdownListRow(it, rememberInline(it.text), showMarker = true) }, quoted)
}

/** リストの1項目の描き方。[showMarker] が false なら記号の幅だけ空ける（見開きで、前のページから続く項目の続き）。 */
internal data class MarkdownListRow(val item: ListItem, val text: AnnotatedString, val showMarker: Boolean)

@Composable
internal fun MarkdownListRows(rows: List<MarkdownListRow>, quoted: Boolean = false) {
    val style = LocalTextStyle.current.merge(listTextStyle(quoted))
    val textColor = if (quoted) OnSurfaceSubtle else OnSurface
    Column(verticalArrangement = Arrangement.spacedBy(MarkdownBodyStyle.ListItemSpacing)) {
        rows.forEach { row ->
            val item = row.item
            val checked = item.checked
            Row(
                modifier = Modifier.padding(start = MarkdownBodyStyle.ListIndentPerDepth * item.depth),
                verticalAlignment = if (checked == null) Alignment.Top else Alignment.CenterVertically
            ) {
                val markerAlpha = if (row.showMarker) Modifier else Modifier.alpha(0f)
                if (checked == null) {
                    val marker: @Composable () -> Unit = {
                        if (item.marker is ListMarker.Ordered) {
                            OrderedBadge(listMarkerText(item), lineHeight = style.lineHeight, modifier = markerAlpha)
                        } else {
                            Text(
                                text = listMarkerText(item),
                                // 箇条の点は読む面のアクセント。引用の中は引用の色のまま。
                                color = if (!quoted) ReadingAccent else textColor,
                                style = style,
                                modifier = markerAlpha
                            )
                        }
                    }
                    // 前のページから続く項目の見えない記号は、選択のコピーに入れない（照らし直しの文字と揃える → features/margin_quote.md §11）。
                    if (row.showMarker) marker() else DisableSelection(marker)
                } else {
                    Checkbox(
                        checked = checked,
                        onCheckedChange = null,
                        modifier = markerAlpha
                            .width(MarkdownBodyStyle.CheckboxSize)
                            .height(MarkdownBodyStyle.CheckboxSize)
                    )
                }
                Spacer(modifier = Modifier.width(MarkdownBodyStyle.ListMarkerGap))
                Text(
                    text = row.text,
                    color = if (checked == true) OnSurfaceFaint else textColor,
                    style = style,
                    textDecoration = if (checked == true) TextDecoration.LineThrough else TextDecoration.None,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

/**
 * 番号付きの箇条の番号の札。番号は原文の表記をそのまま出す（自動採番すると `1. 1. 1.` と書かれたノートで原文と食い違う）。
 * 札は行の高さ [lineHeight] の中で上下の中ほどに置き、行の高さを変えない。幅は見開きの計測（`orderedBadgeWidthPx`）と同じ式で決まる。
 */
@Composable
private fun OrderedBadge(text: String, lineHeight: TextUnit, modifier: Modifier = Modifier) {
    val top = with(LocalDensity.current) { ((lineHeight.toDp() - MarkdownBodyStyle.OrderedBadgeMinSize) / 2).coerceAtLeast(0.dp) }
    Box(
        modifier = modifier
            .padding(top = top)
            .defaultMinSize(minWidth = MarkdownBodyStyle.OrderedBadgeMinSize, minHeight = MarkdownBodyStyle.OrderedBadgeMinSize)
            .background(ReadingBadge, RoundedCornerShape(MarkdownBodyStyle.OrderedBadgeCorner))
            .padding(horizontal = MarkdownBodyStyle.OrderedBadgePaddingHorizontal),
        contentAlignment = Alignment.Center
    ) {
        Text(text = text, color = ReadingAccent, style = LocalTextStyle.current.merge(MarkdownBodyStyle.OrderedBadgeText))
    }
}

/** リストの文字の書式。引用の中は引用の書式で、見開きの計測も同じものを読む。 */
internal fun listTextStyle(quoted: Boolean): TextStyle = if (quoted) MarkdownBodyStyle.Quote else MarkdownBodyStyle.Paragraph

/**
 * コードの枠（暗い窓）。[language] があれば頭の行の右に札を出す（→ features/note_typesetting.md §5.4）。
 * 見開きで割れた切れ端もこれで描き、どの切れ端にも札を出す — 見開きの計測は切れ端ごとに同じ頭の高さを足す。
 * 札は本文ではないので、選択のコピーに入れない。
 */
@Composable
internal fun MarkdownCodeBlock(text: AnnotatedString, language: String?) {
    Surface(
        color = CodeWindow,
        shape = RoundedCornerShape(MarkdownBodyStyle.CodeWindowCorner),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(
                horizontal = MarkdownBodyStyle.CodePaddingHorizontal,
                vertical = MarkdownBodyStyle.CodePaddingVertical
            )
        ) {
            if (language != null) {
                DisableSelection {
                    Text(
                        text = language,
                        color = CodeWindowLabel,
                        style = LocalTextStyle.current.merge(MarkdownBodyStyle.CodeLabel),
                        textAlign = TextAlign.End,
                        maxLines = 1,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Spacer(modifier = Modifier.height(MarkdownBodyStyle.CodeHeaderGap))
                HorizontalDivider(thickness = MarkdownBodyStyle.CodeHeaderRule, color = CodeWindowRule)
                Spacer(modifier = Modifier.height(MarkdownBodyStyle.CodeHeaderGap))
            }
            Text(text = text, color = OnCodeWindow, style = LocalTextStyle.current.merge(MarkdownBodyStyle.Code))
        }
    }
}

/** コードの枠に描く文字（→ [codeAnnotatedString]）。 */
@Composable
private fun rememberCodeText(block: MarkdownBlock.CodeBlock): AnnotatedString =
    remember(block) { codeAnnotatedString(block) }

/**
 * コードの枠に描く文字。末尾の空行は描かず、色分けの切れ目（[MarkdownBlock.CodeBlock.tokens]）に色を付ける。
 * **縦の流しと見開きの計測・描画が同じ文字を使う**（色は字の幅を変えないので、計測には影響しない）。
 */
internal fun codeAnnotatedString(block: MarkdownBlock.CodeBlock): AnnotatedString {
    val text = block.code.trimEnd()
    return buildAnnotatedString {
        append(text)
        block.tokens.forEach { token ->
            val end = minOf(token.end, text.length)
            if (token.start < end) addStyle(SpanStyle(color = codeTokenColor(token.kind)), token.start, end)
        }
    }
}

private fun codeTokenColor(kind: CodeTokenKind): Color = when (kind) {
    CodeTokenKind.Keyword -> CodeKeyword
    CodeTokenKind.StringLiteral -> CodeString
    CodeTokenKind.Comment -> CodeComment
    CodeTokenKind.Number -> CodeNumber
}

/**
 * 選択の容器の見えている上端と、本文の一覧の上端（どちらも窓の座標）。選択メニューが渡す範囲の矩形と同じ座標で、
 * 一覧の見えている項目の位置を窓の座標へ直すのに使う（→ [quoteSourceAt]）。
 */
private class QuoteGeometry {
    var visibleTop = 0f
    var listTop = 0f
}
