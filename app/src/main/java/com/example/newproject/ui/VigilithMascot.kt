package com.example.newproject.ui

import androidx.annotation.DrawableRes
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.RepeatMode
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.Image
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
 * Phase 1では3ポーズを静的ベクターとして確定し、状態間だけ短くクロスフェードする。
 * 翼・レンズ・カプセルの個別モーションはPhase 2で追加する。
 */
@Composable
internal fun VigilithMascot(
    mode: VigilithMode,
    actionStatus: VigilithActionStatus,
    modifier: Modifier = Modifier
) {
    val glowTransition = rememberInfiniteTransition(label = "Vigilith core glow")
    val glowFraction by glowTransition.animateFloat(
        initialValue = 0.25f,
        targetValue = 0.65f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = if (actionStatus == VigilithActionStatus.Working) 900 else 3200),
            repeatMode = RepeatMode.Reverse
        ),
        label = "Vigilith glow fraction"
    )

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
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

        // 既存ベクターの胸部コアと重なる低強度の補助光。
        Canvas(modifier = Modifier.fillMaxSize()) {
            val core = Offset(size.width * 0.5f, size.height * 0.67f)
            drawCircle(
                color = Aqua.copy(alpha = glowFraction * 0.22f),
                radius = size.minDimension * (0.09f + glowFraction * 0.025f),
                center = core
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
    VigilithMode.Idle -> R.drawable.vigilith_idle
    VigilithMode.Distilling -> R.drawable.vigilith_distilling
    VigilithMode.Messenger -> R.drawable.vigilith_messenger
}
