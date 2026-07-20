package com.example.newproject.domain

import com.example.newproject.toNormalizedObsidianTitle

// 関連ノートAI候補の話題スコアリング（純ロジック・Uri非依存）。
// タイトル類似を主シグナル、採番近接を従シグナルにすることで、採番が離れた候補も
// Nanoへ渡す上位集合へ引き上げつつ、既存Vaultの採番規律も弱い加点として活かす。

// 実VaultとPixelでの計測を踏まえて調整する前提の初期値。
private const val W_TITLE = 1.0
private const val W_PREFIX = 0.3

private data class ScoredCandidate<T>(
    val value: T,
    val inputIndex: Int,
    val score: Double,
    val prefixTier: Double
)

/** 採番を除いた正規化タイトルから、連続する2文字の集合を作る。 */
internal fun titleBigrams(title: String): Set<String> {
    val normalized = normalizeTopicTitle(title)
    if (normalized.length <= 1) return emptySet()
    return (0 until normalized.lastIndex)
        .mapTo(LinkedHashSet()) { index -> normalized.substring(index, index + 2) }
}

/** 2つのbigram集合のDice係数。比較材料が無い場合は関連なしとして0を返す。 */
internal fun diceCoefficient(a: Set<String>, b: Set<String>): Double {
    if (a.isEmpty() && b.isEmpty()) return 0.0
    val intersectionSize = a.count { it in b }
    return 2.0 * intersectionSize / (a.size + b.size)
}

/** タイトル類似と採番近接を合成した、単一候補のスコア。 */
internal fun relatedCandidateScore(
    currentTitle: String,
    candidateTitle: String,
    currentPrefix: String?
): Double = scoreCandidate(
    currentBigrams = titleBigrams(currentTitle),
    candidateTitle = candidateTitle,
    currentPrefix = currentPrefix
).first

/**
 * 全候補を話題スコアで並べ、AIへ渡す上位集合を返す。
 *
 * 決定的チャンネルのタイトルは上限適用前に除外する。現在タイトル側の正規化と
 * bigram生成は1回だけ行い、Vault件数に比例して同じ処理を繰り返さない。
 * 同点は採番近接、最後に元の入力順で解決して、常に決定的な並びを保つ。
 */
internal fun <T> rankRelatedCandidates(
    currentTitle: String,
    candidates: List<T>,
    titleOf: (T) -> String,
    excludedTitles: Set<String>,
    limit: Int
): List<T> {
    if (limit <= 0) return emptyList()

    val excluded = excludedTitles.map { it.toNormalizedObsidianTitle() }.toSet()
    val currentPrefix = extractHexPrefix(currentTitle)
    val currentBigrams = titleBigrams(currentTitle)

    return candidates
        .mapIndexedNotNull { index, candidate ->
            val title = titleOf(candidate)
            if (title.toNormalizedObsidianTitle() in excluded) return@mapIndexedNotNull null
            val (score, tier) = scoreCandidate(currentBigrams, title, currentPrefix)
            ScoredCandidate(candidate, index, score, tier)
        }
        .sortedWith(
            compareByDescending<ScoredCandidate<T>> { it.score }
                .thenByDescending { it.prefixTier }
                .thenBy { it.inputIndex }
        )
        .take(limit)
        .map { it.value }
}

private fun scoreCandidate(
    currentBigrams: Set<String>,
    candidateTitle: String,
    currentPrefix: String?
): Pair<Double, Double> {
    val titleSimilarity = diceCoefficient(currentBigrams, titleBigrams(candidateTitle))
    val tier = prefixTier(currentPrefix, extractHexPrefix(candidateTitle))
    return (W_TITLE * titleSimilarity + W_PREFIX * tier) to tier
}

private fun prefixTier(currentPrefix: String?, candidatePrefix: String?): Double {
    if (currentPrefix == null || candidatePrefix == null) return 0.0
    return when {
        currentPrefix.take(2) == candidatePrefix.take(2) -> 1.0
        currentPrefix.take(1) == candidatePrefix.take(1) -> 0.5
        else -> 0.0
    }
}

private fun normalizeTopicTitle(title: String): String {
    val prefix = extractHexPrefix(title)
    val withoutPrefix = if (prefix == null) {
        title
    } else {
        title.drop(prefix.length).trimStart { char ->
            char.isWhitespace() || char in "-_.:–—"
        }
    }
    return withoutPrefix.toNormalizedObsidianTitle()
}
