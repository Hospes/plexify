package io.github.hospes.plexify.core.corpus

// Real release names, as the October 2026 audit's live runs used them (empty files: plexify reads only names), and
// names plexify itself writes. A path leads through its release folder, as on disk; the folder may name the season.

private fun episodes(folder: String, numbers: List<Int>, name: (String) -> String) =
    numbers.map { "$folder/${name(it.toString().padStart(2, '0'))}" }

/** AniLibria/AniLiberty (and one LostFilm) anime releases, one folder per release, plus a film at the top. */
internal val animeNames: List<String> =
    episodes("Devil May Cry - AniLibria [WEBRip 1080p HEVC]", listOf(1, 2, 8)) { "Devil_May_Cry_[$it]_[AniLibria]_[WEBRip_1080p_HEVC].mkv" } +
        episodes("Fullmetal Alchemist Brotherhood - AniLibria.TV [BDRip 1080p HEVC]", listOf(1, 2, 63, 64)) { "Fullmetal_Alchemist_Brotherhood_[$it]_[AniLibria_TV]_[BDRip_1080p_HEVC].mkv" } +
        episodes("Gate S2", listOf(1, 2, 12)) { "Gate_[$it]_[720p].mkv" } +
        episodes("Kashita Maryoku wa Revo Barai de Kyousei Choushuu - AniLiberty [WEB-DL 1080p HEVC]", listOf(1, 2, 3)) { "Kashita_Maryoku_wa_Revo_Barai_de_Kyousei_Choushuu_[$it]_[HEVC].mkv" } +
        episodes("Mamonogurai no Boukensha - AniLiberty [WEB-DL 1080p HEVC]", listOf(1, 2, 12)) { "Mamonogurai_no_Boukensha_Ore_dake_Mamono_wo_Kuratte_Tsuyoku_Naru_[$it]_[HEVC].mkv" } +
        episodes("Nozomanu Fushi no Boukensha - AniLibria.TV [WEBRip 1080p HEVC]", listOf(1, 2, 12)) { "Nozomanu_Fushi_no_Boukensha_[$it]_[AniLibria_TV]_[WEBRip_1080p_HEVC].mkv" } +
        episodes("Record of Ragnarok 2 - AniLibria.TV [WEBRip 1080p]", listOf(1, 2, 6)) { "Record_of_Ragnarok_2_[$it]_[AniLibria_TV]_[WEBRip_1080p].mkv" } +
        episodes("Record of Ragnarok - AniLibria.TV [WEBRip 1080p HEVC]", listOf(1, 2, 11)) { "Record_of_Ragnarok_[$it]_[AniLibria_TV]_[WEBRip_1080p_HEVC].mkv" } +
        "Record of Ragnarok - AniLibria.TV [WEBRip 1080p HEVC]/Record_of_Ragnarok_[12]_[AniLibria_TV]_[WEBRip_1080p_HEVC]_[END].mkv" +
        episodes("Shuumatsu no Walkure III - AniLiberty [WEB-DL 1080p HEVC]", listOf(1, 2, 12)) { "Shuumatsu_no_Walkure_III_[$it]_[HEVC].mkv" } +
        episodes("Star Wars Visions Presents - The Ninth Jedi - AniLiberty [WEB-DL 1080p HEVC]", listOf(1, 2, 8)) { "Star_Wars_Visions_Presents_-_The_Ninth_Jedi_[$it]_[HEVC].mkv" } +
        episodes("Tsue to Tsurugi no Wistoria Season 2 - AniLiberty [WEB-DL 1080p HEVC]", listOf(0, 1, 2, 12)) { "Tsue_to_Tsurugi_no_Wistoria_Season_2_[$it]_[HEVC].mkv" } +
        episodes("Kimetsu no Yaiba - AniLibria.TV [HDTVRip 1080p HEVC]", listOf(1, 2, 26)) { "Kimetsu_no_Yaiba_[$it]_[AniLibria.TV]_[HDTVRip_1080p_HEVC].mkv" } +
        episodes("Kimetsu no Yaiba Mugen Ressha-hen (TV) - AniLibria.TV [WEBRip 1080p HEVC]", listOf(1, 2, 7)) { "Kimetsu_no_Yaiba_Mugen_Ressha-hen_(TV)_[$it]_[AniLibria_TV]_[WEBRip_1080p_HEVC].mkv" } +
        episodes("Kimetsu no Yaiba - Yuukaku-hen - AniLibria.TV [WEBRip 1080p HEVC]", listOf(1, 2, 10)) { "Kimetsu_no_Yaiba_-_Yuukaku-hen_[$it]_[AniLibria_TV]_[WEBRip_1080p_HEVC].mkv" } +
        "Kimetsu no Yaiba - Yuukaku-hen - AniLibria.TV [WEBRip 1080p HEVC]/Kimetsu_no_Yaiba_-_Yuukaku-hen_[11_END]_[AniLibria_TV]_[WEBRip_1080p_HEVC].mkv" +
        episodes("Kimetsu no Yaiba - Hashira Geiko-hen - AniLibria [WEBRip 1080p HEVC]", listOf(1, 2, 8)) { "Kimetsu_no_Yaiba_-_Hashira_Geiko-hen_[$it]_[AniLibria]_[WEBRip_1080p_HEVC].mkv" } +
        episodes("Cyberpunk. Edgerunners 1 - LostFilm.TV [1080p]", listOf(1, 2, 10)) { "Cyberpunk.Edgerunners.S01E$it.1080p.rus.LostFilm.TV.mkv" } +
        "The Lord of the Rings The War of the Rohirrim_[AniLibria]_[WEBRip_1080p_HEVC].mkv"

/** Scene movies at the top, and Western (and two anime) shows in their release folders. */
internal val sceneNames: List<String> = listOf(
    "...And.Justice.for.All.1979.1080p.BluRay.x264-AMIABLE.mkv",
    "Ant-Man.and.the.Wasp.2018.1080p.WEB-DL.mkv",
    "Barbie.2023.1080p.Blu-ray.mkv",
    "Batman.Begins.2005.2160p.UHD.BluRay.mkv",
    "Blade.Runner.1982.The.Final.Cut.1080p.BluRay.x264-SPARKS.mkv",
    "Blade.Runner.2049.[2017].UHD.BluRay.2160p.x265-TERMiNAL.mkv",
    "Dune.2021.2160p.WEB-DL.x265-GalaxyRG[TGx].mkv",
    "Heat.1995.1080p.BluRay.DTS-ES.mkv",
    "Inception.2010.1080p.BluRay.x264-YTS.mkv",
    "Inception.2010.1080p.BluRay.x264-SPARKS.mkv",
    "Internal.Affairs.1990.1080p.BluRay.mkv",
    "Interstellar.2014.1080p.BluRay.DTS.x264-D-Z0N3.mkv",
    "Monsters.Inc.2001.1080p.BluRay.mkv",
    "Oppenheimer.2023.1080p.BluRay.DTS-HD.MA.5.1.x264-FGT.mkv",
    "S.W.A.T.2003.720p.BluRay.mkv",
    "Spider-Man.No.Way.Home.2021.2160p.WEB-DL.DDP5.1.Atmos.DV.HDR.H.265-FLUX.mkv",
    "Star.Wars.1977.1080p.mkv",
    "The.Avengers.2012.1080p.WEB-DL.mkv",
    "The.Matrix.1999.1080p.BluRay.x264-YTS.mkv",
    "X-Men.2000.1080p.BluRay.x264-SiNNERS.mkv",
    "El Camino A Breaking Bad Movie.mkv",
    "Wonder.Woman.1984.2020.1080p.WEB-DL.mkv",
    "The.Thing.2011.1080p.BluRay.mkv",
    "Dracula.1974.720p.BluRay.mkv",
) +
    episodes("Breaking Bad S01", listOf(1, 2, 7)) { "Breaking.Bad.S01E$it.720p.BluRay.mkv" } +
    episodes("The Boys S03", listOf(1, 2, 8)) { "The.Boys.S03E$it.1080p.AMZN.WEB-DL.DDP5.1.H.264-NTb.mkv" } +
    episodes("Doctor Who 2005 S01", listOf(1, 5)) { "Doctor.Who.2005.S01E$it.1080p.BluRay.x264-SHORTBREHD.mkv" } +
    episodes("Dark S01", listOf(1, 5)) { "Dark.S01E$it.1080p.NF.WEB-DL.mkv" } +
    listOf(
        "Space 1999/Space.1999.S01E01.Breakaway.mkv",
        "Space 1999/Space.1999.S01E02.Matter.of.Life.and.Death.mkv",
        "Durarara/Durarara!!x2 Ten - 01.mkv",
        "Durarara/Durarara!!x2 Ten - 02.mkv",
        "Durarara/Durarara!!x2 Ten - 03.mkv",
        "One Piece/One.Piece.S01E1071.1080p.mkv",
        "What If/What.If.2021.S01E01-E09.1080p.WEB-DL.mkv",
    )

/**
 * Names that carry an ID, multi-episode names, and names plexify writes (also by a title's ID), from the parser tests.
 * The round trip in CorpusTest adds every name the release runs write.
 */
internal val idAndMultiEpisodeNames: List<String> = listOf(
    "Blade Runner (1982) [tmdbid-78] - [Final Cut] [SPARKS].mkv",
    "Inception (2010) {imdb-tt1375666} - [2160p] [DV].mkv",
    "Inception (2010) [tmdbid-27205] - [1080p] [BluRay] [YTS.MX].mkv",
    // Two IDs, the TMDB one first, under a title that isn't the film's.
    "Movie (2010) {imdb-tt0000001} [tmdbid-27205].mkv",
    "Blade Runner 2049 (2017) [tmdbid-335984] - [2160p] [BluRay] [HDR10].mkv",
    // A title plexify names by its ID (TMDB titles movie 1721501 "?").
    "tmdb-1721501 (2026)/tmdb-1721501 (2026) - [1080p] [WEB-DL].mkv",
    "Breaking Bad (2008) [tmdbid-1396]/Season 01/Breaking Bad (2008) - S01E01-E02 - Pilot & Cat's in the Bag - [720p].mkv",
    "Breaking Bad Extras/Breaking.Bad.S01E03E04.720p.BluRay.mkv",
    "Doctor Who Finale/Doctor.Who.S04E12E13E14.1080p.mkv",
    // The folder's ID is another show's (Overlord).
    "Gate (2015) [tmdbid-64196]/Season 01/Gate (2015) - S01E13 - The Banquet Begins - [720p] [HDTV] [AniLibria.TV].mkv",
    "One Piece (1999) {imdb-tt0388629}/Season 01/One Piece (1999) - S01E1072 - [1080p].mkv",
)
