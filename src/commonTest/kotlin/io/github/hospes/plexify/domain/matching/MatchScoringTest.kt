package io.github.hospes.plexify.domain.matching

import io.github.hospes.plexify.core.DefaultFileOrganizer
import io.github.hospes.plexify.core.MediaProcessor
import io.github.hospes.plexify.core.yearScore
import io.github.hospes.plexify.data.MetadataCache
import io.github.hospes.plexify.data.calculateTitleConfidence
import io.github.hospes.plexify.domain.model.MediaSearchResult
import io.github.hospes.plexify.domain.service.MetadataService
import io.github.hospes.plexify.domain.service.PathFormatter
import io.github.hospes.plexify.domain.strategy.NamingStrategy
import io.github.hospes.plexify.logging.LoggingContext
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Pins today's [MatchScoring]: the year score and whole match scores. A row failing after a weight
 * or threshold changed means the change alters which files match; update the table only when that
 * is intended.
 */
class MatchScoringTest {

    private val processor = MediaProcessor(
        metadataService = MetadataService(emptyList(), NamingStrategy.Jellyfin),
        fileOrganizer = DefaultFileOrganizer(PathFormatter(), NamingStrategy.Jellyfin),
        cache = MetadataCache(),
    )

    private class YearRow(val parsedYear: Int, val candidateYear: Int, val isShow: Boolean, val expected: Double)

    @Test
    fun `year score table`() {
        val table = listOf(
            YearRow(2022, 2022, isShow = false, expected = 10.0),
            YearRow(2022, 2021, isShow = false, expected = 5.0),
            YearRow(2022, 2023, isShow = false, expected = 5.0),
            YearRow(2022, 2020, isShow = false, expected = -10.0),
            YearRow(2022, 2024, isShow = false, expected = -10.0),
            YearRow(2022, 2022, isShow = true, expected = 10.0),
            YearRow(2022, 2021, isShow = true, expected = 5.0),
            YearRow(2022, 2023, isShow = true, expected = 5.0),
            // A show that premiered earlier: 5, less 0.1 per year beyond the first, at least 1.
            YearRow(2022, 2020, isShow = true, expected = 4.9),
            YearRow(2022, 2012, isShow = true, expected = 4.1),
            YearRow(2022, 1982, isShow = true, expected = 1.1),
            YearRow(2022, 1981, isShow = true, expected = 1.0),
            YearRow(2022, 1950, isShow = true, expected = 1.0),
            YearRow(2022, 2024, isShow = true, expected = -10.0),
        )

        val wrong = table.mapNotNull { row ->
            val actual = yearScore(row.parsedYear, row.candidateYear, row.isShow)
            if (abs(actual - row.expected) < TOLERANCE) null
            else "${if (row.isShow) "show" else "movie"} ${row.candidateYear} for ${row.parsedYear}: expected ${row.expected}, got $actual"
        }
        assertTrue(wrong.isEmpty(), wrong.joinToString("\n"))
    }

    /** [expected] is the best match's score, or null for no match. */
    private class ScoreRow(
        val name: String,
        val results: List<MediaSearchResult>,
        val parsedTitle: String,
        val parsedYear: String?,
        val expected: Double?,
    )

    @Test
    fun `match score table`() = with(LoggingContext()) {
        val spidermanConfidence = calculateTitleConfidence("Spiderman", "Spider-Man")
        val table = listOf(
            // 10·1.0 + 10 (exact year) + 0 (one provider) + 100/20
            ScoreRow(
                "exact title and year",
                listOf(MediaSearchResult.Movie("Inception", "2010", tmdbId = "27205", provider = "TMDb", matchConfidence = 100.0)),
                "Inception", "2010", expected = 25.0,
            ),
            // 10·1.0 (via the alias) + 4.9 (show premiered two years before) + 0 + 100/20: once the aliases
            // are looked up, the provider scores its confidence over them too.
            ScoreRow(
                "show matched by alias, premiered earlier",
                listOf(
                    MediaSearchResult.TvShow(
                        "The Kingdoms of Ruin", "2023", tmdbId = "219673", provider = "TMDb", matchConfidence = 100.0,
                        alternativeTitles = listOf("Hametsu no Oukoku"),
                    )
                ),
                "Hametsu no Oukoku", "2025", expected = 19.9,
            ),
            // 10·1.0 + 0 (no year) + 2·(2 − 1) + 90/20. The title counts twice, normalized two ways:
            // "Spiderman" against "Spider-Man" is similarity 1.0 (letters and digits only) but confidence
            // 90 (lowercased only).
            ScoreRow(
                "two providers agree, no year",
                listOf(
                    MediaSearchResult.Movie("Spider-Man", "2002", tmdbId = "557", provider = "TMDb", matchConfidence = spidermanConfidence),
                    MediaSearchResult.Movie("Spider-Man", "2002", tmdbId = "557", provider = "Other", matchConfidence = spidermanConfidence),
                ),
                "Spiderman", null, expected = 16.5,
            ),
            // Similarity 1 − 3/5 = 0.4, the cut: 4.0 + 20/20 is exactly the minimum.
            ScoreRow(
                "at the similarity cut and the minimum",
                listOf(MediaSearchResult.Movie("Abxyz", null, tmdbId = "1", provider = "TMDb", matchConfidence = 20.0)),
                "Abcde", null, expected = 5.0,
            ),
            // 4.0 + 10/20 = 4.5: the one case where the minimum, not the similarity cut, rejects.
            ScoreRow(
                "past the similarity cut, under the minimum",
                listOf(MediaSearchResult.Movie("Abxyz", null, tmdbId = "1", provider = "TMDb", matchConfidence = 10.0)),
                "Abcde", null, expected = null,
            ),
            // Similarity 1 − 5/8 = 0.375 is discarded, though it would score 3.75 + 5.
            ScoreRow(
                "under the similarity cut",
                listOf(MediaSearchResult.Movie("Abcxxxxx", null, tmdbId = "1", provider = "TMDb", matchConfidence = 100.0)),
                "Abcdefgh", null, expected = null,
            ),
        )

        val wrong = table.mapNotNull { row ->
            val actual = processor.rankMatches(row.results, row.parsedTitle, row.parsedYear).firstOrNull()?.score
            val matches = if (row.expected == null || actual == null) actual == row.expected else abs(actual - row.expected) < TOLERANCE
            if (matches) null else "${row.name}: expected ${row.expected ?: "no match"}, got ${actual ?: "no match"}"
        }
        assertTrue(wrong.isEmpty(), wrong.joinToString("\n"))
    }

    private companion object {
        const val TOLERANCE = 1e-9
    }
}
