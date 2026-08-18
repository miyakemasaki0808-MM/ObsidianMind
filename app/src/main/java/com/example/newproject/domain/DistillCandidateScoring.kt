package com.example.newproject.domain

import com.example.newproject.model.DistillCandidate
import com.example.newproject.model.DistillLimits
import com.example.newproject.model.DistillSentence
import com.example.newproject.model.DistillSourceModel
import com.example.newproject.model.DistillTextRange

private const val TITLE_WEIGHT = 1.0
private const val HEADING_WEIGHT = 0.7
private const val PARAGRAPH_FIRST_BONUS = 0.3
private const val CHUNK_LAST_BONUS = 0.4
private const val HEADING_ADJACENT_BONUS = 0.2
private const val SHORT_SENTENCE_PENALTY = 0.25

/** 全文の各所を残しつつ、AIへ渡す上位候補を決定的に選ぶ。 */
internal fun selectDistillCandidates(
    model: DistillSourceModel,
    noteTitle: String,
    limit: Int = DistillLimits.MAX_AI_CANDIDATES
): List<DistillCandidate> {
    if (limit <= 0) return emptyList()
    // isLinkOnly をここで外すと、リンクだけのチャンクは以降の groupBy に現れないため
    // チャンク代表も出ない。「リンクしか無いセクションは代表を持たない」は追加の分岐ではなく
    // この除外の帰結である。
    val eligible = model.sentences.filter {
        it.text.isNotBlank() &&
            // 上限は句ではなく親文へ掛ける。句長で見ると、読点のある超過文だけが
            // 分割後に上限内の断片となって候補へ復活し、入力契約を迂回できる。
            // 割っていない文は contextRange == range なので判定は変わらない。
            it.contextRange.length <= DistillLimits.MAX_SENTENCE_CHARACTERS &&
            !it.isLinkOnly
    }
    if (eligible.isEmpty()) return emptyList()
    val bounded = boundedSentencesPreservingChunks(eligible, DistillLimits.MAX_SENTENCES_FOR_SCORING)
    val titleSignal = textBigrams(noteTitle)

    data class Scored(val sentence: DistillSentence, val score: Double, val structural: Double)
    val scored = bounded.map { sentence ->
        val structural = structuralWeight(sentence)
        val score = TITLE_WEIGHT * diceCoefficient(textBigrams(sentence.text), titleSignal) +
            HEADING_WEIGHT * diceCoefficient(
                textBigrams(sentence.text),
                textBigrams(sentence.heading.orEmpty())
            ) + structural +
            // 語句候補は短いのが当たり前なので、短文ペナルティの対象にしない。
            if (!sentence.isTerm && sentence.text.length < 15) -SHORT_SENTENCE_PENALTY else 0.0
        Scored(sentence, score, structural)
    }
    val ordering = compareByDescending<Scored> { it.score }
        .thenByDescending { it.structural }
        .thenBy { it.sentence.sourceIndex }

    // 1文から入れる句は上限まで。同じ文の句が枠を埋め尽くすのを防ぐ。
    // 割っていない文は contextRange が自分自身なので、この間引きに素通りする。
    val (terms, structural) = scored.partition { it.sentence.isTerm }
    val perSentence = structural
        .groupBy { it.sentence.contextRange }
        .values
        .flatMap { it.sortedWith(ordering).take(DistillLimits.MAX_CLAUSES_PER_SENTENCE) }

    // 同じ語が何度も出るノートで候補が同じ語句で埋まらないよう、表層文字列で1件へ落とす。
    val distinctTerms = terms
        .sortedWith(ordering)
        .distinctBy { it.sentence.text }
        .take(DistillLimits.MAX_TERM_CANDIDATES)

    val available = nonOverlappingBy(
        perSentence + distinctTerms,
        range = { it.sentence.range },
        order = { it.sentence.sourceIndex }
    )
    val leaders = available.groupBy { it.sentence.chunkIndex }.values.map { it.sortedWith(ordering).first() }
    val selected = LinkedHashMap<Int, Scored>()
    if (leaders.size > limit) {
        leaders.sortedWith(
            compareByDescending<Scored> { it.structural }
                .thenByDescending { it.score }
                .thenBy { it.sentence.sourceIndex }
        ).take(limit).forEach { selected[it.sentence.sourceIndex] = it }
    } else {
        leaders.forEach { selected[it.sentence.sourceIndex] = it }
        available.sortedWith(ordering).forEach { candidate ->
            if (selected.size < limit) selected.putIfAbsent(candidate.sentence.sourceIndex, candidate)
        }
    }

    return selected.values.sortedBy { it.sentence.sourceIndex }.mapIndexed { index, item ->
        DistillCandidate(
            id = distillCandidateId(index),
            sentence = item.sentence,
            score = item.score,
            structuralWeight = item.structural
        )
    }
}

/**
 * 候補どうしが重ならない集合へ落とす。**重なったら細かいほうを残す。**
 *
 * 書き戻しは重なりを `require` で拒むため（`applyDistillBold`）、重なる候補を同時に選べる状態を
 * UIへ出してはいけない。語句候補は親の内側に重なる初めての候補で、ここが唯一の防波堤になる。
 * **細かいほうを残すのは、粒度を細かくすることが語句候補の目的だから。**
 *
 * 文と句だけのときは互いに重ならないので、この関数は何も落とさない。
 */
private fun <T> nonOverlappingBy(
    candidates: List<T>,
    range: (T) -> DistillTextRange,
    order: (T) -> Int
): List<T> {
    val accepted = mutableListOf<T>()
    candidates
        .sortedWith(compareBy({ range(it).length }, { order(it) }))
        .forEach { candidate ->
            if (accepted.none { range(it).overlaps(range(candidate)) }) accepted += candidate
        }
    return accepted.sortedBy(order)
}

internal fun structuralWeight(sentence: DistillSentence): Double =
    (if (sentence.isParagraphFirst) PARAGRAPH_FIRST_BONUS else 0.0) +
        (if (sentence.isChunkLast || sentence.isNoteLast) CHUNK_LAST_BONUS else 0.0) +
        (if (sentence.isHeadingAdjacent) HEADING_ADJACENT_BONUS else 0.0)

private fun evenlyBoundedSentences(
    input: List<DistillSentence>,
    limit: Int
): List<DistillSentence> {
    if (input.size <= limit) return input
    if (limit <= 1) return listOf(input.last())
    val indices = (0 until limit).map { slot ->
        ((slot.toLong() * (input.lastIndex)) / (limit - 1)).toInt()
    }.distinct()
    return indices.map(input::get)
}

/**
 * 計算量を制限しても、小さなチャンクが全体の均等間引きで消えないよう代表文を先に確保する。
 * チャンク自体が上限を超える場合だけ、構造重みと実用的な文長を優先して二次間引きする。
 */
private fun boundedSentencesPreservingChunks(
    input: List<DistillSentence>,
    limit: Int
): List<DistillSentence> {
    if (input.size <= limit) return input
    if (limit <= 0) return emptyList()

    val representativeOrdering = compareByDescending<DistillSentence> { structuralWeight(it) }
        .thenByDescending { it.text.length >= 15 }
        .thenByDescending { it.text.length.coerceAtMost(80) }
        .thenBy { it.sourceIndex }
    val representatives = input.groupBy { it.chunkIndex }.values
        .map { chunk -> chunk.sortedWith(representativeOrdering).first() }

    if (representatives.size >= limit) {
        return representatives.sortedWith(representativeOrdering)
            .take(limit)
            .sortedBy { it.sourceIndex }
    }

    val representativeIndices = representatives.mapTo(mutableSetOf()) { it.sourceIndex }
    val remaining = input.filterNot { it.sourceIndex in representativeIndices }
    val additional = evenlyBoundedSentences(remaining, limit - representatives.size)
    return (representatives + additional).sortedBy { it.sourceIndex }
}

internal fun distillCandidateId(index: Int): String {
    require(index in 0..998)
    return "S" + (index + 1).toString().padStart(3, '0')
}
