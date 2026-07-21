package com.example.newproject.domain

internal data class DistillTransformResult(
    val content: String,
    val insertedRanges: List<DistillTextRange>
)

/** 選択文を原文offsetの降順で囲み、選択範囲外の文字を変更しない。 */
internal fun applyDistillBold(
    content: String,
    ranges: Collection<DistillTextRange>
): DistillTransformResult {
    val sorted = ranges.distinct().sortedBy { it.start }
    sorted.forEach { range ->
        require(range.endExclusive <= content.length) { "Range is outside source content: $range" }
    }
    sorted.zipWithNext().forEach { (left, right) ->
        require(left.endExclusive <= right.start) { "Distill ranges overlap: $left and $right" }
    }
    var transformed = content
    sorted.asReversed().forEach { range ->
        transformed = transformed.substring(0, range.endExclusive) + "**" +
            transformed.substring(range.endExclusive)
        transformed = transformed.substring(0, range.start) + "**" + transformed.substring(range.start)
    }
    return DistillTransformResult(transformed, sorted)
}

internal fun projectedBoldRatio(
    model: DistillSourceModel,
    selectedRanges: Collection<DistillTextRange>
): Double {
    if (model.eligibleBodyCharacterCount <= 0) return 0.0
    val added = selectedRanges.distinct().sumOf { range ->
        model.content.substring(range.start, range.endExclusive).count { !it.isWhitespace() }
    }
    return (model.existingBoldCharacterCount + added).toDouble() / model.eligibleBodyCharacterCount
}

internal fun isWithinDistillBoldLimit(
    model: DistillSourceModel,
    selectedRanges: Collection<DistillTextRange>,
    maxRatio: Double = DistillLimits.MAX_BOLD_RATIO
): Boolean = projectedBoldRatio(model, selectedRanges) <= maxRatio
