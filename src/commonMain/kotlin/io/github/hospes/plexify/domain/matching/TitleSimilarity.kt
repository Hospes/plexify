package io.github.hospes.plexify.domain.matching

import kotlin.math.max
import kotlin.math.min

// Keep only alphanumeric chars for title comparison, so "Spider-Man" matches "Spiderman".
internal fun String.normalizedTitle(): String = filter { it.isLetterOrDigit() }.lowercase()

/** Normalized Levenshtein similarity in [0.0, 1.0]; 1.0 means identical strings. */
internal fun titleSimilarity(lhs: String, rhs: String): Double {
    val distance = levenshtein(lhs, rhs)
    val length = max(lhs.length, rhs.length)
    return if (length > 0) 1.0 - (distance.toDouble() / length) else 0.0
}

internal fun calculateTitleConfidence(query: String, resultTitle: String): Double {
    val distance = levenshtein(query.lowercase(), resultTitle.lowercase())
    val titleLength = max(query.length, resultTitle.length)
    return if (titleLength > 0) (1.0 - (distance.toDouble() / titleLength)) * 100.0 else 0.0
}

/**
 * Calculates the Levenshtein distance between two strings.
 * This is a measure of the number of single-character edits (insertions, deletions, or substitutions)
 * required to change one word into the other.
 */
internal fun levenshtein(lhs: CharSequence, rhs: CharSequence): Int {
    val lhsLength = lhs.length
    val rhsLength = rhs.length

    var cost = Array(lhsLength + 1) { it }
    val newCost = Array(lhsLength + 1) { 0 }

    for (i in 1..rhsLength) {
        newCost[0] = i
        for (j in 1..lhsLength) {
            val match = if (lhs[j - 1] == rhs[i - 1]) 0 else 1
            val costReplace = cost[j - 1] + match
            val costInsert = cost[j] + 1
            val costDelete = newCost[j - 1] + 1
            newCost[j] = min(min(costInsert, costDelete), costReplace)
        }
        cost = newCost.copyOf()
    }
    return cost[lhsLength]
}
