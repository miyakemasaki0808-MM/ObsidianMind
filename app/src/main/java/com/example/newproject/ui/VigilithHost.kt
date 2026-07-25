package com.example.newproject.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.newproject.domain.markdown.NoteSection
import com.example.newproject.ui.theme.AccentGlass
import com.example.newproject.ui.theme.OnVibrant
import kotlin.math.max
import kotlin.math.roundToInt

/** Noteタブから共通Hostへ渡す、現在の読書位置と既存AI操作の状態。 */
internal data class VigilithNoteAction(
    val section: NoteSection,
    val sectionLabel: String,
    val status: VigilithActionStatus,
    val isAnswerGenerating: Boolean
)

/**
 * 通常5タブにまたがって一体だけ存在するVigilithの配置Host。
 *
 * 位置は画面内の相対値で保存し、Fold開閉・回転・状態ラベルの寸法変更後も
 * safe drawing領域とナビゲーションUIの内側へ収め直す。
 */
@Composable
internal fun BoxScope.VigilithHost(
    presentation: VigilithPresentation,
    useNavigationRail: Boolean,
    isSnackbarVisible: Boolean,
    noteAction: VigilithNoteAction?,
    onTap: (() -> Unit)?
) {
    val density = LocalDensity.current
    var horizontalFraction by rememberSaveable { mutableFloatStateOf(1f) }
    var verticalFraction by rememberSaveable { mutableFloatStateOf(1f) }
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }
    var contentSize by remember(noteAction != null, density.density) {
        mutableStateOf(
            with(density) {
                IntSize(
                    width = (if (noteAction != null) 260.dp else 76.dp).roundToPx(),
                    height = (if (noteAction != null) 132.dp else 93.dp).roundToPx()
                )
            }
        )
    }
    val currentOnTap by rememberUpdatedState(onTap)
    val actionDescription = noteAction?.let(::vigilithActionDescription)
    val layoutDirection = LocalLayoutDirection.current
    val safeDrawing = WindowInsets.safeDrawing
    val safeLeftPx = safeDrawing.getLeft(density, layoutDirection).toFloat()
    val safeTopPx = safeDrawing.getTop(density).toFloat()
    val safeRightPx = safeDrawing.getRight(density, layoutDirection).toFloat()
    val safeBottomPx = safeDrawing.getBottom(density).toFloat()
    val imeBottomPx = WindowInsets.ime.getBottom(density).toFloat()
    val edgeMarginPx = with(density) { VigilithEdgeMargin.toPx() }
    val railWidthPx = with(density) { VigilithNavigationRailWidth.toPx() }
    val navigationClearancePx = with(density) {
        if (useNavigationRail) VigilithRailBottomMargin.toPx()
        else VigilithNavigationBarClearance.toPx()
    }
    val bottomReservedPx = calculateVigilithBottomReserved(
        safeBottomPx = safeBottomPx,
        navigationClearancePx = navigationClearancePx,
        isSnackbarVisible = isSnackbarVisible,
        snackbarClearancePx = with(density) { VigilithSnackbarClearance.toPx() },
        imeBottomPx = imeBottomPx,
        imeMarginPx = with(density) { VigilithImeMargin.toPx() }
    )
    val railOnLeft = useNavigationRail && layoutDirection == LayoutDirection.Ltr
    val railOnRight = useNavigationRail && layoutDirection == LayoutDirection.Rtl
    val bounds = calculateVigilithPlacementBounds(
        viewportWidthPx = viewportSize.width.toFloat(),
        viewportHeightPx = viewportSize.height.toFloat(),
        contentWidthPx = contentSize.width.toFloat(),
        contentHeightPx = contentSize.height.toFloat(),
        startReservedPx = max(safeLeftPx, if (railOnLeft) railWidthPx else 0f),
        topReservedPx = safeTopPx,
        endReservedPx = max(safeRightPx, if (railOnRight) railWidthPx else 0f),
        bottomReservedPx = bottomReservedPx,
        edgeMarginPx = edgeMarginPx
    )
    val resolvedOffset = resolveVigilithPlacement(
        placement = VigilithPlacement(horizontalFraction, verticalFraction),
        bounds = bounds
    )

    // 全画面の透明な配置レイヤー。入力を持つのはVigilith本体だけなので、
    // 背後のコンテンツ操作は妨げない。
    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { viewportSize = it }
    ) {
        AnimatedVisibility(
            visible = presentation.isVisible,
            modifier = Modifier
                .offset {
                    IntOffset(
                        resolvedOffset.x.roundToInt(),
                        resolvedOffset.y.roundToInt()
                    )
                }
                .onSizeChanged {
                    // 非表示時の0pxで最後の実寸を消さず、再表示の初回から安全に配置する。
                    if (it.width > 0 && it.height > 0) contentSize = it
                },
            enter = fadeIn(tween(180)) + scaleIn(tween(220), initialScale = 0.92f),
            exit = fadeOut(tween(140)) + scaleOut(tween(140), targetScale = 0.96f)
        ) {
            Column(horizontalAlignment = Alignment.End) {
                if (noteAction != null) {
                    VigilithActionLabel(noteAction)
                    Spacer(modifier = Modifier.size(10.dp))
                }

                val interactionModifier = Modifier
                    .size(width = 76.dp, height = 93.dp)
                    .pointerInput(bounds) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            val moved = moveVigilithPlacement(
                                placement = VigilithPlacement(
                                    horizontalFraction,
                                    verticalFraction
                                ),
                                deltaX = dragAmount.x,
                                deltaY = dragAmount.y,
                                bounds = bounds
                            )
                            horizontalFraction = moved.horizontalFraction
                            verticalFraction = moved.verticalFraction
                        }
                    }
                    .then(
                        if (currentOnTap != null && actionDescription != null) {
                            Modifier
                                .pointerInput(Unit) {
                                    detectTapGestures(onTap = { currentOnTap?.invoke() })
                                }
                                .clearAndSetSemantics {
                                    contentDescription = actionDescription
                                    role = Role.Button
                                    onClick {
                                        currentOnTap?.invoke()
                                        true
                                    }
                                }
                        } else {
                            // 他タブでは装飾。TalkBackのフォーカス対象を増やさない。
                            Modifier.clearAndSetSemantics {}
                        }
                    )

                VigilithMascot(
                    presentation = presentation,
                    actionStatus = noteAction?.status ?: VigilithActionStatus.Idle,
                    modifier = interactionModifier
                )
            }
        }
    }
}

internal fun vigilithActionDescription(action: VigilithNoteAction): String {
    val stateDescription = when (action.status) {
        VigilithActionStatus.Idle -> "AIメニューを開く"
        VigilithActionStatus.Working -> if (action.isAnswerGenerating) {
            "AI回答を生成中。タップで開く"
        } else {
            "AI要約を生成中。タップで開く"
        }
        VigilithActionStatus.Ready -> "AI結果を生成済み。タップで開く"
        VigilithActionStatus.Error -> "AI処理でエラー。タップで確認"
    }
    return "Vigilith。$stateDescription。対象は${action.sectionLabel}"
}

private val VigilithEdgeMargin = 16.dp
private val VigilithNavigationRailWidth = 80.dp
private val VigilithNavigationBarClearance = 92.dp
private val VigilithRailBottomMargin = 20.dp
private val VigilithSnackbarClearance = 72.dp
private val VigilithImeMargin = 16.dp

@Composable
private fun VigilithActionLabel(action: VigilithNoteAction) {
    Box(
        modifier = Modifier
            .background(AccentGlass, RoundedCornerShape(999.dp))
            .widthIn(max = 260.dp)
            .padding(horizontal = 11.dp, vertical = 5.dp)
            // 読み上げは本体の1つのボタンへ集約し、ラベルとの二重フォーカスを避ける。
            .clearAndSetSemantics {}
    ) {
        Text(
            text = when (action.status) {
                VigilithActionStatus.Idle -> "📌 ${action.sectionLabel}"
                VigilithActionStatus.Working -> if (action.isAnswerGenerating) {
                    "⏳ AI回答中 · ${action.sectionLabel}"
                } else {
                    "⏳ AI要約中 · ${action.sectionLabel}"
                }
                VigilithActionStatus.Ready -> "✓ 要約完了 · ${action.sectionLabel}"
                VigilithActionStatus.Error -> "! 要約を確認 · ${action.sectionLabel}"
            },
            color = OnVibrant,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
