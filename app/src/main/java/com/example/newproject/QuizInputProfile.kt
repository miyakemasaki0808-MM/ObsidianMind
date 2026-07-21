package com.example.newproject

import kotlin.math.max

/** AIを使わず、実際にクイズへ渡す文脈の構造だけから出題形式を決める。 */
internal data class QuizInputProfile(
    val meaningfulCharacters: Int,
    val sentenceSignals: Int,
    val codeRatio: Double,
    val format: QuizFormat
)

internal fun profileQuizInput(content: String): QuizInputProfile {
    var inFence = false
    var codeCharacters = 0
    val proseLines = mutableListOf<String>()

    content.lineSequence().forEach { sourceLine ->
        val trimmed = sourceLine.trim()
        val fence = trimmed.startsWith("```") || trimmed.startsWith("~~~")
        if (fence) {
            inFence = !inFence
            return@forEach
        }
        if (inFence) {
            codeCharacters += trimmed.count { !it.isWhitespace() }
        } else {
            val prose = trimmed
                .replace(Regex("^#{1,6}\\s+"), "")
                .replace(Regex("^[-+*>]\\s+"), "")
                .replace("**", "")
                .replace("__", "")
                .replace("`", "")
                .trim()
            if (prose.isNotEmpty()) proseLines += prose
        }
    }

    val proseText = proseLines.joinToString("\n")
    val meaningfulCharacters = proseText.count { it.isLetterOrDigit() }
    val punctuationSignals = proseText.count { it in "。！？.!?" }
    val sentenceSignals = max(punctuationSignals, proseLines.size)
    val denominator = meaningfulCharacters + codeCharacters
    val codeRatio = if (denominator == 0) 0.0 else codeCharacters.toDouble() / denominator
    val format = when {
        codeRatio >= CODE_DOMINANT_RATIO -> QuizFormat.ThreeChoice
        meaningfulCharacters < SHORT_CONTEXT_CHARACTERS || sentenceSignals <= SHORT_CONTEXT_SENTENCES ->
            QuizFormat.TrueFalse
        meaningfulCharacters >= RICH_CONTEXT_CHARACTERS && sentenceSignals >= RICH_CONTEXT_SENTENCES ->
            QuizFormat.FourChoice
        else -> QuizFormat.ThreeChoice
    }
    return QuizInputProfile(meaningfulCharacters, sentenceSignals, codeRatio, format)
}

private const val CODE_DOMINANT_RATIO = 0.45
private const val SHORT_CONTEXT_CHARACTERS = 180
private const val SHORT_CONTEXT_SENTENCES = 2
private const val RICH_CONTEXT_CHARACTERS = 700
private const val RICH_CONTEXT_SENTENCES = 6
