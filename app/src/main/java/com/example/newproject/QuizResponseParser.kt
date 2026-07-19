package com.example.newproject

// LLMのクイズ応答（Q:/A:/B:/C:/D:/ANSWER:/EXPLANATION: 形式）を QuizCard に変換する。
// 純関数のためユニットテスト可能。
//
// 空行区切り（\n\n）には依存せず、"Q:" 行を新しい問題の開始として分割する。
// モデルが空行を挟まない・\r\n を返す・行頭に空白を入れる等の揺れに耐える。
internal fun parseQuizResponse(raw: String): List<QuizCard> {
    val blocks = mutableListOf<MutableList<String>>()
    raw.replace("\r\n", "\n").lineSequence().forEach { rawLine ->
        val line = rawLine.trim()
        if (line.isEmpty()) return@forEach
        if (line.startsWith("Q:")) blocks.add(mutableListOf())
        blocks.lastOrNull()?.add(line)   // 最初の Q: より前の前置きは捨てる
    }
    return blocks.mapNotNull { lines ->
        val q = lines.firstOrNull { it.startsWith("Q:") }?.removePrefix("Q:")?.trim()
        val a = lines.firstOrNull { it.startsWith("A:") }?.removePrefix("A:")?.trim()
        val b = lines.firstOrNull { it.startsWith("B:") }?.removePrefix("B:")?.trim()
        val c = lines.firstOrNull { it.startsWith("C:") }?.removePrefix("C:")?.trim()
        val d = lines.firstOrNull { it.startsWith("D:") }?.removePrefix("D:")?.trim()
        val answer = lines.firstOrNull { it.startsWith("ANSWER:") }?.removePrefix("ANSWER:")?.trim()
        val explanation = lines.firstOrNull { it.startsWith("EXPLANATION:") }?.removePrefix("EXPLANATION:")?.trim() ?: ""
        if (q != null && a != null && b != null && c != null && d != null && answer != null) {
            val choices = listOf(a, b, c, d)
            val correctIndex = listOf("A", "B", "C", "D").indexOf(answer)
            if (correctIndex >= 0) QuizCard(q, choices, correctIndex, explanation) else null
        } else null
    }
}
