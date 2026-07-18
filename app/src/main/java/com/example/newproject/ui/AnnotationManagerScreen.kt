package com.example.newproject.ui

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.newproject.AnnotationListState
import com.example.newproject.NoteFile
import com.example.newproject.ui.theme.AppGradient
import com.example.newproject.ui.theme.OnSurface
import com.example.newproject.ui.theme.OnVibrant
import com.example.newproject.ui.theme.Panel

private val DangerRed = Color(0xFFD32F2F)

@Composable
fun AnnotationManagerScreen(
    state: AnnotationListState,
    onLoad: () -> Unit,
    onDelete: (Uri) -> Unit,
    onDeleteAll: () -> Unit,
    onBack: () -> Unit
) {
    LaunchedEffect(Unit) { onLoad() }

    var pendingDelete by remember { mutableStateOf<NoteFile?>(null) }
    var showDeleteAll by remember { mutableStateOf(false) }

    val files = (state as? AnnotationListState.Success)?.files.orEmpty()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AppGradient)
            .safeDrawingPadding()
            .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            IconPillButton(symbol = "‹", contentDescription = "戻る", onClick = onBack)
            Text(
                text = "補記メモの削除",
                color = OnVibrant,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            if (files.isNotEmpty()) {
                Surface(
                    modifier = Modifier.clickable { showDeleteAll = true },
                    color = DangerRed,
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Text(
                        text = "すべて削除",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        when (state) {
            is AnnotationListState.Idle,
            is AnnotationListState.Loading -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = OnVibrant)
                }
            }
            is AnnotationListState.Error -> {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = Panel,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = "補記メモの読み込みに失敗しました: ${state.message}",
                        color = DangerRed,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
            is AnnotationListState.Success -> {
                if (files.isEmpty()) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = Panel.copy(alpha = 0.22f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = "補記メモはありません。",
                            color = OnVibrant,
                            fontSize = 14.sp,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        files.forEach { file ->
                            AnnotationRow(file = file, onDeleteClick = { pendingDelete = file })
                        }
                    }
                }
            }
        }
    }

    // 1件削除の確認
    pendingDelete?.let { target ->
        val (title, _) = parseAnnotationName(target.name)
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("補記メモを削除") },
            text = { Text("「$title」を削除しますか？この操作は取り消せません。") },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(target.uri)
                    pendingDelete = null
                }) { Text("削除", color = DangerRed) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("キャンセル") }
            }
        )
    }

    // 全件削除の確認
    if (showDeleteAll) {
        AlertDialog(
            onDismissRequest = { showDeleteAll = false },
            title = { Text("すべての補記メモを削除") },
            text = { Text("保存済みの補記メモ ${files.size} 件をすべて削除しますか？この操作は取り消せません。") },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteAll()
                    showDeleteAll = false
                }) { Text("すべて削除", color = DangerRed) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteAll = false }) { Text("キャンセル") }
            }
        )
    }
}

@Composable
private fun AnnotationRow(file: NoteFile, onDeleteClick: () -> Unit) {
    val (title, createdAt) = parseAnnotationName(file.name)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Panel,
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, color = OnSurface, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                if (createdAt.isNotBlank()) {
                    Text(
                        text = "作成: $createdAt",
                        color = OnSurface.copy(alpha = 0.6f),
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
            Surface(
                modifier = Modifier
                    .size(40.dp)
                    .clickable(onClick = onDeleteClick),
                shape = CircleShape,
                color = DangerRed.copy(alpha = 0.10f)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text("🗑", fontSize = 18.sp)
                }
            }
        }
    }
}

@Composable
private fun IconPillButton(symbol: String, contentDescription: String, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .size(40.dp)
            .clickable(onClick = onClick),
        shape = CircleShape,
        color = Panel.copy(alpha = 0.22f)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(symbol, color = OnVibrant, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/**
 * "{タイトル}__補記_{yyyyMMdd_HHmm}.md" を (タイトル, "yyyy-MM-dd HH:mm") に分解する。
 * 形式が想定と違う場合はできる範囲で表示にフォールバックする。
 */
private fun parseAnnotationName(fileName: String): Pair<String, String> {
    val base = fileName.removeSuffix(".md")
    val marker = "__補記_"
    val idx = base.lastIndexOf(marker)
    if (idx < 0) return base to ""
    val title = base.substring(0, idx)
    val stamp = base.substring(idx + marker.length)
    return title to formatStamp(stamp)
}

// "20260716_1530" -> "2026-07-16 15:30"（想定外はそのまま返す）
private fun formatStamp(stamp: String): String {
    val digits = stamp.filter { it.isDigit() }
    if (digits.length != 12) return stamp
    val y = digits.substring(0, 4)
    val mo = digits.substring(4, 6)
    val d = digits.substring(6, 8)
    val h = digits.substring(8, 10)
    val mi = digits.substring(10, 12)
    return "$y-$mo-$d $h:$mi"
}
