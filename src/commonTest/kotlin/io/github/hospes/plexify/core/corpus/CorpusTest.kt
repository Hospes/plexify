package io.github.hospes.plexify.core.corpus

import io.github.hospes.plexify.core.DefaultFileOrganizer
import io.github.hospes.plexify.core.OrganizeOutcome
import io.github.hospes.plexify.core.ProcessorRun
import io.github.hospes.plexify.core.RecordingOrganizer
import io.github.hospes.plexify.core.runProcessor
import io.github.hospes.plexify.data.tmdb.TmdbResponder
import io.github.hospes.plexify.data.tmdb.capped
import io.github.hospes.plexify.data.tmdb.mockProvider
import io.github.hospes.plexify.data.tmdb.tokenCredentials
import io.github.hospes.plexify.domain.service.PathFormatter
import io.github.hospes.plexify.domain.strategy.NamingStrategy
import kotlin.test.Test
import kotlin.test.fail

/**
 * Real release names through the real parser, processor, TmdbProvider and organizer (in test mode), against the
 * hand-written TMDB of [corpusTmdb]: every file's library path for both templates, the run's counts, and the TMDB
 * requests it made by endpoint. It pins what plexify does today, right or wrong, so a refactor that changes which media
 * a file matches, its name, or what plexify asks TMDB shows up as a diff. A request the fixture can't answer fails the
 * test (see CorpusTmdb.kt). Offline: TMDB is a MockEngine.
 *
 * To update after an intended change, run the test: the failure prints the whole block as it is now, ready to paste
 * over the expected one. Check the diff before committing it.
 */
class CorpusTest {

    @Test
    fun `anime releases`() = corpus(animeNames, """
        |Devil May Cry - AniLibria [WEBRip 1080p HEVC]/Devil_May_Cry_[01]_[AniLibria]_[WEBRip_1080p_HEVC].mkv
        |  Jellyfin: Devil May Cry (2025) [tmdbid-235930]/Season 01/Devil May Cry (2025) - S01E01 - Episode 1 - [1080p] [WEBRip] [AniLibria].mkv
        |  Plex:     Devil May Cry (2025) {imdb-tt15716776}/Season 01/Devil May Cry (2025) - S01E01 - Episode 1 - [1080p] [WEBRip] [AniLibria].mkv
        |Devil May Cry - AniLibria [WEBRip 1080p HEVC]/Devil_May_Cry_[02]_[AniLibria]_[WEBRip_1080p_HEVC].mkv
        |  Jellyfin: Devil May Cry (2025) [tmdbid-235930]/Season 01/Devil May Cry (2025) - S01E02 - Episode 2 - [1080p] [WEBRip] [AniLibria].mkv
        |  Plex:     Devil May Cry (2025) {imdb-tt15716776}/Season 01/Devil May Cry (2025) - S01E02 - Episode 2 - [1080p] [WEBRip] [AniLibria].mkv
        |Devil May Cry - AniLibria [WEBRip 1080p HEVC]/Devil_May_Cry_[08]_[AniLibria]_[WEBRip_1080p_HEVC].mkv
        |  Jellyfin: Devil May Cry (2025) [tmdbid-235930]/Season 01/Devil May Cry (2025) - S01E08 - Episode 8 - [1080p] [WEBRip] [AniLibria].mkv
        |  Plex:     Devil May Cry (2025) {imdb-tt15716776}/Season 01/Devil May Cry (2025) - S01E08 - Episode 8 - [1080p] [WEBRip] [AniLibria].mkv
        |Fullmetal Alchemist Brotherhood - AniLibria.TV [BDRip 1080p HEVC]/Fullmetal_Alchemist_Brotherhood_[01]_[AniLibria_TV]_[BDRip_1080p_HEVC].mkv
        |  Jellyfin: Fullmetal Alchemist Brotherhood (2009) [tmdbid-31911]/Season 01/Fullmetal Alchemist Brotherhood (2009) - S01E01 - Episode 1 - [1080p] [BDRip] [AniLibria].mkv
        |  Plex:     Fullmetal Alchemist Brotherhood (2009) {imdb-tt1355642}/Season 01/Fullmetal Alchemist Brotherhood (2009) - S01E01 - Episode 1 - [1080p] [BDRip] [AniLibria].mkv
        |Fullmetal Alchemist Brotherhood - AniLibria.TV [BDRip 1080p HEVC]/Fullmetal_Alchemist_Brotherhood_[02]_[AniLibria_TV]_[BDRip_1080p_HEVC].mkv
        |  Jellyfin: Fullmetal Alchemist Brotherhood (2009) [tmdbid-31911]/Season 01/Fullmetal Alchemist Brotherhood (2009) - S01E02 - Episode 2 - [1080p] [BDRip] [AniLibria].mkv
        |  Plex:     Fullmetal Alchemist Brotherhood (2009) {imdb-tt1355642}/Season 01/Fullmetal Alchemist Brotherhood (2009) - S01E02 - Episode 2 - [1080p] [BDRip] [AniLibria].mkv
        |Fullmetal Alchemist Brotherhood - AniLibria.TV [BDRip 1080p HEVC]/Fullmetal_Alchemist_Brotherhood_[63]_[AniLibria_TV]_[BDRip_1080p_HEVC].mkv
        |  Jellyfin: Fullmetal Alchemist Brotherhood (2009) [tmdbid-31911]/Season 01/Fullmetal Alchemist Brotherhood (2009) - S01E63 - Episode 63 - [1080p] [BDRip] [AniLibria].mkv
        |  Plex:     Fullmetal Alchemist Brotherhood (2009) {imdb-tt1355642}/Season 01/Fullmetal Alchemist Brotherhood (2009) - S01E63 - Episode 63 - [1080p] [BDRip] [AniLibria].mkv
        |Fullmetal Alchemist Brotherhood - AniLibria.TV [BDRip 1080p HEVC]/Fullmetal_Alchemist_Brotherhood_[64]_[AniLibria_TV]_[BDRip_1080p_HEVC].mkv
        |  Jellyfin: Fullmetal Alchemist Brotherhood (2009) [tmdbid-31911]/Season 01/Fullmetal Alchemist Brotherhood (2009) - S01E64 - Episode 64 - [1080p] [BDRip] [AniLibria].mkv
        |  Plex:     Fullmetal Alchemist Brotherhood (2009) {imdb-tt1355642}/Season 01/Fullmetal Alchemist Brotherhood (2009) - S01E64 - Episode 64 - [1080p] [BDRip] [AniLibria].mkv
        |Gate S2/Gate_[01]_[720p].mkv
        |  Jellyfin: Gate (2015) [tmdbid-63663]/Season 01/Gate (2015) - S01E13 - Episode 13 - [720p].mkv
        |  Plex:     Gate (2015) {imdb-tt4958580}/Season 01/Gate (2015) - S01E13 - Episode 13 - [720p].mkv
        |Gate S2/Gate_[02]_[720p].mkv
        |  Jellyfin: Gate (2015) [tmdbid-63663]/Season 01/Gate (2015) - S01E14 - Episode 14 - [720p].mkv
        |  Plex:     Gate (2015) {imdb-tt4958580}/Season 01/Gate (2015) - S01E14 - Episode 14 - [720p].mkv
        |Gate S2/Gate_[12]_[720p].mkv
        |  Jellyfin: Gate (2015) [tmdbid-63663]/Season 01/Gate (2015) - S01E24 - Episode 24 - [720p].mkv
        |  Plex:     Gate (2015) {imdb-tt4958580}/Season 01/Gate (2015) - S01E24 - Episode 24 - [720p].mkv
        |Kashita Maryoku wa Revo Barai de Kyousei Choushuu - AniLiberty [WEB-DL 1080p HEVC]/Kashita_Maryoku_wa_Revo_Barai_de_Kyousei_Choushuu_[01]_[HEVC].mkv
        |  Jellyfin: Magic Repo Man Dumped by My Party, I'll Cash In with a Cute Support Fairy to Become the Strongest! (2026) [tmdbid-333622]/Season 01/Magic Repo Man Dumped by My Party, I'll Cash In with a Cute Support Fairy to Become the Strongest! (2026) - S01E01 - Episode 1 - [AniLiberty].mkv
        |  Plex:     Magic Repo Man Dumped by My Party, I'll Cash In with a Cute Support Fairy to Become the Strongest! (2026) {imdb-tt43650419}/Season 01/Magic Repo Man Dumped by My Party, I'll Cash In with a Cute Support Fairy to Become the Strongest! (2026) - S01E01 - Episode 1 - [AniLiberty].mkv
        |Kashita Maryoku wa Revo Barai de Kyousei Choushuu - AniLiberty [WEB-DL 1080p HEVC]/Kashita_Maryoku_wa_Revo_Barai_de_Kyousei_Choushuu_[02]_[HEVC].mkv
        |  Jellyfin: Magic Repo Man Dumped by My Party, I'll Cash In with a Cute Support Fairy to Become the Strongest! (2026) [tmdbid-333622]/Season 01/Magic Repo Man Dumped by My Party, I'll Cash In with a Cute Support Fairy to Become the Strongest! (2026) - S01E02 - Episode 2 - [AniLiberty].mkv
        |  Plex:     Magic Repo Man Dumped by My Party, I'll Cash In with a Cute Support Fairy to Become the Strongest! (2026) {imdb-tt43650419}/Season 01/Magic Repo Man Dumped by My Party, I'll Cash In with a Cute Support Fairy to Become the Strongest! (2026) - S01E02 - Episode 2 - [AniLiberty].mkv
        |Kashita Maryoku wa Revo Barai de Kyousei Choushuu - AniLiberty [WEB-DL 1080p HEVC]/Kashita_Maryoku_wa_Revo_Barai_de_Kyousei_Choushuu_[03]_[HEVC].mkv
        |  Jellyfin: Magic Repo Man Dumped by My Party, I'll Cash In with a Cute Support Fairy to Become the Strongest! (2026) [tmdbid-333622]/Season 01/Magic Repo Man Dumped by My Party, I'll Cash In with a Cute Support Fairy to Become the Strongest! (2026) - S01E03 - Episode 3 - [AniLiberty].mkv
        |  Plex:     Magic Repo Man Dumped by My Party, I'll Cash In with a Cute Support Fairy to Become the Strongest! (2026) {imdb-tt43650419}/Season 01/Magic Repo Man Dumped by My Party, I'll Cash In with a Cute Support Fairy to Become the Strongest! (2026) - S01E03 - Episode 3 - [AniLiberty].mkv
        |Mamonogurai no Boukensha - AniLiberty [WEB-DL 1080p HEVC]/Mamonogurai_no_Boukensha_Ore_dake_Mamono_wo_Kuratte_Tsuyoku_Naru_[01]_[HEVC].mkv
        |  Jellyfin: Monster Eater (2026) [tmdbid-310921]/Season 01/Monster Eater (2026) - S01E01 - Episode 1 - [AniLiberty].mkv
        |  Plex:     Monster Eater (2026) {imdb-tt39673631}/Season 01/Monster Eater (2026) - S01E01 - Episode 1 - [AniLiberty].mkv
        |Mamonogurai no Boukensha - AniLiberty [WEB-DL 1080p HEVC]/Mamonogurai_no_Boukensha_Ore_dake_Mamono_wo_Kuratte_Tsuyoku_Naru_[02]_[HEVC].mkv
        |  Jellyfin: Monster Eater (2026) [tmdbid-310921]/Season 01/Monster Eater (2026) - S01E02 - Episode 2 - [AniLiberty].mkv
        |  Plex:     Monster Eater (2026) {imdb-tt39673631}/Season 01/Monster Eater (2026) - S01E02 - Episode 2 - [AniLiberty].mkv
        |Mamonogurai no Boukensha - AniLiberty [WEB-DL 1080p HEVC]/Mamonogurai_no_Boukensha_Ore_dake_Mamono_wo_Kuratte_Tsuyoku_Naru_[12]_[HEVC].mkv
        |  Jellyfin: Monster Eater (2026) [tmdbid-310921]/Season 01/Monster Eater (2026) - S01E12 - Episode 12 - [AniLiberty].mkv
        |  Plex:     Monster Eater (2026) {imdb-tt39673631}/Season 01/Monster Eater (2026) - S01E12 - Episode 12 - [AniLiberty].mkv
        |Nozomanu Fushi no Boukensha - AniLibria.TV [WEBRip 1080p HEVC]/Nozomanu_Fushi_no_Boukensha_[01]_[AniLibria_TV]_[WEBRip_1080p_HEVC].mkv
        |  Jellyfin: The Unwanted Undead Adventurer (2024) [tmdbid-197848]/Season 01/The Unwanted Undead Adventurer (2024) - S01E01 - Episode 1 - [1080p] [WEBRip] [AniLibria].mkv
        |  Plex:     The Unwanted Undead Adventurer (2024) {imdb-tt30177477}/Season 01/The Unwanted Undead Adventurer (2024) - S01E01 - Episode 1 - [1080p] [WEBRip] [AniLibria].mkv
        |Nozomanu Fushi no Boukensha - AniLibria.TV [WEBRip 1080p HEVC]/Nozomanu_Fushi_no_Boukensha_[02]_[AniLibria_TV]_[WEBRip_1080p_HEVC].mkv
        |  Jellyfin: The Unwanted Undead Adventurer (2024) [tmdbid-197848]/Season 01/The Unwanted Undead Adventurer (2024) - S01E02 - Episode 2 - [1080p] [WEBRip] [AniLibria].mkv
        |  Plex:     The Unwanted Undead Adventurer (2024) {imdb-tt30177477}/Season 01/The Unwanted Undead Adventurer (2024) - S01E02 - Episode 2 - [1080p] [WEBRip] [AniLibria].mkv
        |Nozomanu Fushi no Boukensha - AniLibria.TV [WEBRip 1080p HEVC]/Nozomanu_Fushi_no_Boukensha_[12]_[AniLibria_TV]_[WEBRip_1080p_HEVC].mkv
        |  Jellyfin: The Unwanted Undead Adventurer (2024) [tmdbid-197848]/Season 01/The Unwanted Undead Adventurer (2024) - S01E12 - Episode 12 - [1080p] [WEBRip] [AniLibria].mkv
        |  Plex:     The Unwanted Undead Adventurer (2024) {imdb-tt30177477}/Season 01/The Unwanted Undead Adventurer (2024) - S01E12 - Episode 12 - [1080p] [WEBRip] [AniLibria].mkv
        |Record of Ragnarok 2 - AniLibria.TV [WEBRip 1080p]/Record_of_Ragnarok_2_[01]_[AniLibria_TV]_[WEBRip_1080p].mkv
        |  Jellyfin: Record of Ragnarok (2021) [tmdbid-114868]/Season 02/Record of Ragnarok (2021) - S02E01 - Episode 1 - [1080p] [WEBRip] [AniLibria].mkv
        |  Plex:     Record of Ragnarok (2021) {imdb-tt13676344}/Season 02/Record of Ragnarok (2021) - S02E01 - Episode 1 - [1080p] [WEBRip] [AniLibria].mkv
        |Record of Ragnarok 2 - AniLibria.TV [WEBRip 1080p]/Record_of_Ragnarok_2_[02]_[AniLibria_TV]_[WEBRip_1080p].mkv
        |  Jellyfin: Record of Ragnarok (2021) [tmdbid-114868]/Season 02/Record of Ragnarok (2021) - S02E02 - Episode 2 - [1080p] [WEBRip] [AniLibria].mkv
        |  Plex:     Record of Ragnarok (2021) {imdb-tt13676344}/Season 02/Record of Ragnarok (2021) - S02E02 - Episode 2 - [1080p] [WEBRip] [AniLibria].mkv
        |Record of Ragnarok 2 - AniLibria.TV [WEBRip 1080p]/Record_of_Ragnarok_2_[06]_[AniLibria_TV]_[WEBRip_1080p].mkv
        |  Jellyfin: Record of Ragnarok (2021) [tmdbid-114868]/Season 02/Record of Ragnarok (2021) - S02E06 - Episode 6 - [1080p] [WEBRip] [AniLibria].mkv
        |  Plex:     Record of Ragnarok (2021) {imdb-tt13676344}/Season 02/Record of Ragnarok (2021) - S02E06 - Episode 6 - [1080p] [WEBRip] [AniLibria].mkv
        |Record of Ragnarok - AniLibria.TV [WEBRip 1080p HEVC]/Record_of_Ragnarok_[01]_[AniLibria_TV]_[WEBRip_1080p_HEVC].mkv
        |  Jellyfin: Record of Ragnarok (2021) [tmdbid-114868]/Season 01/Record of Ragnarok (2021) - S01E01 - Episode 1 - [1080p] [WEBRip] [AniLibria].mkv
        |  Plex:     Record of Ragnarok (2021) {imdb-tt13676344}/Season 01/Record of Ragnarok (2021) - S01E01 - Episode 1 - [1080p] [WEBRip] [AniLibria].mkv
        |Record of Ragnarok - AniLibria.TV [WEBRip 1080p HEVC]/Record_of_Ragnarok_[02]_[AniLibria_TV]_[WEBRip_1080p_HEVC].mkv
        |  Jellyfin: Record of Ragnarok (2021) [tmdbid-114868]/Season 01/Record of Ragnarok (2021) - S01E02 - Episode 2 - [1080p] [WEBRip] [AniLibria].mkv
        |  Plex:     Record of Ragnarok (2021) {imdb-tt13676344}/Season 01/Record of Ragnarok (2021) - S01E02 - Episode 2 - [1080p] [WEBRip] [AniLibria].mkv
        |Record of Ragnarok - AniLibria.TV [WEBRip 1080p HEVC]/Record_of_Ragnarok_[11]_[AniLibria_TV]_[WEBRip_1080p_HEVC].mkv
        |  Jellyfin: Record of Ragnarok (2021) [tmdbid-114868]/Season 01/Record of Ragnarok (2021) - S01E11 - Episode 11 - [1080p] [WEBRip] [AniLibria].mkv
        |  Plex:     Record of Ragnarok (2021) {imdb-tt13676344}/Season 01/Record of Ragnarok (2021) - S01E11 - Episode 11 - [1080p] [WEBRip] [AniLibria].mkv
        |Record of Ragnarok - AniLibria.TV [WEBRip 1080p HEVC]/Record_of_Ragnarok_[12]_[AniLibria_TV]_[WEBRip_1080p_HEVC]_[END].mkv
        |  Jellyfin: Record of Ragnarok (2021) [tmdbid-114868]/Season 01/Record of Ragnarok (2021) - S01E12 - Episode 12 - [1080p] [WEBRip] [AniLibria].mkv
        |  Plex:     Record of Ragnarok (2021) {imdb-tt13676344}/Season 01/Record of Ragnarok (2021) - S01E12 - Episode 12 - [1080p] [WEBRip] [AniLibria].mkv
        |Shuumatsu no Walkure III - AniLiberty [WEB-DL 1080p HEVC]/Shuumatsu_no_Walkure_III_[01]_[HEVC].mkv
        |  Jellyfin: Record of Ragnarok (2021) [tmdbid-114868]/Season 03/Record of Ragnarok (2021) - S03E01 - Episode 1 - [AniLiberty].mkv
        |  Plex:     Record of Ragnarok (2021) {imdb-tt13676344}/Season 03/Record of Ragnarok (2021) - S03E01 - Episode 1 - [AniLiberty].mkv
        |Shuumatsu no Walkure III - AniLiberty [WEB-DL 1080p HEVC]/Shuumatsu_no_Walkure_III_[02]_[HEVC].mkv
        |  Jellyfin: Record of Ragnarok (2021) [tmdbid-114868]/Season 03/Record of Ragnarok (2021) - S03E02 - Episode 2 - [AniLiberty].mkv
        |  Plex:     Record of Ragnarok (2021) {imdb-tt13676344}/Season 03/Record of Ragnarok (2021) - S03E02 - Episode 2 - [AniLiberty].mkv
        |Shuumatsu no Walkure III - AniLiberty [WEB-DL 1080p HEVC]/Shuumatsu_no_Walkure_III_[12]_[HEVC].mkv
        |  Jellyfin: Record of Ragnarok (2021) [tmdbid-114868]/Season 03/Record of Ragnarok (2021) - S03E12 - Episode 12 - [AniLiberty].mkv
        |  Plex:     Record of Ragnarok (2021) {imdb-tt13676344}/Season 03/Record of Ragnarok (2021) - S03E12 - Episode 12 - [AniLiberty].mkv
        |Star Wars Visions Presents - The Ninth Jedi - AniLiberty [WEB-DL 1080p HEVC]/Star_Wars_Visions_Presents_-_The_Ninth_Jedi_[01]_[HEVC].mkv
        |  Jellyfin: Star Wars Visions Presents - The Ninth Jedi (2026) [tmdbid-289324]/Season 01/Star Wars Visions Presents - The Ninth Jedi (2026) - S01E01 - Episode 1 - [AniLiberty].mkv
        |  Plex:     Star Wars Visions Presents - The Ninth Jedi (2026) {imdb-tt43337681}/Season 01/Star Wars Visions Presents - The Ninth Jedi (2026) - S01E01 - Episode 1 - [AniLiberty].mkv
        |Star Wars Visions Presents - The Ninth Jedi - AniLiberty [WEB-DL 1080p HEVC]/Star_Wars_Visions_Presents_-_The_Ninth_Jedi_[02]_[HEVC].mkv
        |  Jellyfin: Star Wars Visions Presents - The Ninth Jedi (2026) [tmdbid-289324]/Season 01/Star Wars Visions Presents - The Ninth Jedi (2026) - S01E02 - Episode 2 - [AniLiberty].mkv
        |  Plex:     Star Wars Visions Presents - The Ninth Jedi (2026) {imdb-tt43337681}/Season 01/Star Wars Visions Presents - The Ninth Jedi (2026) - S01E02 - Episode 2 - [AniLiberty].mkv
        |Star Wars Visions Presents - The Ninth Jedi - AniLiberty [WEB-DL 1080p HEVC]/Star_Wars_Visions_Presents_-_The_Ninth_Jedi_[08]_[HEVC].mkv
        |  Jellyfin: Star Wars Visions Presents - The Ninth Jedi (2026) [tmdbid-289324]/Season 01/Star Wars Visions Presents - The Ninth Jedi (2026) - S01E08 - Episode 8 - [AniLiberty].mkv
        |  Plex:     Star Wars Visions Presents - The Ninth Jedi (2026) {imdb-tt43337681}/Season 01/Star Wars Visions Presents - The Ninth Jedi (2026) - S01E08 - Episode 8 - [AniLiberty].mkv
        |Tsue to Tsurugi no Wistoria Season 2 - AniLiberty [WEB-DL 1080p HEVC]/Tsue_to_Tsurugi_no_Wistoria_Season_2_[00]_[HEVC].mkv
        |  Jellyfin: (not organized)
        |  Plex:     (not organized)
        |Tsue to Tsurugi no Wistoria Season 2 - AniLiberty [WEB-DL 1080p HEVC]/Tsue_to_Tsurugi_no_Wistoria_Season_2_[01]_[HEVC].mkv
        |  Jellyfin: Wistoria Wand and Sword (2024) [tmdbid-245842]/Season 02/Wistoria Wand and Sword (2024) - S02E01 - Episode 1 - [AniLiberty].mkv
        |  Plex:     Wistoria Wand and Sword (2024) {imdb-tt31889371}/Season 02/Wistoria Wand and Sword (2024) - S02E01 - Episode 1 - [AniLiberty].mkv
        |Tsue to Tsurugi no Wistoria Season 2 - AniLiberty [WEB-DL 1080p HEVC]/Tsue_to_Tsurugi_no_Wistoria_Season_2_[02]_[HEVC].mkv
        |  Jellyfin: Wistoria Wand and Sword (2024) [tmdbid-245842]/Season 02/Wistoria Wand and Sword (2024) - S02E02 - Episode 2 - [AniLiberty].mkv
        |  Plex:     Wistoria Wand and Sword (2024) {imdb-tt31889371}/Season 02/Wistoria Wand and Sword (2024) - S02E02 - Episode 2 - [AniLiberty].mkv
        |Tsue to Tsurugi no Wistoria Season 2 - AniLiberty [WEB-DL 1080p HEVC]/Tsue_to_Tsurugi_no_Wistoria_Season_2_[12]_[HEVC].mkv
        |  Jellyfin: Wistoria Wand and Sword (2024) [tmdbid-245842]/Season 02/Wistoria Wand and Sword (2024) - S02E12 - Episode 12 - [AniLiberty].mkv
        |  Plex:     Wistoria Wand and Sword (2024) {imdb-tt31889371}/Season 02/Wistoria Wand and Sword (2024) - S02E12 - Episode 12 - [AniLiberty].mkv
        |Kimetsu no Yaiba - AniLibria.TV [HDTVRip 1080p HEVC]/Kimetsu_no_Yaiba_[01]_[AniLibria.TV]_[HDTVRip_1080p_HEVC].mkv
        |  Jellyfin: Demon Slayer Kimetsu no Yaiba (2019) [tmdbid-85937]/Season 01/Demon Slayer Kimetsu no Yaiba (2019) - S01E01 - Episode 1 - [1080p] [AniLibria].mkv
        |  Plex:     Demon Slayer Kimetsu no Yaiba (2019) {imdb-tt9335498}/Season 01/Demon Slayer Kimetsu no Yaiba (2019) - S01E01 - Episode 1 - [1080p] [AniLibria].mkv
        |Kimetsu no Yaiba - AniLibria.TV [HDTVRip 1080p HEVC]/Kimetsu_no_Yaiba_[02]_[AniLibria.TV]_[HDTVRip_1080p_HEVC].mkv
        |  Jellyfin: Demon Slayer Kimetsu no Yaiba (2019) [tmdbid-85937]/Season 01/Demon Slayer Kimetsu no Yaiba (2019) - S01E02 - Episode 2 - [1080p] [AniLibria].mkv
        |  Plex:     Demon Slayer Kimetsu no Yaiba (2019) {imdb-tt9335498}/Season 01/Demon Slayer Kimetsu no Yaiba (2019) - S01E02 - Episode 2 - [1080p] [AniLibria].mkv
        |Kimetsu no Yaiba - AniLibria.TV [HDTVRip 1080p HEVC]/Kimetsu_no_Yaiba_[26]_[AniLibria.TV]_[HDTVRip_1080p_HEVC].mkv
        |  Jellyfin: Demon Slayer Kimetsu no Yaiba (2019) [tmdbid-85937]/Season 01/Demon Slayer Kimetsu no Yaiba (2019) - S01E26 - Episode 26 - [1080p] [AniLibria].mkv
        |  Plex:     Demon Slayer Kimetsu no Yaiba (2019) {imdb-tt9335498}/Season 01/Demon Slayer Kimetsu no Yaiba (2019) - S01E26 - Episode 26 - [1080p] [AniLibria].mkv
        |Kimetsu no Yaiba Mugen Ressha-hen (TV) - AniLibria.TV [WEBRip 1080p HEVC]/Kimetsu_no_Yaiba_Mugen_Ressha-hen_(TV)_[01]_[AniLibria_TV]_[WEBRip_1080p_HEVC].mkv
        |  Jellyfin: Demon Slayer Kimetsu no Yaiba (2019) [tmdbid-85937]/Season 02/Demon Slayer Kimetsu no Yaiba (2019) - S02E01 - Episode 1 - [1080p] [WEBRip] [AniLibria].mkv
        |  Plex:     Demon Slayer Kimetsu no Yaiba (2019) {imdb-tt9335498}/Season 02/Demon Slayer Kimetsu no Yaiba (2019) - S02E01 - Episode 1 - [1080p] [WEBRip] [AniLibria].mkv
        |Kimetsu no Yaiba Mugen Ressha-hen (TV) - AniLibria.TV [WEBRip 1080p HEVC]/Kimetsu_no_Yaiba_Mugen_Ressha-hen_(TV)_[02]_[AniLibria_TV]_[WEBRip_1080p_HEVC].mkv
        |  Jellyfin: Demon Slayer Kimetsu no Yaiba (2019) [tmdbid-85937]/Season 02/Demon Slayer Kimetsu no Yaiba (2019) - S02E02 - Episode 2 - [1080p] [WEBRip] [AniLibria].mkv
        |  Plex:     Demon Slayer Kimetsu no Yaiba (2019) {imdb-tt9335498}/Season 02/Demon Slayer Kimetsu no Yaiba (2019) - S02E02 - Episode 2 - [1080p] [WEBRip] [AniLibria].mkv
        |Kimetsu no Yaiba Mugen Ressha-hen (TV) - AniLibria.TV [WEBRip 1080p HEVC]/Kimetsu_no_Yaiba_Mugen_Ressha-hen_(TV)_[07]_[AniLibria_TV]_[WEBRip_1080p_HEVC].mkv
        |  Jellyfin: Demon Slayer Kimetsu no Yaiba (2019) [tmdbid-85937]/Season 02/Demon Slayer Kimetsu no Yaiba (2019) - S02E07 - Episode 7 - [1080p] [WEBRip] [AniLibria].mkv
        |  Plex:     Demon Slayer Kimetsu no Yaiba (2019) {imdb-tt9335498}/Season 02/Demon Slayer Kimetsu no Yaiba (2019) - S02E07 - Episode 7 - [1080p] [WEBRip] [AniLibria].mkv
        |Kimetsu no Yaiba - Yuukaku-hen - AniLibria.TV [WEBRip 1080p HEVC]/Kimetsu_no_Yaiba_-_Yuukaku-hen_[01]_[AniLibria_TV]_[WEBRip_1080p_HEVC].mkv
        |  Jellyfin: Demon Slayer Kimetsu no Yaiba (2019) [tmdbid-85937]/Season 03/Demon Slayer Kimetsu no Yaiba (2019) - S03E01 - Episode 1 - [1080p] [WEBRip] [AniLibria].mkv
        |  Plex:     Demon Slayer Kimetsu no Yaiba (2019) {imdb-tt9335498}/Season 03/Demon Slayer Kimetsu no Yaiba (2019) - S03E01 - Episode 1 - [1080p] [WEBRip] [AniLibria].mkv
        |Kimetsu no Yaiba - Yuukaku-hen - AniLibria.TV [WEBRip 1080p HEVC]/Kimetsu_no_Yaiba_-_Yuukaku-hen_[02]_[AniLibria_TV]_[WEBRip_1080p_HEVC].mkv
        |  Jellyfin: Demon Slayer Kimetsu no Yaiba (2019) [tmdbid-85937]/Season 03/Demon Slayer Kimetsu no Yaiba (2019) - S03E02 - Episode 2 - [1080p] [WEBRip] [AniLibria].mkv
        |  Plex:     Demon Slayer Kimetsu no Yaiba (2019) {imdb-tt9335498}/Season 03/Demon Slayer Kimetsu no Yaiba (2019) - S03E02 - Episode 2 - [1080p] [WEBRip] [AniLibria].mkv
        |Kimetsu no Yaiba - Yuukaku-hen - AniLibria.TV [WEBRip 1080p HEVC]/Kimetsu_no_Yaiba_-_Yuukaku-hen_[10]_[AniLibria_TV]_[WEBRip_1080p_HEVC].mkv
        |  Jellyfin: Demon Slayer Kimetsu no Yaiba (2019) [tmdbid-85937]/Season 03/Demon Slayer Kimetsu no Yaiba (2019) - S03E10 - Episode 10 - [1080p] [WEBRip] [AniLibria].mkv
        |  Plex:     Demon Slayer Kimetsu no Yaiba (2019) {imdb-tt9335498}/Season 03/Demon Slayer Kimetsu no Yaiba (2019) - S03E10 - Episode 10 - [1080p] [WEBRip] [AniLibria].mkv
        |Kimetsu no Yaiba - Yuukaku-hen - AniLibria.TV [WEBRip 1080p HEVC]/Kimetsu_no_Yaiba_-_Yuukaku-hen_[11_END]_[AniLibria_TV]_[WEBRip_1080p_HEVC].mkv
        |  Jellyfin: Demon Slayer Kimetsu no Yaiba (2019) [tmdbid-85937]/Season 03/Demon Slayer Kimetsu no Yaiba (2019) - S03E11 - Episode 11 - [1080p] [WEBRip] [AniLibria].mkv
        |  Plex:     Demon Slayer Kimetsu no Yaiba (2019) {imdb-tt9335498}/Season 03/Demon Slayer Kimetsu no Yaiba (2019) - S03E11 - Episode 11 - [1080p] [WEBRip] [AniLibria].mkv
        |Kimetsu no Yaiba - Hashira Geiko-hen - AniLibria [WEBRip 1080p HEVC]/Kimetsu_no_Yaiba_-_Hashira_Geiko-hen_[01]_[AniLibria]_[WEBRip_1080p_HEVC].mkv
        |  Jellyfin: Demon Slayer Kimetsu no Yaiba (2019) [tmdbid-85937]/Season 05/Demon Slayer Kimetsu no Yaiba (2019) - S05E01 - Episode 1 - [1080p] [WEBRip] [AniLibria].mkv
        |  Plex:     Demon Slayer Kimetsu no Yaiba (2019) {imdb-tt9335498}/Season 05/Demon Slayer Kimetsu no Yaiba (2019) - S05E01 - Episode 1 - [1080p] [WEBRip] [AniLibria].mkv
        |Kimetsu no Yaiba - Hashira Geiko-hen - AniLibria [WEBRip 1080p HEVC]/Kimetsu_no_Yaiba_-_Hashira_Geiko-hen_[02]_[AniLibria]_[WEBRip_1080p_HEVC].mkv
        |  Jellyfin: Demon Slayer Kimetsu no Yaiba (2019) [tmdbid-85937]/Season 05/Demon Slayer Kimetsu no Yaiba (2019) - S05E02 - Episode 2 - [1080p] [WEBRip] [AniLibria].mkv
        |  Plex:     Demon Slayer Kimetsu no Yaiba (2019) {imdb-tt9335498}/Season 05/Demon Slayer Kimetsu no Yaiba (2019) - S05E02 - Episode 2 - [1080p] [WEBRip] [AniLibria].mkv
        |Kimetsu no Yaiba - Hashira Geiko-hen - AniLibria [WEBRip 1080p HEVC]/Kimetsu_no_Yaiba_-_Hashira_Geiko-hen_[08]_[AniLibria]_[WEBRip_1080p_HEVC].mkv
        |  Jellyfin: Demon Slayer Kimetsu no Yaiba (2019) [tmdbid-85937]/Season 05/Demon Slayer Kimetsu no Yaiba (2019) - S05E08 - Episode 8 - [1080p] [WEBRip] [AniLibria].mkv
        |  Plex:     Demon Slayer Kimetsu no Yaiba (2019) {imdb-tt9335498}/Season 05/Demon Slayer Kimetsu no Yaiba (2019) - S05E08 - Episode 8 - [1080p] [WEBRip] [AniLibria].mkv
        |Cyberpunk. Edgerunners 1 - LostFilm.TV [1080p]/Cyberpunk.Edgerunners.S01E01.1080p.rus.LostFilm.TV.mkv
        |  Jellyfin: Cyberpunk Edgerunners (2022) [tmdbid-105248]/Season 01/Cyberpunk Edgerunners (2022) - S01E01 - Episode 1 - [1080p] [LostFilm].mkv
        |  Plex:     Cyberpunk Edgerunners (2022) {imdb-tt12590266}/Season 01/Cyberpunk Edgerunners (2022) - S01E01 - Episode 1 - [1080p] [LostFilm].mkv
        |Cyberpunk. Edgerunners 1 - LostFilm.TV [1080p]/Cyberpunk.Edgerunners.S01E02.1080p.rus.LostFilm.TV.mkv
        |  Jellyfin: Cyberpunk Edgerunners (2022) [tmdbid-105248]/Season 01/Cyberpunk Edgerunners (2022) - S01E02 - Episode 2 - [1080p] [LostFilm].mkv
        |  Plex:     Cyberpunk Edgerunners (2022) {imdb-tt12590266}/Season 01/Cyberpunk Edgerunners (2022) - S01E02 - Episode 2 - [1080p] [LostFilm].mkv
        |Cyberpunk. Edgerunners 1 - LostFilm.TV [1080p]/Cyberpunk.Edgerunners.S01E10.1080p.rus.LostFilm.TV.mkv
        |  Jellyfin: Cyberpunk Edgerunners (2022) [tmdbid-105248]/Season 01/Cyberpunk Edgerunners (2022) - S01E10 - Episode 10 - [1080p] [LostFilm].mkv
        |  Plex:     Cyberpunk Edgerunners (2022) {imdb-tt12590266}/Season 01/Cyberpunk Edgerunners (2022) - S01E10 - Episode 10 - [1080p] [LostFilm].mkv
        |The Lord of the Rings The War of the Rohirrim_[AniLibria]_[WEBRip_1080p_HEVC].mkv
        |  Jellyfin: The Lord of the Rings The War of the Rohirrim (2024) [tmdbid-839033]/The Lord of the Rings The War of the Rohirrim (2024) [tmdbid-839033] - [1080p] [WEBRip] [AniLibria].mkv
        |  Plex:     The Lord of the Rings The War of the Rohirrim (2024) {imdb-tt14824600}/The Lord of the Rings The War of the Rohirrim (2024) - [1080p] [WEBRip] [AniLibria].mkv
        |Jellyfin: 52 organized, 1 skipped, 0 failed
        |Jellyfin requests: 3 movie/{id}/alternative_titles, 20 search/multi, 3 tv/episode_group/{id}, 13 tv/{id}/alternative_titles, 2 tv/{id}/episode_groups, 16 tv/{id}/season/{id}
        |Plex: 52 organized, 1 skipped, 0 failed
        |Plex requests: 3 movie/{id}/alternative_titles, 1 movie/{id}/external_ids, 20 search/multi, 3 tv/episode_group/{id}, 13 tv/{id}/alternative_titles, 2 tv/{id}/episode_groups, 11 tv/{id}/external_ids, 16 tv/{id}/season/{id}
    """)

    @Test
    fun `scene movies and shows`() = corpus(sceneNames, """
        |...And.Justice.for.All.1979.1080p.BluRay.x264-AMIABLE.mkv
        |  Jellyfin: And Justice for All (1979) [tmdbid-17443]/And Justice for All (1979) [tmdbid-17443] - [1080p] [BluRay] [AMIABLE].mkv
        |  Plex:     And Justice for All (1979) {imdb-tt0078718}/And Justice for All (1979) - [1080p] [BluRay] [AMIABLE].mkv
        |Ant-Man.and.the.Wasp.2018.1080p.WEB-DL.mkv
        |  Jellyfin: Ant-Man and the Wasp (2018) [tmdbid-363088]/Ant-Man and the Wasp (2018) [tmdbid-363088] - [1080p] [WEB-DL].mkv
        |  Plex:     Ant-Man and the Wasp (2018) {imdb-tt5095030}/Ant-Man and the Wasp (2018) - [1080p] [WEB-DL].mkv
        |Barbie.2023.1080p.Blu-ray.mkv
        |  Jellyfin: Barbie (2023) [tmdbid-346698]/Barbie (2023) [tmdbid-346698] - [1080p] [Blu-ray].mkv
        |  Plex:     Barbie (2023) {imdb-tt1517268}/Barbie (2023) - [1080p] [Blu-ray].mkv
        |Batman.Begins.2005.2160p.UHD.BluRay.mkv
        |  Jellyfin: Batman Begins (2005) [tmdbid-272]/Batman Begins (2005) [tmdbid-272] - [2160p] [BluRay].mkv
        |  Plex:     Batman Begins (2005) {imdb-tt0372784}/Batman Begins (2005) - [2160p] [BluRay].mkv
        |Blade.Runner.1982.The.Final.Cut.1080p.BluRay.x264-SPARKS.mkv
        |  Jellyfin: Blade Runner (1982) [tmdbid-78]/Blade Runner (1982) [tmdbid-78] - [1080p] [BluRay] [Final Cut] [SPARKS].mkv
        |  Plex:     Blade Runner (1982) {imdb-tt0083658}/Blade Runner (1982) - [1080p] [BluRay] [Final Cut] [SPARKS].mkv
        |Blade.Runner.2049.[2017].UHD.BluRay.2160p.x265-TERMiNAL.mkv
        |  Jellyfin: Blade Runner 2049 (2017) [tmdbid-335984]/Blade Runner 2049 (2017) [tmdbid-335984] - [2160p] [BluRay] [TERMiNAL].mkv
        |  Plex:     Blade Runner 2049 (2017) {imdb-tt1856101}/Blade Runner 2049 (2017) - [2160p] [BluRay] [TERMiNAL].mkv
        |Dune.2021.2160p.WEB-DL.x265-GalaxyRG[TGx].mkv
        |  Jellyfin: Dune (2021) [tmdbid-438631]/Dune (2021) [tmdbid-438631] - [2160p] [WEB-DL] [GalaxyRG].mkv
        |  Plex:     Dune (2021) {imdb-tt1160419}/Dune (2021) - [2160p] [WEB-DL] [GalaxyRG].mkv
        |Heat.1995.1080p.BluRay.DTS-ES.mkv
        |  Jellyfin: Heat (1995) [tmdbid-949]/Heat (1995) [tmdbid-949] - [1080p] [BluRay].mkv
        |  Plex:     Heat (1995) {imdb-tt0113277}/Heat (1995) - [1080p] [BluRay].mkv
        |Inception.2010.1080p.BluRay.x264-YTS.mkv
        |  Jellyfin: Inception (2010) [tmdbid-27205]/Inception (2010) [tmdbid-27205] - [1080p] [BluRay] [YTS].mkv
        |  Plex:     Inception (2010) {imdb-tt1375666}/Inception (2010) - [1080p] [BluRay] [YTS].mkv
        |Inception.2010.1080p.BluRay.x264-SPARKS.mkv
        |  Jellyfin: Inception (2010) [tmdbid-27205]/Inception (2010) [tmdbid-27205] - [1080p] [BluRay] [SPARKS].mkv
        |  Plex:     Inception (2010) {imdb-tt1375666}/Inception (2010) - [1080p] [BluRay] [SPARKS].mkv
        |Internal.Affairs.1990.1080p.BluRay.mkv
        |  Jellyfin: Internal Affairs (1990) [tmdbid-11060]/Internal Affairs (1990) [tmdbid-11060] - [1080p] [BluRay].mkv
        |  Plex:     Internal Affairs (1990) {imdb-tt0099850}/Internal Affairs (1990) - [1080p] [BluRay].mkv
        |Interstellar.2014.1080p.BluRay.DTS.x264-D-Z0N3.mkv
        |  Jellyfin: Interstellar (2014) [tmdbid-157336]/Interstellar (2014) [tmdbid-157336] - [1080p] [BluRay].mkv
        |  Plex:     Interstellar (2014) {imdb-tt0816692}/Interstellar (2014) - [1080p] [BluRay].mkv
        |Monsters.Inc.2001.1080p.BluRay.mkv
        |  Jellyfin: Monsters, Inc. (2001) [tmdbid-585]/Monsters, Inc. (2001) [tmdbid-585] - [1080p] [BluRay].mkv
        |  Plex:     Monsters, Inc. (2001) {imdb-tt0198781}/Monsters, Inc. (2001) - [1080p] [BluRay].mkv
        |Oppenheimer.2023.1080p.BluRay.DTS-HD.MA.5.1.x264-FGT.mkv
        |  Jellyfin: Oppenheimer (2023) [tmdbid-872585]/Oppenheimer (2023) [tmdbid-872585] - [1080p] [BluRay] [FGT].mkv
        |  Plex:     Oppenheimer (2023) {imdb-tt15398776}/Oppenheimer (2023) - [1080p] [BluRay] [FGT].mkv
        |S.W.A.T.2003.720p.BluRay.mkv
        |  Jellyfin: S.W.A.T. (2003) [tmdbid-9257]/S.W.A.T. (2003) [tmdbid-9257] - [720p] [BluRay].mkv
        |  Plex:     S.W.A.T. (2003) {imdb-tt0257076}/S.W.A.T. (2003) - [720p] [BluRay].mkv
        |Spider-Man.No.Way.Home.2021.2160p.WEB-DL.DDP5.1.Atmos.DV.HDR.H.265-FLUX.mkv
        |  Jellyfin: Spider-Man No Way Home (2021) [tmdbid-634649]/Spider-Man No Way Home (2021) [tmdbid-634649] - [2160p] [WEB-DL] [DV] [FLUX].mkv
        |  Plex:     Spider-Man No Way Home (2021) {imdb-tt10872600}/Spider-Man No Way Home (2021) - [2160p] [WEB-DL] [DV] [FLUX].mkv
        |Star.Wars.1977.1080p.mkv
        |  Jellyfin: Star Wars (1977) [tmdbid-11]/Star Wars (1977) [tmdbid-11] - [1080p].mkv
        |  Plex:     Star Wars (1977) {imdb-tt0076759}/Star Wars (1977) - [1080p].mkv
        |The.Avengers.2012.1080p.WEB-DL.mkv
        |  Jellyfin: The Avengers (2012) [tmdbid-24428]/The Avengers (2012) [tmdbid-24428] - [1080p] [WEB-DL].mkv
        |  Plex:     The Avengers (2012) {imdb-tt0848228}/The Avengers (2012) - [1080p] [WEB-DL].mkv
        |The.Matrix.1999.1080p.BluRay.x264-YTS.mkv
        |  Jellyfin: The Matrix (1999) [tmdbid-603]/The Matrix (1999) [tmdbid-603] - [1080p] [BluRay] [YTS].mkv
        |  Plex:     The Matrix (1999) {imdb-tt0133093}/The Matrix (1999) - [1080p] [BluRay] [YTS].mkv
        |X-Men.2000.1080p.BluRay.x264-SiNNERS.mkv
        |  Jellyfin: X-Men (2000) [tmdbid-36657]/X-Men (2000) [tmdbid-36657] - [1080p] [BluRay] [SiNNERS].mkv
        |  Plex:     X-Men (2000) {imdb-tt0120903}/X-Men (2000) - [1080p] [BluRay] [SiNNERS].mkv
        |El Camino A Breaking Bad Movie.mkv
        |  Jellyfin: El Camino A Breaking Bad Movie (2019) [tmdbid-559969]/El Camino A Breaking Bad Movie (2019) [tmdbid-559969].mkv
        |  Plex:     El Camino A Breaking Bad Movie (2019) {imdb-tt9243946}/El Camino A Breaking Bad Movie (2019).mkv
        |Wonder.Woman.1984.2020.1080p.WEB-DL.mkv
        |  Jellyfin: Wonder Woman 1984 (2020) [tmdbid-464052]/Wonder Woman 1984 (2020) [tmdbid-464052] - [1080p] [WEB-DL].mkv
        |  Plex:     Wonder Woman 1984 (2020) {imdb-tt7126948}/Wonder Woman 1984 (2020) - [1080p] [WEB-DL].mkv
        |The.Thing.2011.1080p.BluRay.mkv
        |  Jellyfin: The Thing (2011) [tmdbid-60935]/The Thing (2011) [tmdbid-60935] - [1080p] [BluRay].mkv
        |  Plex:     The Thing (2011) {imdb-tt0905372}/The Thing (2011) - [1080p] [BluRay].mkv
        |Dracula.1974.720p.BluRay.mkv
        |  Jellyfin: Dracula (1974) [tmdbid-86889]/Dracula (1974) [tmdbid-86889] - [720p] [BluRay].mkv
        |  Plex:     Dracula (1974) {imdb-tt0070003}/Dracula (1974) - [720p] [BluRay].mkv
        |Breaking Bad S01/Breaking.Bad.S01E01.720p.BluRay.mkv
        |  Jellyfin: Breaking Bad (2008) [tmdbid-1396]/Season 01/Breaking Bad (2008) - S01E01 - Episode 1 - [720p] [BluRay].mkv
        |  Plex:     Breaking Bad (2008) {imdb-tt0903747}/Season 01/Breaking Bad (2008) - S01E01 - Episode 1 - [720p] [BluRay].mkv
        |Breaking Bad S01/Breaking.Bad.S01E02.720p.BluRay.mkv
        |  Jellyfin: Breaking Bad (2008) [tmdbid-1396]/Season 01/Breaking Bad (2008) - S01E02 - Episode 2 - [720p] [BluRay].mkv
        |  Plex:     Breaking Bad (2008) {imdb-tt0903747}/Season 01/Breaking Bad (2008) - S01E02 - Episode 2 - [720p] [BluRay].mkv
        |Breaking Bad S01/Breaking.Bad.S01E07.720p.BluRay.mkv
        |  Jellyfin: Breaking Bad (2008) [tmdbid-1396]/Season 01/Breaking Bad (2008) - S01E07 - Episode 7 - [720p] [BluRay].mkv
        |  Plex:     Breaking Bad (2008) {imdb-tt0903747}/Season 01/Breaking Bad (2008) - S01E07 - Episode 7 - [720p] [BluRay].mkv
        |The Boys S03/The.Boys.S03E01.1080p.AMZN.WEB-DL.DDP5.1.H.264-NTb.mkv
        |  Jellyfin: The Boys (2019) [tmdbid-76479]/Season 03/The Boys (2019) - S03E01 - Episode 1 - [1080p] [WEB-DL] [NTb].mkv
        |  Plex:     The Boys (2019) {imdb-tt1190634}/Season 03/The Boys (2019) - S03E01 - Episode 1 - [1080p] [WEB-DL] [NTb].mkv
        |The Boys S03/The.Boys.S03E02.1080p.AMZN.WEB-DL.DDP5.1.H.264-NTb.mkv
        |  Jellyfin: The Boys (2019) [tmdbid-76479]/Season 03/The Boys (2019) - S03E02 - Episode 2 - [1080p] [WEB-DL] [NTb].mkv
        |  Plex:     The Boys (2019) {imdb-tt1190634}/Season 03/The Boys (2019) - S03E02 - Episode 2 - [1080p] [WEB-DL] [NTb].mkv
        |The Boys S03/The.Boys.S03E08.1080p.AMZN.WEB-DL.DDP5.1.H.264-NTb.mkv
        |  Jellyfin: The Boys (2019) [tmdbid-76479]/Season 03/The Boys (2019) - S03E08 - Episode 8 - [1080p] [WEB-DL] [NTb].mkv
        |  Plex:     The Boys (2019) {imdb-tt1190634}/Season 03/The Boys (2019) - S03E08 - Episode 8 - [1080p] [WEB-DL] [NTb].mkv
        |Doctor Who 2005 S01/Doctor.Who.2005.S01E01.1080p.BluRay.x264-SHORTBREHD.mkv
        |  Jellyfin: Doctor Who (2005) [tmdbid-57243]/Season 01/Doctor Who (2005) - S01E01 - Episode 1 - [1080p] [BluRay] [SHORTBREHD].mkv
        |  Plex:     Doctor Who (2005) {imdb-tt0436992}/Season 01/Doctor Who (2005) - S01E01 - Episode 1 - [1080p] [BluRay] [SHORTBREHD].mkv
        |Doctor Who 2005 S01/Doctor.Who.2005.S01E05.1080p.BluRay.x264-SHORTBREHD.mkv
        |  Jellyfin: Doctor Who (2005) [tmdbid-57243]/Season 01/Doctor Who (2005) - S01E05 - Episode 5 - [1080p] [BluRay] [SHORTBREHD].mkv
        |  Plex:     Doctor Who (2005) {imdb-tt0436992}/Season 01/Doctor Who (2005) - S01E05 - Episode 5 - [1080p] [BluRay] [SHORTBREHD].mkv
        |Dark S01/Dark.S01E01.1080p.NF.WEB-DL.mkv
        |  Jellyfin: Dark (2017) [tmdbid-70523]/Season 01/Dark (2017) - S01E01 - Episode 1 - [1080p] [WEB-DL].mkv
        |  Plex:     Dark (2017) {imdb-tt5753856}/Season 01/Dark (2017) - S01E01 - Episode 1 - [1080p] [WEB-DL].mkv
        |Dark S01/Dark.S01E05.1080p.NF.WEB-DL.mkv
        |  Jellyfin: Dark (2017) [tmdbid-70523]/Season 01/Dark (2017) - S01E05 - Episode 5 - [1080p] [WEB-DL].mkv
        |  Plex:     Dark (2017) {imdb-tt5753856}/Season 01/Dark (2017) - S01E05 - Episode 5 - [1080p] [WEB-DL].mkv
        |Space 1999/Space.1999.S01E01.Breakaway.mkv
        |  Jellyfin: Space 1999 (1975) [tmdbid-134]/Season 01/Space 1999 (1975) - S01E01 - Episode 1.mkv
        |  Plex:     Space 1999 (1975) {imdb-tt0072564}/Season 01/Space 1999 (1975) - S01E01 - Episode 1.mkv
        |Space 1999/Space.1999.S01E02.Matter.of.Life.and.Death.mkv
        |  Jellyfin: Space 1999 (1975) [tmdbid-134]/Season 01/Space 1999 (1975) - S01E02 - Episode 2.mkv
        |  Plex:     Space 1999 (1975) {imdb-tt0072564}/Season 01/Space 1999 (1975) - S01E02 - Episode 2.mkv
        |Durarara/Durarara!!x2 Ten - 01.mkv
        |  Jellyfin: Durarara!! (2010) [tmdbid-42410]/Season 01/Durarara!! (2010) - S01E01 - Episode 1.mkv
        |  Plex:     Durarara!! (2010) {imdb-tt1584000}/Season 01/Durarara!! (2010) - S01E01 - Episode 1.mkv
        |Durarara/Durarara!!x2 Ten - 02.mkv
        |  Jellyfin: Durarara!! (2010) [tmdbid-42410]/Season 01/Durarara!! (2010) - S01E02 - Episode 2.mkv
        |  Plex:     Durarara!! (2010) {imdb-tt1584000}/Season 01/Durarara!! (2010) - S01E02 - Episode 2.mkv
        |Durarara/Durarara!!x2 Ten - 03.mkv
        |  Jellyfin: Durarara!! (2010) [tmdbid-42410]/Season 01/Durarara!! (2010) - S01E03 - Episode 3.mkv
        |  Plex:     Durarara!! (2010) {imdb-tt1584000}/Season 01/Durarara!! (2010) - S01E03 - Episode 3.mkv
        |One Piece/One.Piece.S01E1071.1080p.mkv
        |  Jellyfin: One Piece (1999) [tmdbid-37854]/Season 21/One Piece (1999) - S21E1071 - Episode 1071 - [1080p].mkv
        |  Plex:     One Piece (1999) {imdb-tt0388629}/Season 21/One Piece (1999) - S21E1071 - Episode 1071 - [1080p].mkv
        |What If/What.If.2021.S01E01-E09.1080p.WEB-DL.mkv
        |  Jellyfin: What If... (2021) [tmdbid-91363]/Season 01/What If... (2021) - S01E01-E09 - What If... Episode 1 Went Another Way Entirely & What If... Episode 2 Went Another Way Entirely & What If... Episode 3 Went Another Way Entirely & What If... Episode 4 Went Another Way Entirely… - [1080p] [WEB-DL].mkv
        |  Plex:     What If... (2021) {imdb-tt10168312}/Season 01/What If... (2021) - S01E01-E09 - What If... Episode 1 Went Another Way Entirely & What If... Episode 2 Went Another Way Entirely & What If... Episode 3 Went Another Way Entirely & What If... Episode 4 Went Another Way Entirely… - [1080p] [WEB-DL].mkv
        |Jellyfin: 41 organized, 0 skipped, 0 failed
        |Jellyfin requests: 2 movie/{id}/alternative_titles, 1 search/movie, 33 search/multi, 8 tv/episode_group/{id}, 8 tv/{id}/alternative_titles, 1 tv/{id}/episode_groups, 8 tv/{id}/season/{id}
        |Plex: 41 organized, 0 skipped, 0 failed
        |Plex requests: 2 movie/{id}/alternative_titles, 23 movie/{id}/external_ids, 1 search/movie, 33 search/multi, 8 tv/episode_group/{id}, 8 tv/{id}/alternative_titles, 1 tv/{id}/episode_groups, 8 tv/{id}/external_ids, 8 tv/{id}/season/{id}
    """)

    @Test
    fun `names with IDs and multi-episode names`() = corpus(idAndMultiEpisodeNames, """
        |Blade Runner (1982) [tmdbid-78] - [Final Cut] [SPARKS].mkv
        |  Jellyfin: Blade Runner (1982) [tmdbid-78]/Blade Runner (1982) [tmdbid-78] - [Final Cut] [SPARKS].mkv
        |  Plex:     Blade Runner (1982) {imdb-tt0083658}/Blade Runner (1982) - [Final Cut] [SPARKS].mkv
        |Inception (2010) {imdb-tt1375666} - [2160p] [DV].mkv
        |  Jellyfin: Inception (2010) [tmdbid-27205]/Inception (2010) [tmdbid-27205] - [2160p] [DV].mkv
        |  Plex:     Inception (2010) {imdb-tt1375666}/Inception (2010) - [2160p] [DV].mkv
        |Inception (2010) [tmdbid-27205] - [1080p] [BluRay] [YTS.MX].mkv
        |  Jellyfin: Inception (2010) [tmdbid-27205]/Inception (2010) [tmdbid-27205] - [1080p] [BluRay] [YTS.MX].mkv
        |  Plex:     Inception (2010) {imdb-tt1375666}/Inception (2010) - [1080p] [BluRay] [YTS.MX].mkv
        |Movie (2010) {imdb-tt0000001} [tmdbid-27205].mkv
        |  Jellyfin: Inception (2010) [tmdbid-27205]/Inception (2010) [tmdbid-27205].mkv
        |  Plex:     Inception (2010) {imdb-tt1375666}/Inception (2010).mkv
        |Blade Runner 2049 (2017) [tmdbid-335984] - [2160p] [BluRay] [HDR10].mkv
        |  Jellyfin: Blade Runner 2049 (2017) [tmdbid-335984]/Blade Runner 2049 (2017) [tmdbid-335984] - [2160p] [BluRay] [HDR10].mkv
        |  Plex:     Blade Runner 2049 (2017) {imdb-tt1856101}/Blade Runner 2049 (2017) - [2160p] [BluRay] [HDR10].mkv
        |tmdb-1721501 (2026)/tmdb-1721501 (2026) - [1080p] [WEB-DL].mkv
        |  Jellyfin: tmdb-1721501 (2026) [tmdbid-1721501]/tmdb-1721501 (2026) [tmdbid-1721501] - [1080p] [WEB-DL].mkv
        |  Plex:     tmdb-1721501 (2026)/tmdb-1721501 (2026) - [1080p] [WEB-DL].mkv
        |Breaking Bad (2008) [tmdbid-1396]/Season 01/Breaking Bad (2008) - S01E01-E02 - Pilot & Cat's in the Bag - [720p].mkv
        |  Jellyfin: Breaking Bad (2008) [tmdbid-1396]/Season 01/Breaking Bad (2008) - S01E01-E02 - Episode 1 & Episode 2 - [720p].mkv
        |  Plex:     Breaking Bad (2008) {imdb-tt0903747}/Season 01/Breaking Bad (2008) - S01E01-E02 - Episode 1 & Episode 2 - [720p].mkv
        |Breaking Bad Extras/Breaking.Bad.S01E03E04.720p.BluRay.mkv
        |  Jellyfin: Breaking Bad (2008) [tmdbid-1396]/Season 01/Breaking Bad (2008) - S01E03-E04 - Episode 3 & Episode 4 - [720p] [BluRay].mkv
        |  Plex:     Breaking Bad (2008) {imdb-tt0903747}/Season 01/Breaking Bad (2008) - S01E03-E04 - Episode 3 & Episode 4 - [720p] [BluRay].mkv
        |Doctor Who Finale/Doctor.Who.S04E12E13E14.1080p.mkv
        |  Jellyfin: Doctor Who (2005) [tmdbid-57243]/Season 04/Doctor Who (2005) - S04E12 - Episode 12 - [1080p].mkv
        |  Plex:     Doctor Who (2005) {imdb-tt0436992}/Season 04/Doctor Who (2005) - S04E12 - Episode 12 - [1080p].mkv
        |Gate (2015) [tmdbid-64196]/Season 01/Gate (2015) - S01E13 - The Banquet Begins - [720p] [HDTV] [AniLibria.TV].mkv
        |  Jellyfin: Gate (2015) [tmdbid-63663]/Season 01/Gate (2015) - S01E13 - Episode 13 - [720p] [HDTV] [AniLibria].mkv
        |  Plex:     Gate (2015) {imdb-tt4958580}/Season 01/Gate (2015) - S01E13 - Episode 13 - [720p] [HDTV] [AniLibria].mkv
        |One Piece (1999) {imdb-tt0388629}/Season 01/One Piece (1999) - S01E1072 - [1080p].mkv
        |  Jellyfin: One Piece (1999) [tmdbid-37854]/Season 21/One Piece (1999) - S21E1072 - Episode 1072 - [1080p].mkv
        |  Plex:     One Piece (1999) {imdb-tt0388629}/Season 21/One Piece (1999) - S21E1072 - Episode 1072 - [1080p].mkv
        |Jellyfin: 11 organized, 0 skipped, 0 failed
        |Jellyfin requests: 2 find/{id}, 6 movie/{id}, 3 search/multi, 12 tv/episode_group/{id}, 3 tv/{id}, 2 tv/{id}/episode_groups, 4 tv/{id}/season/{id}
        |Plex: 11 organized, 0 skipped, 0 failed
        |Plex requests: 2 find/{id}, 6 movie/{id}, 1 movie/{id}/external_ids, 3 search/multi, 12 tv/episode_group/{id}, 3 tv/{id}, 2 tv/{id}/episode_groups, 3 tv/{id}/external_ids, 4 tv/{id}/season/{id}
    """)


    @Test
    fun `every name plexify writes leads back to itself`() = capped(60_000) {
        val actual = TEMPLATES.flatMap { template ->
            val written = listOf(animeNames, sceneNames, idAndMultiEpisodeNames).flatMap { names ->
                val run = run(names, template)
                names.mapNotNull { run.libraryPath(it) }
            }
            val again = run(written, template)
            val moved = written.filter { again.libraryPath(it) != it }.map { "$it\n  -> ${again.describe(it)}" }
            if (moved.isNotEmpty()) fail("${template.name}: ${moved.size} of ${written.size} names lead elsewhere:\n${moved.joinToString("\n")}")
            listOf("${template.name}: ${again.counts()}", "${template.name} requests: ${again.requests()}")
        }
        assertBlock(actual, """
            |Jellyfin: 104 organized, 0 skipped, 0 failed
            |Jellyfin requests: 31 movie/{id}, 19 tv/{id}, 25 tv/{id}/season/{id}
            |Plex: 104 organized, 0 skipped, 0 failed
            |Plex requests: 49 find/{id}, 31 movie/{id}, 1 movie/{id}/external_ids, 19 tv/{id}, 25 tv/{id}/season/{id}
        """)
    }

    private fun corpus(names: List<String>, expected: String) = capped(60_000) {
        val runs = TEMPLATES.map { run(names, it) }
        val actual = buildList {
            for (name in names) {
                add(name)
                TEMPLATES.forEachIndexed { index, template -> add("  ${"${template.name}:".padEnd(9)} ${runs[index].describe(name)}") }
            }
            TEMPLATES.forEachIndexed { index, template ->
                add("${template.name}: ${runs[index].counts()}")
                add("${template.name} requests: ${runs[index].requests()}")
            }
        }
        assertBlock(actual, expected)
    }

    private fun assertBlock(actual: List<String>, expected: String) {
        if (expected.trimMargin().lines().filter { it.isNotBlank() } != actual) {
            fail("The corpus differs. If the change is intended, paste this over the expected block:\n" +
                actual.joinToString("\n", prefix = "    \"\"\"\n", postfix = "\n    \"\"\"") { "        |" + it.replace("$", "\${'$'}") })
        }
    }

    private suspend fun run(names: List<String>, template: NamingStrategy): CorpusRun {
        val organizer = RecordingOrganizer(DefaultFileOrganizer(PathFormatter(), template))
        val tmdb = TmdbResponder(corpusTmdb.bodies, corpusTmdb.notFound)
        val run = runProcessor(mockProvider(credentials = tokenCredentials, handler = tmdb.handler), *names.toTypedArray(), namingStrategy = template, organizer = organizer)
        if (tmdb.unanswered.isNotEmpty()) {
            fail("The fixture has no answer for these requests; add what TMDB answers to CorpusTmdb.kt, from a live run:\n" +
                tmdb.unanswered.distinct().joinToString("\n"))
        }
        return CorpusRun(run, tmdb)
    }

    private class CorpusRun(val run: ProcessorRun<*>, val tmdb: TmdbResponder) {
        /** Where the file went, relative to the library, if it was organized. */
        fun libraryPath(name: String): String? = (run.outcomeOf(name)?.getOrNull() as? OrganizeOutcome.Organized)?.path?.relative()

        fun describe(name: String): String = when (val outcome = run.outcomeOf(name)) {
            null -> "(not organized)"
            else -> outcome.fold(
                onSuccess = { if (it is OrganizeOutcome.Organized) it.path.relative() else "${it::class.simpleName} ${it.path.relative()}" },
                onFailure = { "failed: ${it.message}" },
            )
        }

        fun counts() = "${run.stats.organized} organized, ${run.stats.skipped} skipped, ${run.stats.failed} failed"

        /** The TMDB requests made, counted by endpoint ("tv/{id}/season/{id}"). */
        fun requests(): String = requestsByEndpoint(tmdb.paths)

        private fun kotlinx.io.files.Path.relative() = toString().replace('\\', '/').replace(LIBRARY, "")
    }

    private companion object {
        val TEMPLATES = listOf(NamingStrategy.Jellyfin, NamingStrategy.Plex)

        // The library runProcessor files into: "library" in a fresh temporary folder, which is relative on the Linux CI
        // runner (no TMPDIR).
        val LIBRARY = Regex("""^(?:.*/)?plexify-processor-test-\d+/library/""")
    }
}
