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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
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
import androidx.compose.ui.unit.dp
import com.example.newproject.domain.NoteLink
import com.example.newproject.domain.markdown.ListItem
import com.example.newproject.domain.markdown.listMarkerText
import com.example.newproject.domain.markdown.MarkdownBlock
import com.example.newproject.domain.markdown.QuoteContent
import com.example.newproject.domain.markdown.renderedRows
import com.example.newproject.domain.markdown.parseMarkdownBlocks
import com.example.newproject.domain.markdown.sourceText
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
    endSpace: EndSpace? = null
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
                    contentPadding = PaddingValues(bottom = endPadding),
                    verticalArrangement = Arrangement.spacedBy(MarkdownBodyStyle.BlockSpacing)
                ) {
                    items(blocks.size) { i ->
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
                            is MarkdownBlock.Paragraph     -> MarkdownParagraph(block.text)
                            is MarkdownBlock.Image         -> MarkdownImage(block, imageLoader, imageMeasurements)
                            is MarkdownBlock.ListBlock     -> MarkdownList(block.items)
                            is MarkdownBlock.CodeBlock     -> MarkdownCodeBlock(block.code)
                            is MarkdownBlock.HorizontalRule -> MarkdownHorizontalRule()
                            is MarkdownBlock.Blockquote    -> MarkdownBlockquote(block.content)
                            is MarkdownBlock.Table         -> MarkdownTable(block.headers, block.rows)
                        }
                    }
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
    val linkTaps = LocalNoteLinkTaps.current
    return remember(text, colors, linkTaps) { inlineMarkdown(text, colors, linkTaps) }
}

@Composable
internal fun MarkdownHeading(block: MarkdownBlock.Heading) {
    MarkdownHeadingText(block.level, rememberInline(block.text))
}

/** 見出しの文字。見開きは割った切れ端（[text] の一部）をこれで描く。 */
@Composable
internal fun MarkdownHeadingText(level: Int, text: AnnotatedString, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = if (level >= 5) OnSurfaceMuted else OnSurface,
        style = LocalTextStyle.current.merge(MarkdownBodyStyle.heading(level)),
        modifier = modifier.padding(top = MarkdownBodyStyle.headingTopPadding(level))
    )
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
 * 引用。コールアウトの頭と中のブロックを、縦線の内側に並べる（→ docs/dev/system/markdown_rendering.md §8）。
 * 中の並べ方は見開きの計測（`measureSpreadBlock`）と同じ寸法で組む。
 */
@Composable
internal fun MarkdownBlockquote(content: QuoteContent) {
    QuoteFrame {
        content.callout?.let { MarkdownCalloutHeader(calloutLabelOf(it), rememberInline(it.title), showLabel = true) }
        content.blocks.forEach { MarkdownQuoteChild(it) }
    }
}

/**
 * 引用の縦線と、その内側の並び。縦線は内側の高さに合わせて描く（中に表やリストが入っても、内側の高さを測り直さずに済む）。
 * 見開きは割った切れ端をこれで囲む。
 */
@Composable
internal fun QuoteFrame(content: @Composable ColumnScope.() -> Unit) {
    val barColor = CheckboxOutline
    Column(
        modifier = Modifier
            .padding(vertical = MarkdownBodyStyle.QuoteVerticalPadding)
            .drawBehind {
                drawRoundRect(
                    color = barColor,
                    size = Size(MarkdownBodyStyle.QuoteBarWidth.toPx(), size.height),
                    cornerRadius = CornerRadius(2.dp.toPx())
                )
            }
            .padding(start = MarkdownBodyStyle.QuoteBarWidth + MarkdownBodyStyle.QuoteTextStart),
        verticalArrangement = Arrangement.spacedBy(MarkdownBodyStyle.BlockSpacing),
        content = content
    )
}

/** 引用の中の1ブロック。段落は行ごとに、リストは引用の文字で描く。 */
@Composable
private fun MarkdownQuoteChild(block: MarkdownBlock) {
    when (block) {
        is MarkdownBlock.Paragraph      -> MarkdownQuoteLines(block.text.lines().map { rememberInline(it) })
        is MarkdownBlock.ListBlock      -> MarkdownList(block.items, quoted = true)
        is MarkdownBlock.Blockquote     -> MarkdownBlockquote(block.content)
        is MarkdownBlock.Heading        -> MarkdownHeading(block)
        is MarkdownBlock.Table          -> MarkdownTable(block.headers, block.rows)
        is MarkdownBlock.CodeBlock      -> MarkdownCodeBlock(block.code)
        MarkdownBlock.HorizontalRule    -> MarkdownHorizontalRule()
        // 中身の解析が画像を段落へ替えているので届かない。届いても原文の文字で出す。
        is MarkdownBlock.Image          -> MarkdownQuoteLines(listOf(rememberInline(block.sourceText())))
    }
}

/** 引用の中の行。**1行ずつ別の文字として描く**（照らし直しの文字は行の間に何も挟まない → `MemoPassages`）。見開きは割った切れ端の行をこれで描く。 */
@Composable
internal fun MarkdownQuoteLines(lines: List<AnnotatedString>) {
    val style = LocalTextStyle.current.merge(MarkdownBodyStyle.Quote)
    Column {
        lines.forEach { line -> Text(text = line, color = OnSurfaceSubtle, style = style) }
    }
}

/**
 * コールアウトの頭。種類の記号と言葉の後に題を続け、言葉と題の基線を揃える。**記号と言葉は選べない**（ノートに無い文字 → [CalloutLabel]）。
 * 言葉が長ければ題を次の行へ送る（→ [calloutTitleBelow]）。題はいつも正の幅を持つ。
 * [showLabel] が false なら見開きで前のページから続く題の続きで、同じ行なら言葉の幅だけ空け、次の行へ送った形なら言葉を置かない。
 * 見開きの計測（`measureCalloutHeader`）はこの並べ方と同じ寸法で測る。
 */
@Composable
internal fun MarkdownCalloutHeader(label: CalloutLabel, title: AnnotatedString, showLabel: Boolean) {
    val gap = MarkdownBodyStyle.CalloutLabelGap
    Layout(
        content = {
            DisableSelection {
                Text(
                    text = "${label.mark} ${label.text}",
                    color = OnSurfaceSubtle,
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
    val borderColor = ContentDivider
    val headerStyle = LocalTextStyle.current.merge(MarkdownBodyStyle.TableHeader)
    val cellStyle = LocalTextStyle.current.merge(MarkdownBodyStyle.TableCell)
    val cellPadding = Modifier.padding(
        horizontal = MarkdownBodyStyle.TableCellPaddingHorizontal,
        vertical = MarkdownBodyStyle.TableCellPaddingVertical
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, borderColor, RoundedCornerShape(4.dp))
    ) {
        headers.forEach { header ->
            Row(modifier = Modifier.background(CodePanel)) {
                header.forEachIndexed { i, text ->
                    Text(
                        text = text,
                        color = OnSurface,
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

@Composable
internal fun MarkdownParagraph(text: String) {
    MarkdownParagraphText(rememberInline(text))
}

/** 段落の文字。見開きは割った切れ端（[text] の一部）をこれで描く。 */
@Composable
internal fun MarkdownParagraphText(text: AnnotatedString) {
    Text(text = text, color = OnSurface, style = LocalTextStyle.current.merge(MarkdownBodyStyle.Paragraph))
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
                        Text(
                            // 番号付きは原文の表記をそのまま出す。自動採番すると `1. 1. 1.` と
                            // 書かれたノートで表示が原文と食い違う（本アプリは編集器ではない）。
                            text = listMarkerText(item),
                            color = textColor,
                            style = style,
                            modifier = markerAlpha
                        )
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

/** リストの文字の書式。引用の中は引用の書式で、見開きの計測も同じものを読む。 */
internal fun listTextStyle(quoted: Boolean): TextStyle = if (quoted) MarkdownBodyStyle.Quote else MarkdownBodyStyle.Paragraph

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
            style = LocalTextStyle.current.merge(MarkdownBodyStyle.Code),
            modifier = Modifier.padding(MarkdownBodyStyle.CodePadding)
        )
    }
}

/**
 * 選択の容器の見えている上端と、本文の一覧の上端（どちらも窓の座標）。選択メニューが渡す範囲の矩形と同じ座標で、
 * 一覧の見えている項目の位置を窓の座標へ直すのに使う（→ [quoteSourceAt]）。
 */
private class QuoteGeometry {
    var visibleTop = 0f
    var listTop = 0f
}
