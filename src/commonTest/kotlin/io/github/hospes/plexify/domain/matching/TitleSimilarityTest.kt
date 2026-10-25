package io.github.hospes.plexify.domain.matching

import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the two title metrics [MatchScoring] scores with: exact distances and exact doubles, so a
 * rewrite of [levenshtein] can't shift a score by a rounding step.
 */
class TitleSimilarityTest {

    @Test
    fun `levenshtein distances`() {
        assertEquals(0, levenshtein("", ""))
        assertEquals(3, levenshtein("abc", ""))
        assertEquals(3, levenshtein("", "abc"))
        assertEquals(0, levenshtein("Inception", "Inception"))
        assertEquals(3, levenshtein("kitten", "sitting"))
        assertEquals(3, levenshtein("sitting", "kitten"))
        assertEquals(3, levenshtein("Saturday", "Sunday"))
        assertEquals(2, levenshtein("flaw", "lawn"))
        // Case-sensitive: the hyphen and the capital M.
        assertEquals(2, levenshtein("Spider-Man", "Spiderman"))
        assertEquals(1, levenshtein("進撃の巨人", "進撃の巨人2"))
    }

    @Test
    fun `levenshtein matches the full-matrix definition on every short string`() {
        val strings = allStrings("ab-", maxLength = 4)
        for (lhs in strings) for (rhs in strings) {
            assertEquals(referenceLevenshtein(lhs, rhs), levenshtein(lhs, rhs), "\"$lhs\" vs \"$rhs\"")
        }
    }

    @Test
    fun `title similarity`() {
        assertEquals(1.0, titleSimilarity("inception", "inception"))
        assertEquals(0.0, titleSimilarity("", ""))
        assertEquals(0.0, titleSimilarity("abc", ""))
        assertEquals(0.5714285714285714, titleSimilarity("kitten", "sitting"))
        assertEquals(0.625, titleSimilarity("Saturday", "Sunday"))
        // Both sides normalized first, as rankMatches does.
        assertEquals(1.0, titleSimilarity("Spiderman".normalizedTitle(), "Spider-Man".normalizedTitle()))
    }

    @Test
    fun `title confidence`() {
        assertEquals(100.0, calculateTitleConfidence("Inception", "inception"))
        assertEquals(0.0, calculateTitleConfidence("", ""))
        // Only lowercased: the hyphen costs one edit of ten (MatchScoring's double count).
        assertEquals(90.0, calculateTitleConfidence("Spiderman", "Spider-Man"))
        assertEquals(57.14285714285714, calculateTitleConfidence("Kitten", "SITTING"))
    }

    @Test
    fun `normalized title`() {
        assertEquals("spidermannowayhome", "Spider-Man: No Way Home".normalizedTitle())
        assertEquals("進撃の巨人2", "進撃の巨人 2".normalizedTitle())
        assertEquals("", " - ".normalizedTitle())
    }

    private fun allStrings(alphabet: String, maxLength: Int): List<String> {
        var level = listOf("")
        val all = level.toMutableList()
        repeat(maxLength) {
            level = level.flatMap { prefix -> alphabet.map { prefix + it } }
            all += level
        }
        return all
    }

    // The textbook dynamic program over the whole (lhs + 1) × (rhs + 1) matrix.
    private fun referenceLevenshtein(lhs: String, rhs: String): Int {
        val d = Array(lhs.length + 1) { IntArray(rhs.length + 1) }
        for (i in 0..lhs.length) d[i][0] = i
        for (j in 0..rhs.length) d[0][j] = j
        for (i in 1..lhs.length) for (j in 1..rhs.length) {
            val substitution = if (lhs[i - 1] == rhs[j - 1]) 0 else 1
            d[i][j] = min(min(d[i - 1][j] + 1, d[i][j - 1] + 1), d[i - 1][j - 1] + substitution)
        }
        return d[lhs.length][rhs.length]
    }
}
