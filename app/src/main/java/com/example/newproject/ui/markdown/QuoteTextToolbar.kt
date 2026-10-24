package com.example.newproject.ui.markdown

import android.graphics.Rect as AndroidRect
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.text.AnnotatedString

// ---------------------------------------------------------------------------
// 今のノートの本文の選択メニューに「引用して書く」を足す（→ features/margin_quote.md §5.1・§7・§11）。
//
// Compose 1.7.3 の選択の容器は、メニューを LocalTextToolbar から、コピー先を LocalClipboardManager から読む（バイナリで確かめた）。
// 今のノートの本文の容器の内側だけで両方を差し替える。メニューは端末の浮かぶメニュー（ActionMode の浮動型）で組み、
// コピー先は普段は端末のクリップボードへ渡し、「引用して書く」の間だけ文字を受け取って端末へは渡さない（→ 判断8）。
// ---------------------------------------------------------------------------

/** 「引用して書く」で受け取ったもの。[source] は選んだ範囲の始まりを含むブロック、[hint] は境目で切るためだけの手掛かり（→ `quoteAttachmentFor`）。 */
internal data class QuoteSelection(val text: String, val source: Int?, val hint: Int?)

/** ブロックが窓の座標のどこにあるか。本文の一覧の見えている項目か、見開きのページに組んだ切れ端。 */
internal data class BlockBox(val block: Int, val top: Float, val bottom: Float)

internal data class QuoteSourceAt(val source: Int?, val hint: Int?)

/**
 * 選択メニューが渡す範囲の矩形の上端 [rectTop] から、範囲の始まりを含むブロックを決める（→ features/margin_quote.md §5.2・§11）。
 * [boxes] は上から順に並んだブロックの位置。
 *
 * 選択の容器は、矩形を**容器の見えている範囲に切り詰めて**渡す。見えている範囲の上より前に本文がある（[contentAbove]）のに
 * 上端が見えている範囲の上端 [visibleTop] に張り付いていれば、範囲の始まりは画面の外にありうるので、**引用元は決めない**
 * （[QuoteSourceAt.source] が null）。そのときも見えている最初のブロックを、境目で切るための手掛かりにだけ渡す —
 * 選択のコピーは組まれている部品の文字しか含まないので、受け取った文字はそこから始まる。
 * 前に本文が無ければ（一覧のいちばん上・見開きのページ）、張り付いていても始まりは見えている。
 */
internal fun quoteSourceAt(rectTop: Float, visibleTop: Float, boxes: List<BlockBox>, contentAbove: Boolean): QuoteSourceAt {
    if (contentAbove && rectTop <= visibleTop + CLIPPED_TOLERANCE_PX) {
        return QuoteSourceAt(source = null, hint = boxes.firstOrNull { it.bottom > visibleTop }?.block)
    }
    // 矩形の上端は始まりの行の上端。ブロックの間の余白に落ちたら、その下のブロックで始まっている。
    val block = boxes.firstOrNull { it.bottom > rectTop }?.block
    return QuoteSourceAt(source = block, hint = block)
}

/** 切り詰めた上端と見えている範囲の上端の、丸めの差の許容（px）。 */
private const val CLIPPED_TOLERANCE_PX = 1f

/**
 * 「引用して書く」の間だけ、選んだ文字を受け取って端末のクリップボードへ渡さないコピー先（→ 判断8）。
 * ほかのコピーは端末のクリップボードへそのまま渡す。
 */
internal class QuoteClipboard(private val system: ClipboardManager) : ClipboardManager {
    private var capturing = false
    private var captured: AnnotatedString? = null

    override fun setText(annotatedString: AnnotatedString) {
        if (capturing) captured = annotatedString else system.setText(annotatedString)
    }

    override fun getText(): AnnotatedString? = system.getText()

    override fun hasText(): Boolean = system.hasText()

    /** [copy] の間に渡された文字を受け取って返す。渡されなければ null。 */
    fun capture(copy: () -> Unit): AnnotatedString? {
        capturing = true
        captured = null
        try {
            copy()
        } finally {
            capturing = false
        }
        return captured.also { captured = null }
    }
}

/**
 * 浮かぶ選択メニュー。端末の既定（`AndroidTextToolbar`）と同じく ActionMode の浮動型で出し、項目に「引用して書く」を足す。
 * **「引用して書く」はコピーを通して文字を受け取る** — 選択の容器のコピーは渡した後に選択を解く（`toolbarCopy` が `onRelease` を呼ぶ）。
 */
internal class QuoteTextToolbar(
    private val view: View,
    private val clipboard: QuoteClipboard,
    /** 今の「引用して書く」の受け手。null の間は項目を出さない（並べ読みの間など）。 */
    private val onQuote: () -> ((text: String, rect: Rect) -> Unit)?
) : TextToolbar {
    private var actionMode: ActionMode? = null
    private var rect = Rect.Zero
    private var onCopy: (() -> Unit)? = null
    private var onPaste: (() -> Unit)? = null
    private var onCut: (() -> Unit)? = null
    private var onSelectAll: (() -> Unit)? = null

    override var status: TextToolbarStatus = TextToolbarStatus.Hidden
        private set

    override fun showMenu(
        rect: Rect,
        onCopyRequested: (() -> Unit)?,
        onPasteRequested: (() -> Unit)?,
        onCutRequested: (() -> Unit)?,
        onSelectAllRequested: (() -> Unit)?
    ) {
        this.rect = rect
        onCopy = onCopyRequested
        onPaste = onPasteRequested
        onCut = onCutRequested
        onSelectAll = onSelectAllRequested
        status = TextToolbarStatus.Shown
        val shown = actionMode
        if (shown == null) actionMode = view.startActionMode(callback, ActionMode.TYPE_FLOATING) else shown.invalidate()
    }

    override fun hide() {
        status = TextToolbarStatus.Hidden
        actionMode?.finish()
        actionMode = null
    }

    /** メニューの項目を押した。**試験もここから押す**（浮かぶメニューは Compose の試験の外の窓にある → [LocalQuoteMenuProbe]）。未知の項目なら偽。 */
    internal fun onItem(id: Int): Boolean {
        when (id) {
            ITEM_QUOTE -> quote()
            ITEM_COPY -> onCopy?.invoke()
            ITEM_PASTE -> onPaste?.invoke()
            ITEM_CUT -> onCut?.invoke()
            ITEM_SELECT_ALL -> onSelectAll?.invoke()
            else -> return false
        }
        return true
    }

    /** 「引用して書く」。選択の容器のコピーで文字を受け取り、そのときの範囲の矩形と一緒に渡す。空白だけかは受け取った側が決める。 */
    private fun quote() {
        val receiver = onQuote() ?: return
        val copy = onCopy ?: return
        val at = rect
        val text = clipboard.capture(copy) ?: return
        receiver(text.text, at)
    }

    private val callback = object : ActionMode.Callback2() {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            addItems(menu)
            return true
        }

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
            menu.clear()
            addItems(menu)
            return true
        }

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            // 既知の項目は押した処理に任せる（選択の容器が選択を解いてメニューを閉じる）。未知の項目だけここで閉じる。
            if (onItem(item.itemId)) return true
            mode.finish()
            return false
        }

        override fun onDestroyActionMode(mode: ActionMode) {
            actionMode = null
        }

        override fun onGetContentRect(mode: ActionMode, view: View, outRect: AndroidRect) {
            outRect.set(rect.left.toInt(), rect.top.toInt(), rect.right.toInt(), rect.bottom.toInt())
        }
    }

    private fun addItems(menu: Menu) {
        // 「引用して書く」は選んだ文字をコピーで受け取るので、コピーできるときだけ出す。
        if (onCopy != null && onQuote() != null) menu.add(Menu.NONE, ITEM_QUOTE, 0, QUOTE_ITEM_TITLE).setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        if (onCopy != null) menu.add(Menu.NONE, ITEM_COPY, 1, android.R.string.copy).setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        if (onPaste != null) menu.add(Menu.NONE, ITEM_PASTE, 2, android.R.string.paste).setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        if (onCut != null) menu.add(Menu.NONE, ITEM_CUT, 3, android.R.string.cut).setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        if (onSelectAll != null) menu.add(Menu.NONE, ITEM_SELECT_ALL, 4, android.R.string.selectAll).setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
    }

    internal companion object {
        const val ITEM_QUOTE = 1
        const val ITEM_COPY = 2
        const val ITEM_PASTE = 3
        const val ITEM_CUT = 4
        const val ITEM_SELECT_ALL = 5
    }
}

/**
 * [QuoteSelectionContainer] が作った選択メニューを受け取る口。**instrumentation だけが使う** — 浮かぶメニューは Compose の試験の外の窓に出るので、
 * 試験は受け取ったメニューの [QuoteTextToolbar.onItem] を押す。本番では null で、何もしない。
 */
internal val LocalQuoteMenuProbe = staticCompositionLocalOf<((QuoteTextToolbar) -> Unit)?> { null }

/** 選択メニューの項目の語。押しただけで保存したように見せない（→ features/margin_quote.md 判断1）。 */
internal const val QUOTE_ITEM_TITLE = "引用して書く"

/**
 * 本文の選択の容器。[quotable] が偽なら今の選択の容器のまま（右のノートなど）。真なら、この容器の内側だけでメニューとコピー先を差し替え、
 * [onQuote] がある間だけ「引用して書く」を出す。[onQuote] は範囲の矩形（窓の座標）を受け取る。
 *
 * **[quotable] は呼ぶ場所ごとに固定する。** 途中で替えると容器の組み立てが変わり、本文の一覧が作り直される。
 * 出す・出さないの切り替えは [onQuote] の有無で行う。
 */
@Composable
internal fun QuoteSelectionContainer(
    quotable: Boolean,
    onQuote: ((text: String, rect: Rect) -> Unit)?,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    if (!quotable) {
        SelectionContainer(modifier = modifier, content = content)
        return
    }
    val view = LocalView.current
    val system = LocalClipboardManager.current
    // メニューは選択の容器が長く持つので、押したときの処理は最新のものを読む（→ lessons L34）。
    val currentOnQuote by rememberUpdatedState(onQuote)
    val clipboard = remember(system) { QuoteClipboard(system) }
    val toolbar = remember(view, clipboard) { QuoteTextToolbar(view, clipboard) { currentOnQuote } }
    LocalQuoteMenuProbe.current?.let { probe -> SideEffect { probe(toolbar) } }
    CompositionLocalProvider(LocalTextToolbar provides toolbar, LocalClipboardManager provides clipboard) {
        SelectionContainer(modifier = modifier, content = content)
    }
}
