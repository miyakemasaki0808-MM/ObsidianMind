package com.example.newproject.ui.screen

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.newproject.model.state.QuizCard
import com.example.newproject.model.state.QuizFormat
import com.example.newproject.model.state.QuizState
import com.example.newproject.ui.theme.OnButtonPrimary
import com.example.newproject.ui.theme.OnQuizLoading
import com.example.newproject.ui.theme.OnQuizError
import com.example.newproject.ui.theme.FailureMark
import com.example.newproject.ui.theme.OnQuizAccent
import com.example.newproject.ui.theme.OnQuizDisabled
import com.example.newproject.ui.theme.OnQuizFaint
import com.example.newproject.ui.theme.OnQuizMuted
import com.example.newproject.ui.theme.OnQuizSurface
import com.example.newproject.ui.theme.OnQuizEmpty
import com.example.newproject.ui.theme.QuizCorrectPanel
import com.example.newproject.ui.theme.QuizOutline
import com.example.newproject.ui.theme.QuizPanel
import com.example.newproject.ui.theme.QuizPanelDim
import com.example.newproject.ui.theme.QuizSurface
import com.example.newproject.ui.theme.QuizWrongPanel
import com.example.newproject.ui.theme.SuccessMark
import com.example.newproject.ui.theme.ButtonPrimary

@Composable
fun QuizScreen(
    noteTitle: String,
    quizState: QuizState,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(QuizSurface)
            .statusBarsPadding()
            .padding(horizontal = 16.dp)
    ) {
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = noteTitle,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            color = OnQuizAccent,
            maxLines = 2
        )
        Spacer(modifier = Modifier.height(16.dp))

        Box(modifier = Modifier.weight(1f)) {
            when (quizState) {
                is QuizState.Loading -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = OnQuizAccent)
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                "${quizState.format.displayName}を生成中…",
                                fontSize = 14.sp,
                                color = OnQuizLoading
                            )
                        }
                    }
                }
                is QuizState.Error -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("エラー: ${quizState.message}", color = OnQuizError)
                    }
                }
                is QuizState.Success -> {
                    if (quizState.cards.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("問題を生成できませんでした。", fontSize = 14.sp, color = OnQuizEmpty)
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
            Text("← ノートに戻る", color = OnQuizAccent)
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
            color = OnQuizFaint
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
        color = QuizPanel
    ) {
        Text(
            text = question,
            fontSize = 16.sp,
            lineHeight = 24.sp,
            fontWeight = FontWeight.Medium,
            color = OnQuizSurface,
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
            selected == null -> QuizPanel
            isCorrect -> QuizCorrectPanel
            isSelected -> QuizWrongPanel
            else -> QuizPanelDim
        }
        val borderColor = when {
            selected == null -> QuizOutline
            isCorrect -> SuccessMark
            isSelected -> FailureMark
            else -> QuizOutline
        }
        val labelColor = when {
            selected == null -> OnQuizAccent
            isCorrect -> SuccessMark
            isSelected -> FailureMark
            else -> OnQuizDisabled
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
                    color = if (selected != null && !isCorrect && !isSelected) OnQuizDisabled else OnQuizSurface
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
            color = if (isCorrect) QuizCorrectPanel else QuizWrongPanel
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = if (isCorrect) "✅ 正解！" else "❌ 不正解。正解は ${labelAt(card.correctIndex)}",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isCorrect) SuccessMark else FailureMark
                )
                if (card.explanation.isNotBlank()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = card.explanation,
                        fontSize = 14.sp,
                        lineHeight = 22.sp,
                        color = OnQuizMuted
                    )
                }
            }
        }
        if (!isLast) {
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = onNext,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = ButtonPrimary, contentColor = OnButtonPrimary)
            ) {
                Text("次の問題 →", color = OnButtonPrimary)
            }
        }
    }
}
