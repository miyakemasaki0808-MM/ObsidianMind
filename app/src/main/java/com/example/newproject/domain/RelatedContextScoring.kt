package com.example.newproject.domain

import com.example.newproject.model.RelatedReason

// 関連ノートAI候補の「本文シグナル」による再ランク採点（Phase 3b・純ロジック・Uri非依存）。
// タイトルだけで選んだ上位集合（Phase 3a）を、Phase 2bで読み込んだ本文（tags/snippet）を使って
// 並べ替え、プロンプト先頭に本文的に近い候補を置く（Nanoの位置バイアスを味方につける）。

// 実VaultとPixelでの計測を踏まえて調整する前提の初期値。
// タグ一致を主シグナルにしつつ、本文スニペット類似とタイトル類似を弱く加点する。
private const val W_TAG = 1.0
private const val W_BODY = 0.5
private const val W_TITLE_SIM = 0.5

/**
 * 現ノート側の再ランク用シグナル。候補ごとに作り直さず、1回だけ構築して使い回す。
 * bigram集合とタグ集合を先に畳んでおくことで、候補数に比例した再計算を避ける。
 */
internal data class CurrentNoteSignals(
    val titleBigrams: Set<String>,
    val snippetBigrams: Set<String>,
    val tags: Set<String>
)

/**
 * 現ノートの本文シグナルを組み立てる。snippet は候補側と同じ抽出・長さに揃え、
 * 短いスニペット同士の対称な比較にする（長い本文と短いスニペットの非対称比較を避ける）。
 */
internal fun buildCurrentNoteSignals(
    currentTitle: String,
    currentContent: String,
    currentTags: List<String>,
    snippetLen: Int
): CurrentNoteSignals = CurrentNoteSignals(
    titleBigrams = titleBigrams(currentTitle),
    snippetBigrams = textBigrams(extractRelatedSnippet(currentContent, snippetLen)),
    tags = normalizeTags(currentTags)
)

/**
 * 候補1件の再ランクスコア。tags 一致を主に、本文スニペット類似とタイトル類似を加点する。
 * tags が無い候補は自然に本文・タイトルへフォールバックする。tieBreak はタイトル類似とし、
 * 本文シグナルが薄い候補同士でも決定的に並ぶようにする。
 */
internal fun relatedContextScore(
    current: CurrentNoteSignals,
    candidateTitle: String,
    candidateSnippet: String,
    candidateTags: List<String>
): CandidateScore {
    val tagSim = diceCoefficient(current.tags, normalizeTags(candidateTags))
    val bodySim = diceCoefficient(current.snippetBigrams, textBigrams(candidateSnippet))
    val titleSim = diceCoefficient(current.titleBigrams, titleBigrams(candidateTitle))
    val score = W_TAG * tagSim + W_BODY * bodySim + W_TITLE_SIM * titleSim
    return CandidateScore(score = score, tieBreak = titleSim)
}

// 根拠として出すのに要る最小の近さ（題名・書き出しの Dice）。少し重なるだけの題名を「題名が近い」と
// 言わないため。タグは1つでも共通なら根拠になる。実VaultとPixelで見え方を確かめて調整する前提の初期値。
private const val MIN_SIMILARITY_FOR_REASON = 0.2

/**
 * 候補を近いと見た根拠のうち、再ランクでいちばん効いたもの（重みをかけた寄与で比べる）。
 * 同じだけ効いていればタグ・書き出し・題名の順に選ぶ。弱い重なりしか無ければ null（→ features/related_notes_ai.md §5）。
 * 共通のタグは候補側の書き方で返す（先頭の `#` だけ除く）。
 */
internal fun relatedReason(
    current: CurrentNoteSignals,
    candidateTitle: String,
    candidateSnippet: String,
    candidateTags: List<String>
): RelatedReason? {
    val sharedTag = candidateTags.firstOrNull { normalizeTag(it) in current.tags }?.trim()?.trimStart('#')
    val tagSim = diceCoefficient(current.tags, normalizeTags(candidateTags))
    val bodySim = diceCoefficient(current.snippetBigrams, textBigrams(candidateSnippet))
    val titleSim = diceCoefficient(current.titleBigrams, titleBigrams(candidateTitle))
    // maxByOrNull は同点なら先の要素を返すので、並びがそのまま優先順になる。
    return listOfNotNull(
        sharedTag?.let { RelatedReason.SharedTag(it) to W_TAG * tagSim },
        (RelatedReason.SimilarOpening to W_BODY * bodySim).takeIf { bodySim >= MIN_SIMILARITY_FOR_REASON },
        (RelatedReason.SimilarTitle to W_TITLE_SIM * titleSim).takeIf { titleSim >= MIN_SIMILARITY_FOR_REASON }
    ).maxByOrNull { it.second }?.first
}

// タグは大小文字と先頭の # を無視して集合化する（表記ゆれを吸収）。
private fun normalizeTags(tags: List<String>): Set<String> = tags.mapNotNull(::normalizeTag).toSet()

private fun normalizeTag(tag: String): String? = tag.trim().trimStart('#').lowercase().ifBlank { null }
