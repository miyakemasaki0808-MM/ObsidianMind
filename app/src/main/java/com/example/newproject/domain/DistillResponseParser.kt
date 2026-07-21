package com.example.newproject.domain

private val DISTILL_ID_PATTERN = Regex(
    "(?<![A-Z0-9])S\\d{3}(?![A-Z0-9])",
    RegexOption.IGNORE_CASE
)

/** 前置き等は許容するが、境界付き固定3桁の既知IDだけを出現順に返す。 */
internal fun parseDistillResponseIds(
    response: String,
    validIds: Set<String>,
    limit: Int = DistillLimits.FINAL_SELECTION_LIMIT
): List<String> {
    if (limit <= 0 || validIds.isEmpty()) return emptyList()
    val normalizedValid = validIds.associateBy { it.uppercase() }
    val result = LinkedHashSet<String>()
    DISTILL_ID_PATTERN.findAll(response).forEach { match ->
        val valid = normalizedValid[match.value.uppercase()] ?: return@forEach
        result += valid
        if (result.size >= limit) return result.toList()
    }
    return result.toList()
}
