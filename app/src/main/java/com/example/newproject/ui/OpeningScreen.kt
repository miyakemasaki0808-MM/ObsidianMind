package com.example.newproject.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.newproject.R
import com.example.newproject.ui.theme.Aqua
import com.example.newproject.ui.theme.Indigo
import com.example.newproject.ui.theme.LogoNavy
import com.example.newproject.ui.theme.LogoPurple
import com.example.newproject.ui.theme.OnVibrant
import com.example.newproject.ui.theme.ReadingGradient

private const val OpeningDurationMillis = 2_000

/**
 * 起動時に一度だけ表示するブランドOP。
 *
 * 進行を単一の [Animatable] から導出することで、固定delayを使わず、端末の
 * Animator duration scale（0倍を含む）へCompose標準の挙動で追従する。
 */
@Composable
fun OpeningScreen(
    onFinished: () -> Unit,
    modifier: Modifier = Modifier
) {
    val currentOnFinished by rememberUpdatedState(onFinished)
    val progress = remember { Animatable(0f) }
    var completionDispatched by remember { mutableStateOf(false) }

    fun finishOnce() {
        if (completionDispatched) return
        completionDispatched = true
        currentOnFinished()
    }

    LaunchedEffect(Unit) {
        progress.animateTo(
            targetValue = 1f,
            animationSpec = tween(
                durationMillis = OpeningDurationMillis,
                easing = LinearEasing
            )
        )
        finishOnce()
    }

    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(ReadingGradient)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClickLabel = "オープニングをスキップ",
                onClick = ::finishOnce
            )
            // OP全体を1つの読み上げ要素に束ねる。名称は可視の Text("Obsidian Mind")
            // が供給するため、ここで contentDescription を重ねると二重読み上げになる。
            .semantics(mergeDescendants = true) {}
    ) {
        val timeline = progress.value
        val navyAlpha = 1f - timeline.fractionBetween(0.75f, 1f)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(LogoNavy.copy(alpha = navyAlpha))
        )

        OpeningBrand(
            timeline = timeline,
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
        )
    }
}

@Composable
private fun OpeningBrand(
    timeline: Float,
    modifier: Modifier = Modifier
) {
    val iconProgress = timeline.fractionBetween(0f, 0.325f)
    val titleProgress = timeline.fractionBetween(0.225f, 0.525f)
    val haloAlpha = when {
        timeline < 0.09f -> 0f
        timeline < 0.325f -> timeline.fractionBetween(0.09f, 0.325f) * 0.52f
        timeline < 0.75f -> 0.52f
        else -> (1f - timeline.fractionBetween(0.75f, 1f)) * 0.52f
    }
    val iconScale = 0.88f + (0.12f * iconProgress)
    val titleTranslation = with(LocalDensity.current) {
        (12.dp * (1f - titleProgress)).toPx()
    }

    BoxWithConstraints(modifier = modifier) {
        val iconSize = minOf(240.dp, maxWidth * 0.48f, maxHeight * 0.52f)
        val titleSize = if (maxWidth < 360.dp) 24.sp else 28.sp

        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier.size(iconSize * 1.24f),
                contentAlignment = Alignment.Center
            ) {
                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            alpha = haloAlpha
                            scaleX = 0.90f + (0.10f * iconProgress)
                            scaleY = scaleX
                        }
                ) {
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                Aqua.copy(alpha = 0.50f),
                                Indigo.copy(alpha = 0.32f),
                                LogoPurple.copy(alpha = 0.18f),
                                Color.Transparent
                            ),
                            center = Offset(size.width / 2f, size.height / 2f),
                            radius = size.minDimension / 2f
                        ),
                        radius = size.minDimension / 2f
                    )
                }

                Image(
                    painter = painterResource(R.drawable.mm_ai_solutions_logo),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .size(iconSize)
                        .graphicsLayer {
                            alpha = iconProgress
                            scaleX = iconScale
                            scaleY = iconScale
                        }
                )
            }

            Spacer(modifier = Modifier.height(18.dp))

            Text(
                text = "Obsidian Mind",
                color = OnVibrant,
                fontSize = titleSize,
                fontWeight = FontWeight.Medium,
                letterSpacing = 1.2.sp,
                maxLines = 1,
                modifier = Modifier.graphicsLayer {
                    alpha = titleProgress
                    translationY = titleTranslation
                }
            )
        }
    }
}

private fun Float.fractionBetween(start: Float, end: Float): Float =
    ((this - start) / (end - start)).coerceIn(0f, 1f)
