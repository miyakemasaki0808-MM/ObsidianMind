package com.example.newproject.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.newproject.QuizCard
import com.example.newproject.QuizFormat
import com.example.newproject.QuizState
import com.example.newproject.ui.theme.ButtonPrimary
import com.example.newproject.ui.theme.Indigo
import com.example.newproject.ui.theme.OnVibrant

@Composable
fun QuizScreen(
    noteTitle: String,
    quizState: QuizState,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF1A1C2E))
            .statusBarsPadding()
            .padding(horizontal = 16.dp)
    ) {
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = noteTitle,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFFB0B8FF),
            maxLines = 2
        )
        Spacer(modifier = Modifier.height(16.dp))

        Box(modifier = Modifier.weight(1f)) {
            when (quizState) {
                is QuizState.Loading -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = Indigo)
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                "${quizState.format.displayName}を生成中…",
                                fontSize = 14.sp,
                                color = Color(0xFFAAAAAA)
                            )
                        }
                    }
                }
                is QuizState.Error -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("エラー: ${quizState.message}", color = Color(0xFFCC0000))
                    }
                }
                is QuizState.Success -> {
                    if (quizState.cards.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("問題を生成できませんでした。", fontSize = 14.sp, color = Color(0xFF555555))
                        }
                    } else {
                        MultipleChoicePager(cards = quizState.cards)
                    }
                }
                else -> {}
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
        OutlinedButton(
            onClick = onBack,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp)
        ) {
            Text("← ノートに戻る", color = Color(0xFFB0B8FF))
        }
        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun MultipleChoicePager(cards: List<QuizCard>) {
    var currentIndex by remember { mutableIntStateOf(0) }
    val card = cards[currentIndex]

    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = "${currentIndex + 1} / ${cards.size}",
            fontSize = 13.sp,
            color = Color(0xFF777799)
        )
        Spacer(modifier = Modifier.height(12.dp))

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            QuestionCard(question = card.question)
            Spacer(modifier = Modifier.height(4.dp))
            ChoiceButtons(
                card = card,
                isLast = currentIndex == cards.lastIndex,
                onNext = { if (currentIndex < cards.lastIndex) currentIndex++ }
            )
        }
    }
}

@Composable
private fun QuestionCard(question: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        shadowElevation = 2.dp,
        color = Color(0xFF2A2D45)
    ) {
        Text(
            text = question,
            fontSize = 16.sp,
            lineHeight = 24.sp,
            fontWeight = FontWeight.Medium,
            color = Color(0xFFEEEEFF),
            modifier = Modifier.padding(16.dp)
        )
    }
}

@Composable
private fun ChoiceButtons(card: QuizCard, isLast: Boolean, onNext: () -> Unit) {
    var selected by remember(card) { mutableStateOf<Int?>(null) }
    val labels = if (card.format == QuizFormat.TrueFalse) {
        listOf("○", "×")
    } else {
        listOf("A", "B", "C", "D")
    }
    fun labelAt(index: Int): String = labels.getOrElse(index) { (index + 1).toString() }

    card.choices.forEachIndexed { index, choice ->
        val isSelected = selected == index
        val isCorrect = index == card.correctIndex
        val bgColor = when {
            selected == null -> Color(0xFF2A2D45)
            isCorrect -> Color(0xFF1B3A2A)
            isSelected -> Color(0xFF3A1B1B)
            else -> Color(0xFF222436)
        }
        val borderColor = when {
            selected == null -> Color(0xFF3D4070)
            isCorrect -> Color(0xFF4CAF50)
            isSelected -> Color(0xFFEF5350)
            else -> Color(0xFF3D4070)
        }
        val labelColor = when {
            selected == null -> Color(0xFFB0B8FF)
            isCorrect -> Color(0xFF4CAF50)
            isSelected -> Color(0xFFEF5350)
            else -> Color(0xFF555577)
        }

        Surface(
            modifier = Modifier
                .fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            shadowElevation = if (selected == null) 1.dp else 0.dp,
            color = bgColor,
            border = androidx.compose.foundation.BorderStroke(1.dp, borderColor),
            onClick = { if (selected == null) selected = index }
        ) {
            androidx.compose.foundation.layout.Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = labelAt(index),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = labelColor
                )
                Text(
                    text = choice,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    color = if (selected != null && !isCorrect && !isSelected) Color(0xFF555577) else Color(0xFFEEEEFF)
                )
            }
        }
    }

    if (selected != null) {
        val isCorrect = selected == card.correctIndex
        Spacer(modifier = Modifier.height(8.dp))
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight(),
            shape = RoundedCornerShape(10.dp),
            color = if (isCorrect) Color(0xFF1B3A2A) else Color(0xFF3A1B1B)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = if (isCorrect) "✅ 正解！" else "❌ 不正解。正解は ${labelAt(card.correctIndex)}",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isCorrect) Color(0xFF4CAF50) else Color(0xFFEF5350)
                )
                if (card.explanation.isNotBlank()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = card.explanation,
                        fontSize = 14.sp,
                        lineHeight = 22.sp,
                        color = Color(0xFFCCCCDD)
                    )
                }
            }
        }
        if (!isLast) {
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = onNext,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = ButtonPrimary)
            ) {
                Text("次の問題 →", color = OnVibrant)
            }
        }
    }
}
