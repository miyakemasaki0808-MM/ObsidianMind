package com.example.newproject.domain

import com.example.newproject.model.CRYSTAL_CURRENT_ID
import com.example.newproject.model.CRYSTAL_NONE_TOKEN
import com.example.newproject.model.CRYSTAL_SELECTION_LABEL
import com.example.newproject.model.CRYSTAL_SENTENCE_LABEL
import com.example.newproject.model.CrystalLimits

internal sealed interface CrystalAnswer {
    /** 筋が無い。何も書かない。 */
    data object None : CrystalAnswer

    /** 形が崩れている。何も書かない。**空振りと区別して記録しない**。 */
    data object Invalid : CrystalAnswer

    /** [ids] は先頭が今のノート。[sentence] はそのまま結晶として書く1文。 */
    data class Chosen(val ids: List<String>, val sentence: String) : CrystalAnswer
}

/**
 * 結晶の応答を検証する（→ `docs/dev/features/reflect_crystal.md` §5「応答の検証」）。
 *
 * 形は2行 — `選択: N1, N3` と `結晶: 〜。`。コロンの全角・半角、IDの大小文字と全角、
 * 2行の前後の余計な行は許す。**[validIds] は実際に渡した候補のIDだけ**を渡すこと
 * （提示していない候補を指した応答を通さない）。
 */
internal fun parseCrystalResponse(response: String, validIds: Set<String>): CrystalAnswer {
    val text = response.trim()
    if (NONE_ONLY.matches(text)) return CrystalAnswer.None

    val lines = text.lineSequence()
        .map { it.trim().trimStart('-', '*', '・', '•').trim() }
        .filter { it.isNotEmpty() }
        .toList()
    val selection = lines.firstNotNullOfOrNull { SELECTION_LINE.matchEntire(it)?.groupValues?.get(1) }
        ?: return CrystalAnswer.Invalid
    val rawSentence = lines.firstNotNullOfOrNull { SENTENCE_LINE.matchEntire(it)?.groupValues?.get(1) }
        ?: return CrystalAnswer.Invalid

    val ids = ID.findAll(normalizeWidth(selection))
        .map { "N" + it.groupValues[1].trimStart('0').ifEmpty { "0" } }
        .distinct()
        .toList()
    if (ids.isEmpty() || ids.any { it !in validIds }) return CrystalAnswer.Invalid
    if (CRYSTAL_CURRENT_ID !in ids) return CrystalAnswer.Invalid
    if (ids.size !in CrystalLimits.MIN_SOURCES..CrystalLimits.MAX_SOURCES) return CrystalAnswer.Invalid

    val sentence = cleanCrystalSentence(rawSentence) ?: return CrystalAnswer.Invalid
    return CrystalAnswer.Chosen(
        ids = listOf(CRYSTAL_CURRENT_ID) + ids.filterNot { it == CRYSTAL_CURRENT_ID },
        sentence = sentence
    )
}

/**
 * 1文として採れるなら整えて返す。採れなければ null。
 *
 * 句点が無ければ足す（Nano は末尾の句点を落とすことがある）。そのうえで、
 * 疑問文・2文以上・長さの外れ・**本文へ漏れた候補ID**を落とす。
 */
private fun cleanCrystalSentence(raw: String): String? {
    var sentence = raw.trim()
    QUOTE_PAIRS.firstOrNull { (open, close) -> sentence.startsWith(open) && sentence.endsWith(close) }
        ?.let { (open, close) -> sentence = sentence.removePrefix(open).removeSuffix(close).trim() }
    if (sentence.isEmpty()) return null
    // **選択欄と同じ表記の集合で見る。** 選択欄は小文字・全角・空白付きのIDも読むので、
    // 本文の検査が大文字だけを見ると、その表記のIDが結晶の文へ残る。保存する文そのものは均さない。
    if (ID.containsMatchIn(normalizeWidth(sentence))) return null
    if (sentence.last() !in SENTENCE_ENDS) sentence += "。"
    if (sentence.last() in QUESTION_ENDS || sentence.endsWith("か。")) return null
    if (sentence.dropLast(1).any { it in SENTENCE_ENDS }) return null
    val length = sentence.codePointCount(0, sentence.length)
    if (length !in CrystalLimits.SENTENCE_MIN_CHARACTERS..CrystalLimits.SENTENCE_MAX_CHARACTERS) return null
    return sentence
}

/** 全角の数字・`Ｎ`・空白を半角へ寄せる。**IDの照合にだけ使う**（選択欄と本文の検査で共有する）。 */
private fun normalizeWidth(value: String): String = buildString(value.length) {
    for (char in value) {
        append(
            when (char) {
                in '０'..'９' -> '0' + (char - '０')
                'Ｎ', 'ｎ' -> 'N'
                '　' -> ' '
                else -> char
            }
        )
    }
}

private val NONE_ONLY = Regex("""^[「"']?$CRYSTAL_NONE_TOKEN[」"']?[。.]?$""", RegexOption.IGNORE_CASE)
private val SELECTION_LINE =
    Regex("""^(?:$CRYSTAL_SELECTION_LABEL|IDs?)\s*[:：]\s*(.*)$""", RegexOption.IGNORE_CASE)
private val SENTENCE_LINE =
    Regex("""^(?:$CRYSTAL_SENTENCE_LABEL|Sentence)\s*[:：]\s*(.+)$""", RegexOption.IGNORE_CASE)
/** 候補ID。英字の続きに現れる `N` は拾わない（`DNN1` のような語をIDと読まない）。 */
private val ID = Regex("""(?<![A-Za-z])[Nn]\s*(\d+)""")
private val QUOTE_PAIRS = listOf("「" to "」", "\"" to "\"", "“" to "”", "『" to "』")
private const val SENTENCE_ENDS = "。！？!?"
private const val QUESTION_ENDS = "？?"
