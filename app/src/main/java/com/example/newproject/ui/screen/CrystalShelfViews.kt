package com.example.newproject.ui.screen

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.newproject.model.Crystal
import com.example.newproject.model.NoteField
import com.example.newproject.ui.CRYSTAL_NO_FIELD_LABEL
import com.example.newproject.ui.crystalDayLabel
import com.example.newproject.ui.crystalGemDescription
import com.example.newproject.ui.theme.OnSurfaceMuted
import com.example.newproject.ui.theme.Panel
import com.example.newproject.ui.theme.crystalGemColor

// 結晶の一覧の宝石の棚（→ docs/dev/features/reflect_crystal.md 判断11）。並べ方は `crystalShelf` が決め、ここは描くだけ。

/** 棚の角の丸み。✨タブの結晶のパネルと同じ。 */
internal val CrystalShelfCorner = 8.dp

/** 棚の中の左右の余白。1段の宝石の数は、この内側の幅で決める（→ `crystalShelfColumns`）。 */
internal val CrystalShelfInnerPadding = 8.dp

/**
 * 宝石1つ。[fields] の1つ目を左、2つ目を右に塗り、1つなら全体、空なら灰色にする。**混ぜない** —
 * 6分野は色相を等間隔に置いたので、2色の中間が別の分野の色相になる（→ reflect_crystal.md 判断11）。
 * 2色のときは、境目にパネルの色の細い隙間を入れる。近い色相どうしでも割れていると分かるように。
 * 面の明暗は宝石の色から作るので、色の持ち主は宝石の色1つのまま。読み上げは持たない（押せる枠が持つ）。
 */
@Composable
internal fun CrystalGem(fields: List<NoteField>, modifier: Modifier = Modifier, width: Dp = 44.dp) {
    val left = crystalGemColor(fields.getOrNull(0))
    val right = crystalGemColor(fields.getOrNull(1) ?: fields.getOrNull(0))
    val seam = Panel
    val split = fields.size > 1
    Canvas(modifier = modifier.size(width = width, height = width * GEM_HEIGHT / GEM_WIDTH)) {
        val sx = size.width / GEM_WIDTH
        val sy = size.height / GEM_HEIGHT
        fun polygon(color: Color, vararg points: Pair<Float, Float>) {
            val path = Path().apply {
                points.forEachIndexed { i, (x, y) -> if (i == 0) moveTo(x * sx, y * sy) else lineTo(x * sx, y * sy) }
                close()
            }
            drawPath(path, color)
        }
        // 左半分
        polygon(left, 10f to 4f, 22f to 4f, 22f to 38f, 2f to 14f)
        polygon(left.facetLight(), 10f to 4f, 22f to 4f, 22f to 14f, 16f to 14f)
        polygon(left.facetDark(), 2f to 14f, 16f to 14f, 22f to 38f)
        // 右半分
        polygon(right, 22f to 4f, 34f to 4f, 42f to 14f, 22f to 38f)
        polygon(right.facetLight(), 22f to 4f, 34f to 4f, 28f to 14f, 22f to 14f)
        polygon(right.facetDarker(), 28f to 14f, 42f to 14f, 22f to 38f)
        if (split) drawSeam(seam, sx, sy)
    }
}

private fun DrawScope.drawSeam(color: Color, sx: Float, sy: Float) {
    drawLine(color, Offset(22f * sx, 4f * sy), Offset(22f * sx, 38f * sy), strokeWidth = 1.5.dp.toPx())
}

private fun Color.facetLight(): Color = lerp(this, Color.White, 0.35f)
private fun Color.facetDark(): Color = lerp(this, Color.Black, 0.2f)
private fun Color.facetDarker(): Color = lerp(this, Color.Black, 0.35f)

private const val GEM_WIDTH = 44f
private const val GEM_HEIGHT = 40f

/** 一覧の上の凡例。宝石の色は分野を表すので、**色以外の手がかり**（1.4.1）として分野名を並べる。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CrystalShelfLegend(modifier: Modifier = Modifier) {
    Surface(modifier = modifier.fillMaxWidth(), color = Panel, shape = RoundedCornerShape(CrystalShelfCorner)) {
        FlowRow(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            (NoteField.entries.map { listOf(it) to it.label } + (emptyList<NoteField>() to CRYSTAL_NO_FIELD_LABEL)).forEach { (fields, label) ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    CrystalGem(fields = fields, width = 14.dp)
                    Text(text = label, fontSize = 11.sp, color = OnSurfaceMuted)
                }
            }
        }
    }
}

/**
 * 宝石の1段。[columns] 個の枠に左から置き、足りない分は空ける（月の終わりの段は欠けたまま）。
 * 宝石を押すと [onToggle] にその結晶を渡す。開いている宝石の下には、中身を指す ▲ を出す。
 */
@Composable
internal fun CrystalShelfRow(
    crystals: List<Crystal>,
    columns: Int,
    opened: String?,
    fieldsOf: (Crystal) -> List<NoteField>,
    onToggle: (Crystal) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(modifier = modifier.fillMaxWidth().padding(horizontal = CrystalShelfInnerPadding)) {
        repeat(columns) { i ->
            val crystal = crystals.getOrNull(i)
            if (crystal == null) {
                Spacer(modifier = Modifier.weight(1f))
            } else {
                val isOpen = crystal.fileName == opened
                val fields = fieldsOf(crystal)
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .weight(1f)
                        .defaultMinSize(minHeight = 72.dp)
                        .semantics(mergeDescendants = true) {
                            contentDescription = crystalGemDescription(crystal.createdAt, fields)
                            stateDescription = if (isOpen) "開いています" else "閉じています"
                        }
                        .clickable(role = Role.Button) { onToggle(crystal) }
                        .padding(vertical = 6.dp)
                ) {
                    CrystalGem(fields = fields)
                    Text(
                        text = crystalDayLabel(crystal.createdAt),
                        fontSize = 12.sp,
                        color = OnSurfaceMuted,
                        modifier = Modifier.padding(top = 4.dp).clearAndSetSemantics {}
                    )
                    if (isOpen) {
                        Text(text = "▲", fontSize = 11.sp, color = OnSurfaceMuted, modifier = Modifier.clearAndSetSemantics {})
                    }
                }
            }
        }
    }
}

/** 棚の1区切りの地。月の見出しが上端、最後の段か中身が下端で、そこだけ角を丸める。 */
internal fun Modifier.crystalShelfSegment(color: Color, top: Boolean, bottom: Boolean): Modifier = background(
    color,
    RoundedCornerShape(
        topStart = if (top) CrystalShelfCorner else 0.dp,
        topEnd = if (top) CrystalShelfCorner else 0.dp,
        bottomStart = if (bottom) CrystalShelfCorner else 0.dp,
        bottomEnd = if (bottom) CrystalShelfCorner else 0.dp
    )
)
