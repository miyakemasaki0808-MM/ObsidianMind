package com.example.newproject.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.newproject.model.state.CrystalLogState
import com.example.newproject.ui.CRYSTAL_EMPTY_MESSAGE
import com.example.newproject.ui.component.GradientHeader
import com.example.newproject.ui.component.screenContentPadding
import com.example.newproject.ui.component.IconPill
import com.example.newproject.ui.crystalDateLabel
import com.example.newproject.ui.theme.AccentText
import com.example.newproject.ui.theme.AppGradient
import com.example.newproject.ui.theme.OnSurface
import com.example.newproject.ui.theme.Panel

/**
 * 結晶の一覧（→ `docs/dev/features/reflect_crystal.md` §3）。新しい順に、日付・1文・根拠。
 *
 * 根拠のノート名は**今の走査結果に同じ相対パスがあるときだけ**押せる（[knownNotePaths]）。
 * 開き方は関連・さがすと同じで、Rediscover 経路ではないので再会カードは出ない。
 */
@Composable
fun CrystalListScreen(
    state: CrystalLogState,
    knownNotePaths: Set<String>,
    onLoad: () -> Unit,
    onOpenSource: (String) -> Unit,
    onBack: () -> Unit
) {
    LaunchedEffect(Unit) { onLoad() }

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
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(state.crystals, key = { it.fileName }) { crystal ->
                        Surface(modifier = Modifier.fillMaxWidth(), color = Panel, shape = RoundedCornerShape(8.dp)) {
                            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                                CrystalCard(
                                    crystal = crystal,
                                    dateLabel = crystalDateLabel(crystal.createdAt),
                                    onOpenSource = onOpenSource,
                                    canOpen = { path -> path in knownNotePaths }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
