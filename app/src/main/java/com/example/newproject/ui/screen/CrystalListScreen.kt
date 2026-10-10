package com.example.newproject.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.newproject.domain.crystalFields
import com.example.newproject.model.Crystal
import com.example.newproject.model.DocumentRef
import com.example.newproject.model.NoteField
import com.example.newproject.model.NoteFieldClassification
import com.example.newproject.model.state.CrystalLogState
import com.example.newproject.ui.CRYSTAL_EMPTY_MESSAGE
import com.example.newproject.ui.CrystalShelfItem
import com.example.newproject.ui.CrystalShelfReveal
import com.example.newproject.ui.crystalShelfDetailKey
import com.example.newproject.ui.crystalShelfRevealFor
import com.example.newproject.ui.crystalShelfRowIndex
import com.example.newproject.ui.component.GradientHeader
import com.example.newproject.ui.component.screenContentPadding
import com.example.newproject.ui.component.IconPill
import com.example.newproject.ui.crystalDetailCaption
import com.example.newproject.ui.crystalMonthLabel
import com.example.newproject.ui.crystalShelf
import com.example.newproject.ui.crystalShelfColumns
import com.example.newproject.ui.crystalShelfToggle
import com.example.newproject.ui.theme.AccentText
import com.example.newproject.ui.theme.AppGradient
import com.example.newproject.ui.theme.OnSurface
import com.example.newproject.ui.theme.OnSurfaceMuted
import com.example.newproject.ui.theme.Panel
import com.example.newproject.ui.theme.PanelBlue
import java.time.ZoneId

/**
 * 結晶の一覧（→ `docs/dev/features/reflect_crystal.md` §3・判断11）。月ごとの棚に宝石と日だけを新しい順に並べ、
 * 押した宝石の段の直下に、分野・日付・1文・根拠を開く。開くのは1つだけで、動きは付けない。
 *
 * 根拠のノート名は**今の走査結果に同じ相対パスがあるときだけ**押せる（[knownNotes]）。
 * 開き方は関連・さがすと同じで、Rediscover 経路ではないので再会カードは出ない。
 * 宝石の色は根拠のノートの分野で、走査結果の参照から分野の索引（[noteFields]）を引く。
 */
@Composable
fun CrystalListScreen(
    state: CrystalLogState,
    knownNotes: Map<String, DocumentRef>,
    noteFields: Map<DocumentRef, NoteFieldClassification>,
    onLoad: () -> Unit,
    onOpenSource: (String) -> Unit,
    onBack: () -> Unit
) {
    LaunchedEffect(Unit) { onLoad() }
    val fieldOf: (String) -> NoteField? = { path -> knownNotes[path]?.let { noteFields[it]?.field } }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AppGradient)
            .safeDrawingPadding()
            .screenContentPadding()
    ) {
        GradientHeader(
            title = "結晶",
            titleSize = 24.sp,
            leading = {
                IconPill(symbol = "‹", contentDescription = "戻る", symbolSize = 22.sp, onClick = onBack)
            }
        )
        Spacer(modifier = Modifier.height(14.dp))

        when (state) {
            CrystalLogState.NotLoaded,
            CrystalLogState.Loading -> Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(modifier = Modifier.padding(24.dp), color = AccentText)
            }

            is CrystalLogState.Loaded -> if (state.crystals.isEmpty()) {
                Surface(modifier = Modifier.fillMaxWidth(), color = Panel, shape = RoundedCornerShape(8.dp)) {
                    Text(
                        text = CRYSTAL_EMPTY_MESSAGE,
                        fontSize = 14.sp,
                        lineHeight = 22.sp,
                        color = OnSurface,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            } else {
                CrystalShelf(
                    crystals = state.crystals,
                    fieldOf = fieldOf,
                    canOpen = { path -> path in knownNotes },
                    onOpenSource = onOpenSource
                )
            }
        }
    }
}

/**
 * 宝石の棚。**開いた中身を画面の中へ送るのは棚の側** — 中身の項目は画面の外だと一覧に組まれず、
 * 自分の副作用では送れない。送るのは宝石を押したときだけで、項目が組み直されただけでは送り直さない。
 */
@Composable
private fun CrystalShelf(
    crystals: List<Crystal>,
    fieldOf: (String) -> NoteField?,
    canOpen: (String) -> Boolean,
    onOpenSource: (String) -> Unit
) {
    var opened by rememberSaveable { mutableStateOf<String?>(null) }
    // 押して開いた結晶。押したときにだけ変わり、送る合図になる。画面を作り直したら持ち越さない。
    var revealing by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    val fieldsOf: (Crystal) -> List<NoteField> = { crystalFields(it, fieldOf) }
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val columns = crystalShelfColumns((maxWidth - CrystalShelfInnerPadding * 2).value)
        val items = crystalShelf(crystals, columns, opened, ZoneId.systemDefault())
        LaunchedEffect(revealing) {
            val fileName = revealing ?: return@LaunchedEffect
            // 中身を足した並びが測られるのを待つ。
            withFrameNanos { }
            val info = listState.layoutInfo
            val detail = info.visibleItemsInfo.firstOrNull { it.key == crystalShelfDetailKey(fileName) }
            when (val reveal = crystalShelfRevealFor(detail?.offset, detail?.let { it.offset + it.size }, info.viewportStartOffset, info.viewportEndOffset)) {
                CrystalShelfReveal.None -> Unit
                is CrystalShelfReveal.By -> listState.animateScrollBy(reveal.pixels.toFloat())
                CrystalShelfReveal.ToRow -> crystalShelfRowIndex(items, fileName)?.let { listState.animateScrollToItem(it) }
            }
        }
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            itemsIndexed(items, key = { _, item -> item.key }) { index, item ->
                when (item) {
                    CrystalShelfItem.Legend -> CrystalShelfLegend(modifier = Modifier.padding(bottom = 14.dp))

                    is CrystalShelfItem.Month -> Text(
                        text = crystalMonthLabel(item.month, item.count),
                        fontSize = 13.sp,
                        color = OnSurfaceMuted,
                        modifier = Modifier
                            .fillMaxWidth()
                            // 棚と棚の間は地のまま空ける。最初の棚は凡例の下の余白に任せる。
                            .padding(top = if (index == 1) 0.dp else 14.dp)
                            .crystalShelfSegment(Panel, top = true, bottom = false)
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                    )

                    is CrystalShelfItem.Row -> CrystalShelfRow(
                        crystals = item.crystals,
                        columns = columns,
                        opened = opened,
                        fieldsOf = fieldsOf,
                        onToggle = { crystal ->
                            opened = crystalShelfToggle(opened, crystal.fileName)
                            revealing = opened
                        },
                        modifier = Modifier
                            .crystalShelfSegment(Panel, top = false, bottom = item.bottom)
                            .padding(bottom = if (item.bottom) 8.dp else 0.dp)
                    )

                    is CrystalShelfItem.Detail -> CrystalShelfDetail(
                        crystal = item.crystal,
                        fields = fieldsOf(item.crystal),
                        fieldOf = fieldOf,
                        canOpen = canOpen,
                        onOpenSource = onOpenSource,
                        modifier = Modifier.crystalShelfSegment(Panel, top = false, bottom = item.bottom)
                    )
                }
            }
        }
    }
}

/** 開いた結晶の中身。✨タブと同じ結晶のカードに、分野と日付を添える。 */
@Composable
private fun CrystalShelfDetail(
    crystal: Crystal,
    fields: List<NoteField>,
    fieldOf: (String) -> NoteField?,
    canOpen: (String) -> Boolean,
    onOpenSource: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 8.dp)) {
        Surface(modifier = Modifier.fillMaxWidth(), color = PanelBlue, shape = RoundedCornerShape(CrystalShelfCorner)) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                CrystalCard(
                    crystal = crystal,
                    caption = crystalDetailCaption(crystal.createdAt, fields),
                    onOpenSource = onOpenSource,
                    canOpen = canOpen,
                    sourceField = fieldOf
                )
            }
        }
    }
}
