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
import com.example.newproject.ui.theme.LogoPurple
import com.example.newproject.ui.theme.OnVibrant
import com.example.newproject.ui.theme.ReadingGradient
import com.example.newproject.ui.theme.VigilithSlate

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
            // OP全体を1つの読み上げ要素に束ねる。名称は可視の Text("Vigilith AI")
            // が供給するため、ここで contentDescription を重ねると二重読み上げになる。
            .semantics(mergeDescendants = true) {}
    ) {
        val timeline = progress.value
        val motion = vigilithOpeningMotion(timeline)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(VigilithSlate.copy(alpha = motion.backdropAlpha))
        )

        OpeningBrand(
            motion = motion,
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
        )
    }
}

@Composable
private fun OpeningBrand(
    motion: VigilithOpeningMotion,
    modifier: Modifier = Modifier
) {
    val titleTranslation = with(LocalDensity.current) {
        (12.dp * motion.titleLiftFraction).toPx()
    }
    val bodyTranslation = with(LocalDensity.current) {
        (8.dp * motion.bodyLiftFraction).toPx()
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
                            alpha = motion.haloAlpha
                            scaleX = motion.haloScale
                            scaleY = scaleX
                        }
                ) {
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                // Adaptive Icon背景と同じ実効アルファ。
                                Aqua.copy(alpha = 0.16f),
                                Indigo.copy(alpha = 0.14f),
                                LogoPurple.copy(alpha = 0.10f),
                                Color.Transparent
                            ),
                            center = Offset(size.width / 2f, size.height / 2f),
                            radius = size.minDimension / 2f
                        ),
                        radius = size.minDimension / 2f
                    )
                }

                Image(
                    painter = painterResource(R.drawable.vigilith_idle_rich),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .size(iconSize)
                        .graphicsLayer {
                            alpha = motion.bodyAlpha
                            scaleX = motion.bodyScale
                            scaleY = motion.bodyScale
                            translationY = bodyTranslation
                        }
                )

            }

            Spacer(modifier = Modifier.height(18.dp))

            Text(
                text = "Vigilith AI",
                color = OnVibrant,
                fontSize = titleSize,
                fontWeight = FontWeight.Medium,
                letterSpacing = 1.2.sp,
                maxLines = 1,
                modifier = Modifier.graphicsLayer {
                    alpha = motion.titleAlpha
                    translationY = titleTranslation
                }
            )
        }
    }
}
