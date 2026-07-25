package com.example.newproject.ui

import androidx.annotation.DrawableRes
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.newproject.R
import com.example.newproject.ui.theme.Aqua
import com.example.newproject.ui.theme.ErrorRed
import com.example.newproject.ui.theme.Indigo
import com.example.newproject.ui.theme.OnVibrant
import androidx.compose.foundation.shape.CircleShape

/** Vigilith本体とは別に、既存のAI操作結果を知らせる小さな状態表示。 */
internal enum class VigilithActionStatus {
    Idle,
    Working,
    Ready,
    Error
}

/**
 * アプリ内Vigilithの描画本体。
 *
 * 4状態の透過WebP完成ポーズを土台に、レンズ・コア・断片・痕跡カプセルだけを小さく動かす。
 * 回転やバウンドでキャラクター全体を騒がせず、寡黙な不寝番のトーンを保つ。
 */
@Composable
internal fun VigilithMascot(
    presentation: VigilithPresentation,
    actionStatus: VigilithActionStatus,
    modifier: Modifier = Modifier
) {
    val mode = presentation.mode
    val loopTransition = rememberInfiniteTransition(label = "Vigilith ambient motion")
    val loopFraction by loopTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = when (mode) {
                    VigilithMode.Idle -> 4_800
                    VigilithMode.Summarizing -> 2_800
                    VigilithMode.Distilling -> 1_600
                    VigilithMode.Messenger -> 3_600
                },
                easing = LinearEasing
            ),
            repeatMode = RepeatMode.Restart
        ),
        label = "Vigilith loop fraction"
    )
    val entrance = remember { Animatable(1f) }
    LaunchedEffect(mode, presentation.distillPhase) {
        entrance.snapTo(0f)
        entrance.animateTo(1f, animationSpec = tween(520))
    }
    val motion = vigilithMascotMotion(presentation, loopFraction, entrance.value)
    val liftPx = with(LocalDensity.current) { (8.dp * motion.bodyLiftFraction).toPx() }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationY = liftPx
                    val summaryScale = 1f + 0.008f * motion.summaryGuideFraction
                    scaleX = when (mode) {
                        VigilithMode.Distilling ->
                            1.018f - 0.018f * motion.wingCloseFraction
                        else -> summaryScale
                    }
                    scaleY = summaryScale
                    transformOrigin = TransformOrigin(0.5f, 0.9f)
                }
        ) {
            Crossfade(
                targetState = mode,
                animationSpec = tween(durationMillis = 220),
                label = "Vigilith pose"
            ) { targetMode ->
                Image(
                    painter = painterResource(targetMode.drawableResource()),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize()
                )
            }

            VigilithLightLayer(
                mode = mode,
                distillPhase = presentation.distillPhase,
                motion = motion
            )
        }

        when (actionStatus) {
            VigilithActionStatus.Working -> CircularProgressIndicator(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(19.dp),
                color = Aqua,
                trackColor = Indigo.copy(alpha = 0.35f),
                strokeWidth = 2.dp
            )
            VigilithActionStatus.Ready -> VigilithStatusBadge("✓", Indigo)
            VigilithActionStatus.Error -> VigilithStatusBadge("!", ErrorRed)
            VigilithActionStatus.Idle -> Unit
        }
    }
}

@Composable
private fun VigilithLightLayer(
    mode: VigilithMode,
    distillPhase: VigilithDistillPhase?,
    motion: VigilithMascotMotion
) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        // 各WebP内の虹彩中心を実測し、呼吸光が瞳孔へ被らない位置に合わせる。
        val (leftLens, rightLens) = when (mode) {
            VigilithMode.Idle -> Offset(0.351f, 0.276f) to Offset(0.634f, 0.277f)
            VigilithMode.Summarizing -> Offset(0.461f, 0.291f) to Offset(0.684f, 0.295f)
            VigilithMode.Distilling -> Offset(0.370f, 0.266f) to Offset(0.635f, 0.266f)
            VigilithMode.Messenger -> Offset(0.373f, 0.274f) to Offset(0.653f, 0.274f)
        }
        val lensRadius = size.minDimension *
            (0.078f + 0.006f * motion.summaryGuideFraction)
        listOf(
            leftLens,
            rightLens
        ).forEach { lens ->
            drawCircle(
                color = Aqua.copy(alpha = motion.lensGlowAlpha * 0.34f),
                radius = lensRadius,
                center = Offset(size.width * lens.x, size.height * lens.y)
            )
        }

        val core = when (mode) {
            VigilithMode.Idle -> Offset(size.width * 0.50f, size.height * 0.55f)
            VigilithMode.Summarizing -> Offset(size.width * 0.50f, size.height * 0.53f)
            VigilithMode.Distilling -> Offset(size.width * 0.50f, size.height * 0.56f)
            VigilithMode.Messenger -> Offset(size.width * 0.50f, size.height * 0.63f)
        }
        drawCircle(
            color = Aqua.copy(alpha = motion.coreGlowAlpha * 0.30f),
            radius = size.minDimension * (0.08f + motion.coreGlowAlpha * 0.025f),
            center = core
        )

        if (mode == VigilithMode.Distilling) {
            val gather = motion.candidateGatherFraction
            val lineWidth = size.width * 0.23f
            val centerX = size.width * 0.50f
            val sideOffset = size.width * (0.22f * (1f - gather))
            listOf(-1f, 0f, 1f).forEachIndexed { index, direction ->
                val y = size.height * (0.535f + index * 0.035f)
                val x = centerX + direction * sideOffset
                drawLine(
                    color = Aqua.copy(
                        alpha = if (index == 1) 0.82f else 0.26f + 0.24f * (1f - gather)
                    ),
                    start = Offset(x - lineWidth / 2f, y),
                    end = Offset(x + lineWidth / 2f, y),
                    strokeWidth = size.minDimension * if (index == 1) 0.012f else 0.008f,
                    cap = StrokeCap.Round
                )
            }

            if (distillPhase == VigilithDistillPhase.Underlining) {
                val underlineY = size.height * 0.595f
                drawLine(
                    color = Aqua.copy(alpha = 0.90f),
                    start = Offset(size.width * 0.385f, underlineY),
                    end = Offset(
                        size.width * (0.385f + 0.23f * motion.underlineFraction),
                        underlineY
                    ),
                    strokeWidth = size.minDimension * 0.014f,
                    cap = StrokeCap.Round
                )
            }
        }

        if (mode == VigilithMode.Messenger && motion.messengerGlowAlpha > 0f) {
            drawCircle(
                color = Aqua.copy(alpha = motion.messengerGlowAlpha * 0.55f),
                radius = size.minDimension * (0.18f + motion.messengerGlowAlpha * 0.07f),
                center = core,
                style = androidx.compose.ui.graphics.drawscope.Stroke(
                    width = size.minDimension * 0.018f
                )
            )
        }
    }
}

@Composable
private fun BoxScope.VigilithStatusBadge(text: String, color: Color) {
    Box(
        modifier = Modifier
            .align(Alignment.BottomEnd)
            .size(21.dp)
            .clip(CircleShape)
            .background(color),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = OnVibrant,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@DrawableRes
private fun VigilithMode.drawableResource(): Int = when (this) {
    VigilithMode.Idle -> R.drawable.vigilith_idle_rich
    VigilithMode.Summarizing -> R.drawable.vigilith_summary_rich
    VigilithMode.Distilling -> R.drawable.vigilith_distilling_rich
    VigilithMode.Messenger -> R.drawable.vigilith_messenger_rich
}
