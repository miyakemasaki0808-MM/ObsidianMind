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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.newproject.ui.markdown.NoteSection
import com.example.newproject.ui.theme.Indigo
import com.example.newproject.ui.theme.OnVibrant
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
 * Phase 2では表示位置を共通化する。Snackbar回避とドラッグ範囲のclampは、
 * 画面サイズ別の実測を伴うためPhase 3で追加する。
 */
@Composable
internal fun BoxScope.VigilithHost(
    presentation: VigilithPresentation,
    useNavigationRail: Boolean,
    noteAction: VigilithNoteAction?,
    onTap: (() -> Unit)?
) {
    var dragX by remember { mutableFloatStateOf(0f) }
    var dragY by remember { mutableFloatStateOf(0f) }
    val currentOnTap by rememberUpdatedState(onTap)
    val actionDescription = noteAction?.let {
        when (it.status) {
            VigilithActionStatus.Idle -> "Vigilith。AIメニューを開く"
            VigilithActionStatus.Working -> "Vigilith。AI生成中"
            VigilithActionStatus.Ready -> "Vigilith。AI生成完了。タップで開く"
            VigilithActionStatus.Error -> "Vigilith。AIエラー。タップで確認"
        }
    }

    AnimatedVisibility(
        visible = presentation.isVisible,
        modifier = Modifier
            .align(Alignment.BottomEnd)
            .safeDrawingPadding()
            .padding(
                end = 20.dp,
                bottom = if (useNavigationRail) 20.dp else 92.dp
            )
            .offset { IntOffset(dragX.roundToInt(), dragY.roundToInt()) },
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
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        dragX += dragAmount.x
                        dragY += dragAmount.y
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

@Composable
private fun VigilithActionLabel(action: VigilithNoteAction) {
    Box(
        modifier = Modifier
            .background(Indigo.copy(alpha = 0.55f), RoundedCornerShape(999.dp))
            .widthIn(max = 260.dp)
            .padding(horizontal = 11.dp, vertical = 5.dp)
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
