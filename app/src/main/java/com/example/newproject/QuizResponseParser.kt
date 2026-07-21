package com.example.newproject

// LLMの行形式応答を2〜4択のQuizCardへ変換する。空行には依存せず、Q:を問題境界にする。
internal fun parseQuizResponse(
    raw: String,
    expectedFormat: QuizFormat = QuizFormat.FourChoice
): List<QuizCard> {
    val blocks = mutableListOf<MutableList<String>>()
    raw.replace("\r\n", "\n").lineSequence().forEach { rawLine ->
        val line = rawLine.trim().removePrefix(Regex("^\\d+[.)]\\s*"))
        if (line.isEmpty()) return@forEach
        if (fieldValue(line, "Q") != null) blocks.add(mutableListOf())
        blocks.lastOrNull()?.add(line)
    }
    val limit = when (expectedFormat) {
        QuizFormat.FourChoice -> 1
        QuizFormat.TrueFalse, QuizFormat.ThreeChoice -> 2
    }
    return blocks.mapNotNull { lines -> parseQuizBlock(lines, expectedFormat) }.take(limit)
}

private fun parseQuizBlock(lines: List<String>, expectedFormat: QuizFormat): QuizCard? {
    val question = lines.firstNotNullOfOrNull { fieldValue(it, "Q") } ?: return null
    val answer = lines.firstNotNullOfOrNull { fieldValue(it, "ANSWER") } ?: return null
    val explanation = lines.firstNotNullOfOrNull { fieldValue(it, "EXPLANATION") }.orEmpty()

    if (expectedFormat == QuizFormat.TrueFalse) {
        val correctIndex = when (answer.trim().trimEnd('.', '。').uppercase()) {
            "TRUE", "T", "A", "○", "正しい" -> 0
            "FALSE", "F", "B", "×", "誤り" -> 1
            else -> return null
        }
        return QuizCard(
            question = question,
            choices = listOf("正しい", "誤り"),
            correctIndex = correctIndex,
            explanation = explanation,
            format = QuizFormat.TrueFalse
        )
    }

    val labels = listOf("A", "B", "C", "D")
    val choices = labels.map { label ->
        lines.firstNotNullOfOrNull { fieldValue(it, label) }
    }.takeWhile { it != null }.filterNotNull()
    if (choices.size !in 3..4) return null
    // 小型モデルは "B."・"(B)"・"B) 選択肢文"・"The answer is B" のように崩すため、
    // 末尾記号や余分な語に依存せず、単独で現れた最初の A〜D レターだけを抽出する。
    // 単語境界を要求することで "ANSWER" の A や "BEST" の B を誤検出しない
    // （○× パスと同等の防御性に揃える）。
    val maxLabel = 'A' + (choices.size - 1)
    val answerLetter = Regex("\\b[A-$maxLabel]\\b", RegexOption.IGNORE_CASE)
        .find(answer)?.value?.uppercase()
    val correctIndex = answerLetter?.let { labels.take(choices.size).indexOf(it) } ?: -1
    if (correctIndex < 0) return null
    val actualFormat = when (choices.size) {
        3 -> QuizFormat.ThreeChoice
        else -> QuizFormat.FourChoice
    }
    return QuizCard(question, choices, correctIndex, explanation, actualFormat)
}

private fun fieldValue(line: String, name: String): String? {
    val match = Regex("(?i)^\\*{0,2}${Regex.escape(name)}\\*{0,2}\\s*[:：]\\s*(.+)$")
        .matchEntire(line) ?: return null
    return match.groupValues[1].trim().trim('*').trim().takeIf { it.isNotEmpty() }
}

private fun String.removePrefix(pattern: Regex): String = replaceFirst(pattern, "")
