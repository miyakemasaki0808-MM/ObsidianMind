package com.example.newproject.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.newproject.GraphState
import com.example.newproject.VaultGraph
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

private val BgColor     = Color(0xFF1A1C2E)
private val CurrentColor = Color(0xFFFF6B8A)
private val LinkedColor  = Color(0xFF4D3DFF)
private val OtherColor   = Color(0xFF2A2D45)
private val EdgeColor    = Color(0xFF3D4070)
private val EdgeHighlight = Color(0xFF6B6FFF)
private val LabelColorInt = android.graphics.Color.parseColor("#B0B8FF")
private val LabelDimInt   = android.graphics.Color.parseColor("#444466")

@Composable
fun GraphViewScreen(
    currentNoteTitle: String,
    graphState: GraphState,
    onNodeTap: (String) -> Unit,
    onBack: () -> Unit
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        scale = (scale * zoomChange).coerceIn(0.3f, 4f)
        offset += panChange
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgColor)
            .statusBarsPadding()
    ) {
        Text(
            text = "🕸 Vault グラフ",
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFFB0B8FF),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
        )

        Box(modifier = Modifier.weight(1f)) {
            when (graphState) {
                is GraphState.Idle, is GraphState.Loading -> {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator(color = Color(0xFF4D3DFF))
                        Spacer(modifier = Modifier.height(12.dp))
                        Text("Vault を読み込み中…", fontSize = 14.sp, color = Color(0xFF777799))
                    }
                }
                is GraphState.Error -> {
                    Text(
                        text = "エラー: ${graphState.message}",
                        color = Color(0xFFEF5350),
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
                is GraphState.Success -> {
                    val nodes = remember(graphState, currentNoteTitle) {
                        buildLayoutNodes(graphState.graph, graphState.allTitles, currentNoteTitle)
                    }
                    Canvas(
                        modifier = Modifier
                            .fillMaxSize()
                            .transformable(transformState)
                            .graphicsLayer {
                                scaleX = scale; scaleY = scale
                                translationX = offset.x; translationY = offset.y
                            }
                            .pointerInput(nodes, scale, offset) {
                                detectTapGestures { rawTap ->
                                    // タップ座標をグラフ座標に逆変換
                                    val graphTap = Offset(
                                        (rawTap.x - offset.x) / scale,
                                        (rawTap.y - offset.y) / scale
                                    )
                                    val hit = nodes.firstOrNull { node ->
                                        (graphTap - node.position).length() <= NODE_RADIUS + TAP_SLOP
                                    }
                                    if (hit != null && !hit.isCurrent) {
                                        onNodeTap(hit.label)
                                    }
                                }
                            }
                    ) {
                        drawVaultGraph(nodes, currentNoteTitle)
                    }
                    Text(
                        text = "ピンチ: ズーム  ドラッグ: 移動  ノードタップ: 開く",
                        fontSize = 11.sp,
                        color = Color(0xFF444466),
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 8.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(
            onClick = onBack,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            shape = RoundedCornerShape(8.dp)
        ) {
            Text("← ノートに戻る", color = Color(0xFFB0B8FF))
        }
        Spacer(modifier = Modifier.height(16.dp))
    }
}

private data class LayoutNode(
    val label: String,
    val normalizedLabel: String,
    val position: Offset,
    val isCurrent: Boolean,
    val isLinked: Boolean   // 現在ノートと直接繋がっている
)

private fun buildLayoutNodes(
    graph: VaultGraph,
    allTitles: List<String>,
    currentTitle: String
): List<LayoutNode> {
    val currentNorm = currentTitle.trim().removeSuffix(".md").lowercase()
    val directLinks = graph[currentNorm] ?: emptySet()

    // 現在ノートにリンクしているノード（被リンクも含む）
    val backlinks = graph.entries
        .filter { (_, links) -> currentNorm in links }
        .map { it.key }
        .toSet()
    val neighbors = directLinks + backlinks

    // 表示するノード: 現在 + 直接リンク + 被リンク（上限60）
    val displayTitles = (listOf(currentTitle) +
        allTitles.filter { it.trim().removeSuffix(".md").lowercase() in neighbors }
    ).take(60)

    val count = displayTitles.size
    // Canvas サイズ不明なので 1000x1800 仮想空間で配置
    val cx = 500f
    val cy = 900f
    val radius = 380f

    return displayTitles.mapIndexed { i, title ->
        val norm = title.trim().removeSuffix(".md").lowercase()
        val isCurrent = norm == currentNorm
        val position = if (isCurrent) {
            Offset(cx, cy)
        } else {
            val idx = i - 1  // 0番目はcurrentなのでずらす
            val angle = (2 * Math.PI * idx / (count - 1)).toFloat()
            Offset(cx + radius * cos(angle), cy + radius * sin(angle))
        }
        LayoutNode(
            label = title.trim().removeSuffix(".md"),
            normalizedLabel = norm,
            position = position,
            isCurrent = isCurrent,
            isLinked = norm in neighbors
        )
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawVaultGraph(
    nodes: List<LayoutNode>,
    currentTitle: String
) {
    val currentNorm = currentTitle.trim().removeSuffix(".md").lowercase()
    val currentNode = nodes.firstOrNull { it.isCurrent } ?: return
    val nodeMap = nodes.associateBy { it.normalizedLabel }

    // エッジ
    nodes.filter { !it.isCurrent }.forEach { node ->
        val isHighlighted = node.isLinked
        drawLine(
            color = if (isHighlighted) EdgeHighlight.copy(alpha = 0.5f) else EdgeColor.copy(alpha = 0.3f),
            start = currentNode.position,
            end = node.position,
            strokeWidth = if (isHighlighted) 2f else 1f
        )
    }

    // ノード + ラベル
    nodes.forEach { node ->
        val nodeColor = when {
            node.isCurrent -> CurrentColor
            node.isLinked  -> LinkedColor
            else           -> OtherColor
        }
        val r = if (node.isCurrent) CENTER_RADIUS else NODE_RADIUS

        drawCircle(color = nodeColor.copy(alpha = 0.2f), radius = r + 8f, center = node.position)
        drawCircle(color = nodeColor, radius = r, center = node.position)

        val paint = android.graphics.Paint().apply {
            textSize = if (node.isCurrent) 30f else 22f
            setColor(if (node.isLinked || node.isCurrent) LabelColorInt else LabelDimInt)
            isAntiAlias = true
            textAlign = android.graphics.Paint.Align.CENTER
        }
        val labelText = node.label.take(14)
        drawContext.canvas.nativeCanvas.drawText(
            labelText,
            node.position.x,
            node.position.y + r + 28f,
            paint
        )
    }
}

private fun Offset.length(): Float = sqrt(x.pow(2) + y.pow(2))

private const val CENTER_RADIUS = 38f
private const val NODE_RADIUS   = 20f
private const val TAP_SLOP      = 14f
