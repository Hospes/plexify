package io.github.hospes.plexify.core.corpus

// What TMDB answers the corpus with, written out by hand (see TmdbFixture). No TMDB response is stored here: per the
// TMDB API terms (cached data no older than six months, no derivative data sets handed to others), the fixture holds
// only the facts plexify matches on: IDs, titles, original titles, years, the alternative titles that can decide a
// match, and episode numbering. Episode names are placeholders.
//
// Searches list the results plexify can act on, in TMDB's order: every one whose own title resembles the query (by
// TMDB's confidence or plexify's similarity), and the first three that don't (those get an alternative-titles
// lookup). Records list the aliases that can decide a match: Latin-script ones about as close to a query (or to the
// title an ID name gives) as the record's own titles, every season-tagged one in Latin script, and every one ending in
// an arc ("-hen", "編").
//
// To add a name to the corpus: add the records and searches it needs, run CorpusTest and check the new paths.

internal val corpusTmdb: Map<String, String> = tmdbFixture {
    movie(17443, "...And Justice for All", 1979, imdb = "tt0078718") {
        alias("...And Justice for All.")
        alias("And Justice for All")
    }
    movie(1721501, "?", 2026, original = "\"?\"") {
        alias("Fragezeichen")
    }
    movie(790862, "And Justice for All", 1998)
    movie(363088, "Ant-Man and the Wasp", 2018, imdb = "tt5095030")
    movie(640146, "Ant-Man and the Wasp: Quantumania", 2023) {
        alias("Ant-Man And The Wasp - Quantumania")
    }
    movie(139588, "Barbie", 2011, original = "바비")
    movie(346698, "Barbie", 2023, imdb = "tt1517268")
    movie(18198, "Barbie Mariposa", 2008) {
        alias("Barbie - Mariposa")
        alias("Barbie: Mariposa")
        alias("Barbie: Butterfly")
    }
    movie(15016, "Barbie of Swan Lake", 2003) {
        alias("Barbie i 'Svanesjøen'")
        alias("Barbie - Joutsenlampi")
        alias("Barbie Lac des cygnes")
        alias("Barbie Svanesoeen")
    }
    movie(272, "Batman Begins", 2005, imdb = "tt0372784")
    movie(498471, "Batman: The Animated Series - The Legend Begins", 2002) {
        alias("Batman  The Animated Series The Legend Begins")
        alias("The Legend Begins")
        alias("Batman The Legend Begins")
        alias("Batman The Animated Series")
    }
    movie(542438, "Bikini Inception", 2015)
    movie(78, "Blade Runner", 1982, imdb = "tt0083658") {
        alias("Blade Runner")
    }
    movie(335984, "Blade Runner 2049", 2017, imdb = "tt1856101") {
        alias("Blade Runner 2")
        alias("Blade Runner 2 - 2049")
        alias("Blade Runner 6 - 2049")
        alias("Blade Runner 5 - 2049")
    }
    movie(1209629, "Bleeding Tiger: Internal Affairs", 2025, original = "Harimau Merah: Intrik Dalaman") {
        alias("Bleeding Tiger: Internal Affairs")
    }
    movie(264351, "Blind - What If?", 2005) {
        alias("Blind: What If?")
    }
    movie(25944, "Blood for Dracula", 1974) {
        alias("Sangre para Dracula")
        alias("Sangue per Dracula")
    }
    movie(635602, "Breaking Bad Wolf", null)
    movie(1399762, "Breaking in Badly", 2024)
    movie(1443305, "Container Interstellar", 2001)
    movie(39043, "Countess Dracula", 1971) {
        alias("Draculan himo")
        alias("Drakula grófnő")
        alias("Grevinne Dracula")
    }
    movie(57656, "Dangerous Days: Making Blade Runner", 2007)
    movie(1311031, "Demon Slayer: Kimetsu no Yaiba Infinity Castle", 2025, original = "劇場版「鬼滅の刃」無限城編 第一章 猗窩座再来") {
        alias("Gekijōban Kimetsu no Yaiba Mugenjō-hen")
        alias("Gekijouban Kimetsu no Yaiba Mugenjou-hen")
        alias("Kimetsu no Yaiba: Mugenjou-hen")
        alias("Kimetsu no Yaiba Movie: Mugen Jou-hen")
        alias("「鬼滅之刃」無限城編")
        alias("Kimetsu no Yaiba: Mugenjou-hen Movie 1 - Akaza Sairai")
        alias("Demon Slayer: Kimetsu no Yaiba Sonsuzluk Kalesi")
        alias("劇場版 鬼滅之刃 無限城篇")
        alias("Demon Slayer: Kimetsu no Yaiba - Castillo infinito")
        alias("Demon Slayer: Kimetsu no Yaiba Infinity Castle - Teil 1")
        alias("Gekijouban Kimetsu no Yaiba: Mugen-jou-hen Movie 1")
        alias("Demon Slayer: Kimetsu no Yaiba Castelo Infinito")
        alias("Gekijô-ban Kimetsu no Yaiba Mugen Jô-hen")
        alias("Gekijo-ban Kimetsu No Yaiba Mugen Jo-hen")
        alias("Demon Slayer: Kimetsu no Yaiba Infinity Castle I")
    }
    movie(1141171, "Demon Slayer: Kimetsu no Yaiba ON STAGE", 2023)
    movie(339102, "Devil May Cry", null)
    movie(1260450, "Devil May Cry", 2009, original = "Obonsam Besu")
    movie(663364, "DEVIL MAY CRY ーTHE LIVE HACKERー", 2019)
    movie(1618462, "Devil May Cry: Expression Through Style", 2025)
    movie(15691, "Doctor Who", 1996)
    movie(434605, "Doctor Who: Robot", 1975)
    movie(138, "Dracula", 1931)
    movie(11868, "Dracula", 1958)
    movie(33521, "Dracula", 1979)
    movie(86889, "Dracula", 1974, imdb = "tt0070003") {
        alias("Dracula")
    }
    movie(1246049, "Dracula", 2025)
    movie(1655673, "Dracula", 2002)
    movie(10577, "Dracula 2000", 2000)
    movie(49017, "Dracula Untold", 2014)
    movie(841, "Dune", 1984)
    movie(438631, "Dune", 2021, imdb = "tt1160419")
    movie(627150, "Dune", 1989, original = "Бархан")
    movie(697620, "Dune", 2020, original = "Dűne")
    movie(1170608, "Dune: Part Three", 2026) {
        alias("Dune 3")
        alias("Dune: Part 3")
        alias("Duna 3")
        alias("Duna: Parte 3")
        alias("Dune: Bagian Tiga")
        alias("Dyun 3")
        alias("Dune : Partie 3")
        alias("Duna: Parte Tres")
    }
    movie(693134, "Dune: Part Two", 2024) {
        alias("Dune 2")
        alias("Dune: Part 2")
        alias("Duna 2")
        alias("Duna: Parte Dois")
        alias("Dune: Del 2")
        alias("Dyun 2")
        alias("Duna: Parte 2")
        alias("Dune Two")
        alias("Duna Parte 2")
        alias("Dune Part Two")
    }
    movie(559969, "El Camino: A Breaking Bad Movie", 2019, imdb = "tt9243946") {
        alias("El Camino Ein Breaking Bad - Film")
        alias("El Camino - A Breaking Bad Movie")
        alias("El Camino - Un film Breaking Bad")
        alias("El Camino A Breaking Bad Movie")
        alias("Breaking Bad - El Camino")
    }
    movie(1527118, "Erotic Blade Runner", 2002)
    movie(1412074, "Fullmetal Alchemist 15th Anniversary Special Edition", 2024, original = "《鋼之鍊金術師BROTHERHOOD 15週年特別版》")
    movie(1467973, "Fullmetal Alchemist: Brotherhood - OVA Collection", 2012)
    movie(1103694, "Gate", 2022)
    movie(40729, "Gate II", 1990)
    movie(949, "Heat", 1995, imdb = "tt0113277")
    movie(27205, "Inception", 2010, imdb = "tt1375666") {
        alias("El Origen")
        alias("A Origem")
        alias("Eredet")
        alias("Orixe")
        alias("Dechreuad y Creu")
        alias("Početak")
        alias("Pradžia")
        alias("Muqaddima")
        alias("Počiatok")
        alias("Izvor")
        alias("Počátek")
    }
    movie(1359046, "Inception", 1980, original = "Эхлэл")
    movie(64956, "Inception: The Cobol Job", 2010)
    movie(529107, "Inside 'Interstellar'", 2015)
    movie(11060, "Internal Affairs", 1990, imdb = "tt0099850")
    movie(545434, "Internal Affairs", 2006, original = "100% Pur Jus")
    movie(643963, "Internal Affairs", 1988)
    movie(793067, "Internal Affairs", 1995)
    movie(836688, "Internal Affairs", 2020)
    movie(862079, "Internal Affairs: From the Files of Valley 911", 2005)
    movie(157336, "Interstellar", 2014, imdb = "tt0816692") {
        alias("Interstellar")
    }
    movie(1391726, "Interstellar Stella", 2006)
    movie(649745, "Interstellar Wandering", 2019, original = "星际流浪")
    movie(398188, "Interstellar Wars", 2016)
    movie(1309626, "ISMO – Breaking Bad English", 2024, original = "ISMO - Maailman hauskin ulkopuolinen")
    movie(684431, "Making \"The Matrix\"", 1999)
    movie(1127361, "Marvel Studios Assembled: The Making of Ant-Man and the Wasp: Quantumania", 2023) {
        alias("Assembled: The Making of Ant-Man and the Wasp: Quantumania")
        alias("AVANTE: Nos Bastidores de Homem-Formiga e a Vespa: Quantumania")
        alias("The Making of Ant-Man and the Wasp: Quantumania")
    }
    movie(682407, "Metallica - ...And Justice For All", 1992)
    movie(339026, "Monsters of the UFO: Incident at Kelly", 2005)
    movie(585, "Monsters, Inc.", 2001, imdb = "tt0198781")
    movie(1647601, "Monsters, Inc. 3", null)
    movie(900667, "One Piece Film Red", 2022, original = "ONE PIECE FILM RED") {
        alias("One Piece Movie 15")
        alias("ONE PIECE FILM RED")
    }
    movie(374205, "One Piece Film: GOLD", 2016, original = "ONE PIECE FILM GOLD") {
        alias("One Piece Film - Gold")
        alias("One Piece: GOLD")
        alias("One Piece: Gold")
        alias("One Piece Gold")
        alias("One Piece Film: Gold")
        alias("One Piece Movie 13")
        alias("ONE PIECE FILM GOLD")
    }
    movie(176983, "One Piece Film: Z", 2012, original = "ONE PIECE FILM Z")
    movie(1659389, "One Piece Heroines", 2026, original = "ONE PIECE HEROINES") {
        alias("One Piece: Heroines")
    }
    movie(872585, "Oppenheimer", 2023, imdb = "tt15398776")
    movie(1634345, "Oppenheimer", 2024)
    movie(1143770, "Oppenheimer After Trinity", 2023)
    movie(1365124, "Pixar Remix: Monsters, Inc.", 2021)
    movie(744089, "S W E A T", 2020)
    movie(9257, "S.W.A.T.", 2003, imdb = "tt0257076") {
        alias("SWAT")
        alias("S W A T")
    }
    movie(559937, "S.W.A.T.", 2019, original = "特警队")
    movie(54445, "S.W.A.T.: Firefight", 2011)
    movie(461053, "S.W.A.T.: Under Siege", 2017)
    movie(663056, "Sengoku Basara vs Devil May Cry", 2015)
    movie(344225, "Sex and the Matrix", 2000)
    movie(2300, "Space Jam", 1996)
    movie(542499, "Space: 1999", 1975, original = "Spazio: 1999")
    movie(1599287, "Space: 1999 - 50 Years Out of Orbit", 2025)
    movie(1768685, "Space: 1999 4K Double Bill - Breakaway and The Metamorph", 2026)
    movie(961651, "Spider-Man: All Roads Lead to No Way Home", 2022)
    movie(634649, "Spider-Man: No Way Home", 2021, imdb = "tt10872600") {
        alias("Spider Man No Way Home")
        alias("Spider-Man. No Way Home")
        alias("Spider-man 5 No Way Home")
    }
    movie(1750432, "Spidey and the Avengers: Halloween Team-Up!", 2026)
    movie(11, "Star Wars", 1977, imdb = "tt0076759")
    movie(1388175, "Temptation on The Force 1: Internal Affairs", 2004)
    movie(153281, "Tender Dracula", 1974, original = "La Grande Trouille") {
        alias("Tendre Dracula")
    }
    movie(9320, "The Avengers", 1998)
    movie(24428, "The Avengers", 2012, imdb = "tt0848228")
    movie(432413, "The Avengers", 1950)
    movie(685795, "The Avengers", 1966, original = "Οι εκδικηταί")
    movie(833898, "The Blade Runner Phenomenon", 2021, original = "Das Phänomen Blade Runner")
    movie(613092, "The Crack: Inception", 2019, original = "El crack cero")
    movie(271683, "The Dune", 2014, original = "La dune")
    movie(1644507, "The Kawayoku Inception", 2020)
    movie(839033, "The Lord of the Rings: The War of the Rohirrim", 2024, imdb = "tt14824600")
    movie(1547, "The Lost Boys", 1987)
    movie(603, "The Matrix", 1999, imdb = "tt0133093")
    movie(1386216, "The Matrix", 2004)
    movie(1502836, "The Matrix", null, original = "A Matriz")
    movie(1268926, "The Matrix 5", null)
    movie(604, "The Matrix Reloaded", 2003) {
        alias("The Matrix 2")
        alias("Matrix 2")
    }
    movie(624860, "The Matrix Resurrections", 2021) {
        alias("The Matrix 4")
        alias("Matrix 4")
        alias("The Matrix 4 Resurrections")
        alias("The Matrix IV: Resurrections")
    }
    movie(605, "The Matrix Revolutions", 2003) {
        alias("The Matrix 3")
        alias("Matrix 3")
    }
    movie(622, "The Ninth Gate", 1999)
    movie(1156529, "The Oppenheimer Case", 1970, original = "Slučaj Openhajmer")
    movie(1521971, "The Oppenheimer Case", 2025, original = "Spionagefall Robert Oppenheimer") {
        alias("Le cas Oppenheimer")
    }
    movie(1230570, "The Real Oppenheimer", 2023)
    movie(934809, "The Road to El Camino: Behind the Scenes of El Camino: A Breaking Bad Movie", 2019)
    movie(336592, "The Science of Interstellar", 2014)
    movie(109088, "The Shaolin Avengers", 1976, original = "方世玉與胡惠乾")
    movie(1775853, "The Space: 1999 Documentary", 1996)
    movie(8992, "The Sure Thing", 1985)
    movie(11812, "The Sweetest Thing", 2002)
    movie(1091, "The Thing", 1982)
    movie(60935, "The Thing", 2011, imdb = "tt0905372")
    movie(275663, "The Three Avengers", 1979, original = "打出頭")
    movie(14609, "Ultimate Avengers: The Movie", 2006) {
        alias("Ultimate Avengers: The Movie")
        alias("Les Vengeurs Ultimate")
        alias("Les vengeurs")
        alias("Avengers: Ultimate Avengers")
    }
    movie(212716, "What If", 2013)
    movie(792494, "What If", 2021)
    movie(1352212, "What If", null)
    movie(1773380, "What If Christmas", 2026)
    movie(42950, "What if...", 2010)
    movie(822188, "What If?", 2021)
    movie(1005011, "What If?", null)
    movie(1390359, "What If?", 2024)
    movie(1357565, "With Liberty and Justice for All", 2024)
    movie(464052, "Wonder Woman 1984", 2020, imdb = "tt7126948")
    movie(250845, "WWA The Inception", 2001)
    movie(36657, "X-Men", 2000, imdb = "tt0120903") {
        alias("X-MEN")
    }
    movie(246655, "X-Men: Apocalypse", 2016) {
        alias("X-Men 6 - Apocalipse")
        alias("X-Men: Apokalipszis")
        alias("X-Men 6: Apocalipsis")
        alias("X-Men 6 Apocalypse")
        alias("X-Men 6: Apocalypse")
        alias("X-Men 6 - Apocalypse")
    }
    movie(127585, "X-Men: Days of Future Past", 2014)
    tv(324407, "...And Justice for All", null)
    tv(108847, "AEW Dark", 2019)
    tv(234586, "Barbie Mysteries", 2024) {
        alias("Barbie Mysteries")
    }
    tv(171802, "Blade Runner 2099", 2026)
    tv(84553, "Blade Runner: Black Lotus", 2021)
    tv(1396, "Breaking Bad", 2008, imdb = "tt0903747") {
        season(1, 1..7)
    }
    tv(232533, "Breaking Bad Fortune Teller", 2016, original = "绝命卦师") {
        alias("Breaking Bad Fortune Teller")
    }
    tv(105248, "Cyberpunk: Edgerunners", 2022, imdb = "tt12590266") {
        alias("Cyberpunk: Edgerunners II", season = 2)
        alias("Cyberpunk: Edgerunners 2", season = 2)
        season(1, 1..10)
    }
    tv(70523, "Dark", 2017, imdb = "tt5753856") {
        alias("DARK")
        season(1, 1..10)
    }
    tv(196322, "Dark Matter", 2024)
    tv(2883, "Dark Shadows", 1966)
    tv(85937, "Demon Slayer: Kimetsu no Yaiba", 2019, original = "鬼滅の刃", imdb = "tt9335498") {
        alias("鬼灭之刃 锻刀村篇", season = 4)
        alias("鬼灭之刃 游郭篇", season = 3)
        alias("Guardianes de la noche: Kimetsu no Yaiba")
        alias("Guardianes de la noche")
        alias("Demon Slayer - Kimetsu no Yaiba")
        alias("Demon Slayer, Kimetsu no Yaiba")
        alias("Demon Slayer Kimetsu no Yaiba")
        alias("Demon Slayer: Kimetsu no Yaiba")
        alias("Demon Slayer")
        alias("鬼滅の刃 無限列車編", season = 2)
        alias("鬼滅の刃 遊郭編", season = 3)
        alias("鬼滅の刃 刀鍛冶の里編", season = 4)
        alias("鬼滅の刃 竈門炭治郎 立志編", season = 1)
        alias("Kimetsu no Yaiba: Hashira Geiko-hen", season = 5)
        alias("Kimetsu no Yaiba")
        alias("Kimetsu no Yaiba: Katanakaji no Sato-hen", season = 4)
        alias("Kimetsu no Yaiba: Mugen Ressha-hen", season = 2)
        alias("Kimetsu no Yaiba: Yuukaku-hen", season = 3)
        alias("鬼滅の刃 柱稽古編", season = 5)
        alias("Kimetsu no Yaiba Katanakaji no Sato Hen", season = 4)
        alias("鬼滅之刃 竈門炭治郎 立志篇", season = 1)
        alias("鬼滅之刃 遊郭篇", season = 3)
        alias("鬼滅之刃 無限列車篇", season = 2)
        alias("鬼滅之刃 刀匠村篇", season = 4)
        alias("鬼滅之刃 柱訓練篇", season = 5)
        alias("Blade of Demon Destruction")
        alias("Junior High and High School!! Kimetsu Academy Story: Valentine Edition")
        alias("Demon Slayer: Kimetsu no Yaiba Mugen Train Arc", season = 2)
        alias("Demon Slayer: Kimetsu no Yaiba Swordsmith Village Arc", season = 4)
        alias("Demon Slayer: Kimetsu no Yaiba Entertainment District Arc", season = 3)
        alias("Demon Slayer: Kimetsu no Yaiba Hashira Training Arc", season = 5)
        alias("Demon Slayer: Kimetsu no Yaiba", season = 1)
        season(1, 1..26)
        season(2, 1..7)
        season(3, 1..11)
        season(5, 1..8)
    }
    tv(12577, "Devil May Cry", 2007, original = "デビル メイ クライ")
    tv(235930, "Devil May Cry", 2025, imdb = "tt15716776") {
        alias("Devil May Cry")
        season(1, 1..8)
    }
    tv(121, "Doctor Who", 1963)
    tv(57243, "Doctor Who", 2005, imdb = "tt0436992") {
        season(1, 1..13)
        season(4, 1..13)
        episodeGroup(type = 3) {
            part(1, 1 to 1..13)
            part(2, 0 to 2..2, 2 to 1..13)
            part(3, 0 to 4..4, 3 to 1..13)
            part(4, 0 to 7..7, 4 to 1..13, 0 to 9..9, 0 to 13..14, 0 to 16..17)
            part(5, 5 to 1..13)
            part(6, 0 to 34..34, 6 to 1..13)
            part(7, 0 to 47..47, 7 to 1..5, 0 to 66..66, 7 to 6..13, 0 to 83..84)
            part(8, 8 to 1..12)
            part(9, 0 to 142..142, 9 to 1..12, 0 to 148..148)
            part(10, 0 to 149..149, 10 to 1..12, 0 to 154..154)
            part(11, 11 to 1..10)
            part(12, 0 to 156..156, 12 to 1..10, 0 to 158..158)
            part(13, 13 to 1..6, 0 to 208..210)
        }
        episodeGroup(type = 3) {
            part(1, 1 to 1..13)
            part(2, 0 to 2..2, 2 to 1..13)
            part(3, 0 to 4..4, 3 to 1..13)
            part(4, 0 to 7..7, 4 to 1..13, 0 to 9..9, 0 to 13..14, 0 to 16..17)
            part(5, 5 to 1..13)
            part(6, 0 to 34..34, 6 to 1..13)
            part(7, 0 to 47..47, 7 to 1..5, 0 to 66..66, 7 to 6..13, 0 to 83..84)
            part(8, 8 to 1..12, 0 to 142..142)
            part(9, 9 to 1..12, 0 to 148..148)
            part(10, 0 to 149..149, 10 to 1..12, 0 to 154..154)
            part(11, 11 to 1..10, 0 to 156..156)
            part(12, 12 to 1..10, 0 to 158..158)
            part(13, 13 to 1..6, 0 to 208..210)
            part(0, 0 to 1..1, 0 to 3..3, 0 to 5..6, 0 to 8..8, 0 to 10..12, 0 to 15..15, 0 to 18..33, 0 to 35..46, 0 to 48..65, 0 to 67..82, 0 to 85..103, 0 to 115..141, 0 to 143..147, 0 to 150..153, 0 to 155..155, 0 to 157..157, 0 to 159..207)
        }
        episodeGroup(type = 4) {
            part(1, 1 to 1..13, 0 to 2..2)
            part(2, 2 to 1..13, 0 to 4..4)
            part(3, 3 to 1..13, 0 to 7..7)
            part(4, 4 to 1..13, 0 to 9..9, 0 to 13..14, 0 to 16..17)
            part(5, 5 to 1..13, 0 to 34..34)
            part(6, 6 to 1..13, 0 to 47..47)
            part(7, 7 to 1..5, 0 to 66..66, 7 to 6..13, 0 to 83..84)
            part(8, 8 to 1..12, 0 to 142..142)
            part(9, 9 to 1..12, 0 to 148..149)
            part(10, 10 to 1..12, 0 to 154..154)
            part(11, 11 to 1..10, 0 to 156..156)
            part(12, 12 to 1..10, 0 to 158..158)
            part(13, 13 to 1..6, 0 to 208..210)
        }
        episodeGroup(type = 7) {
            part(1, 1 to 1..13, 0 to 1..3, 0 to 6..6)
            part(2, 0 to 19..19, 2 to 1..1, 0 to 20..20, 2 to 2..2, 0 to 21..21, 2 to 3..3, 0 to 22..22, 2 to 4..4, 0 to 23..23, 2 to 5..5, 0 to 24..24, 2 to 6..6, 0 to 25..25, 2 to 7..7, 0 to 26..26, 2 to 8..8, 0 to 27..27, 2 to 9..9, 0 to 28..28, 2 to 10..10, 0 to 29..29, 2 to 11..11, 0 to 30..30, 2 to 12..12, 0 to 31..31, 2 to 13..13, 0 to 4..4, 0 to 35..36)
            part(3, 3 to 1..6, 0 to 5..5, 3 to 7..13, 0 to 7..7)
            part(4, 4 to 1..13, 0 to 9..9, 0 to 13..17)
            part(5, 5 to 1..13, 0 to 34..34)
            part(6, 6 to 1..13, 0 to 47..47)
            part(7, 7 to 1..5, 0 to 66..66, 7 to 6..13, 0 to 78..78, 0 to 82..84)
            part(8, 0 to 127..128, 0 to 145..145, 8 to 1..1, 0 to 129..130, 8 to 2..2, 0 to 131..131, 8 to 3..3, 0 to 132..132, 8 to 4..4, 0 to 133..133, 8 to 5..5, 0 to 134..134, 8 to 6..6, 0 to 135..135, 8 to 7..7, 0 to 136..136, 8 to 8..8, 0 to 137..137, 8 to 9..9, 0 to 138..138, 8 to 10..10, 0 to 139..139, 8 to 11..11, 0 to 140..140, 8 to 12..12, 0 to 142..143)
            part(9, 0 to 146..147, 9 to 1..12, 0 to 148..148, 0 to 144..144, 0 to 149..150)
            part(10, 10 to 1..12, 0 to 154..154)
            part(11, 0 to 153..153, 11 to 1..10, 0 to 156..156)
            part(12, 12 to 1..10, 0 to 158..158)
            part(0, 0 to 8..8, 0 to 10..12, 0 to 32..33, 0 to 38..38, 0 to 81..81, 0 to 87..88, 0 to 90..92, 0 to 94..97, 0 to 101..101, 0 to 103..103, 0 to 115..126, 0 to 141..141, 0 to 151..152, 0 to 157..157)
            part(13, 13 to 1..6, 0 to 208..210)
        }
    }
    tv(239770, "Doctor Who", 2024)
    tv(317641, "Doctor Who", null)
    tv(1056, "Doctor Who Confidential", 2005)
    tv(61643, "Doctor Who Extra", 2014)
    tv(129387, "Doctor Who: Dreamland", 2009)
    tv(16576, "Don Dracula", 1982, original = "ドン・ドラキュラ")
    tv(58928, "Dracula", 2013)
    tv(86850, "Dracula", 2020)
    tv(194814, "Dracula", 2021, original = "دراکولا")
    tv(219562, "Dracula", 1999, original = "Drácula")
    tv(90228, "Dune: Prophecy", 2024) {
        alias("Dune Prophecy")
        alias("Duna: A Profecia")
        alias("Dune : Prophecy")
        alias("Dune - Prophecy")
    }
    tv(42410, "Durarara!!", 2010, original = "デュラララ!!", imdb = "tt1584000") {
        alias("Durarara!!")
        alias("Durarara!!x2 Ketsu")
        alias("Durarara!!x2 Shou")
        alias("Durarara!!x2 Ten")
        season(1, 1..24)
    }
    tv(68746, "EBS SPACE", 2004, original = "EBS 스페이스 공감")
    tv(116501, "Escape The Matrix", 2020)
    tv(31911, "Fullmetal Alchemist: Brotherhood", 2009, original = "鋼の錬金術師 FULLMETAL ALCHEMIST", imdb = "tt1355642") {
        alias("Fullmetal Alchemist: Brotherhood")
        alias("Fullmetal Alchemist Brotherhood")
        alias("Full Metal Alchemist: Brotherhood")
        season(1, 1..64)
    }
    tv(63663, "Gate", 2015, original = "ゲート 自衛隊 彼の地にて、斯く戦えり", imdb = "tt4958580") {
        alias("Gate")
        season(1, 1..24)
        episodeGroup(type = 6) {
            part(1, 1 to 1..12)
            part(2, 1 to 13..24)
        }
    }
    tv(86973, "HEAT", 2015)
    tv(230529, "HEAT", 2023)
    tv(252554, "Heat", 2025, original = "Жар")
    tv(100334, "Hey You! What If...", 2020)
    tv(19163, "Internal Affairs", 2009, original = "Força-Tarefa")
    tv(212171, "Interstellar Ella", 2022)
    tv(198237, "Interstellar Wanderer", 2022, original = "星际一游")
    tv(328571, "Kang vs the Avengers", 2023)
    tv(2332, "L.A. Heat", 1999)
    tv(318918, "LEGO ONE PIECE", 2026)
    tv(42467, "Little Dracula", 1991)
    tv(3051, "Lost in Space", 1965)
    tv(333622, "Magic Repo Man: Dumped by My Party, I'll Cash In with a Cute Support Fairy to Become the Strongest!", 2026, original = "貸した魔力は【リボ払い】で強制徴収", imdb = "tt43650419") {
        alias("Kashita Maryoku wa Ribo Barai de Kyousei Choushuu")
        alias("Kashita Maryoku wa \"Ribobarai\" de Kyousei Choushuu ~Youzumi to Party Tsuihousareta Ore wa, Kawaii Support Yousei to Issho ni Toritateta Maryoku wo Unyou shite Saikyou wo Mezasu.~")
        alias("Kashita Maryoku wa \"Ribobarai\" de Kyousei Choushuu")
        season(1, 1..12)
    }
    tv(310921, "Monster Eater", 2026, original = "魔物喰らいの冒険者～俺だけ魔物を喰らって強くなる～", imdb = "tt39673631") {
        alias("Mamonogurai no Boukensha: Ore dake Mamono wo Kuratte Tsuyoku Naru")
        alias("Mamono Kurai no Boukensha: Ore Dake Mamono o Kuratte Tsuyoku Naru")
        alias("Monster Eater Adventurer")
        season(1, 1..12)
    }
    tv(90461, "Monsters at Work", 2021)
    tv(199001, "My Life with the Walter Boys", 2023)
    tv(3180, "Night Heat", 1985)
    tv(37854, "One Piece", 1999, original = "ワンピース", imdb = "tt0388629") {
        alias("One Piece")
        alias("ONE PIECE")
        alias("SPECIAL EDITED VERSION『ONE PIECE』魚人島編")
        alias("ワンピース ドレスローザ編")
        season(1, 1..61)
        season(21, 892..1088)
        episodeGroup(type = 1) {
            part(0, 0 to 2..5, 0 to 7..9, 0 to 1..1, 0 to 10..39)
            part(1, 1 to 1..8)
            part(2, 1 to 9..30)
            part(3, 1 to 31..47)
            part(4, 1 to 48..60)
            part(5, 1 to 61..61, 2 to 62..69)
            part(6, 2 to 70..77, 3 to 78..91)
            part(7, 4 to 92..130)
            part(8, 5 to 131..143)
            part(9, 6 to 144..195)
            part(10, 7 to 196..226)
            part(11, 7 to 227..228, 8 to 229..263, 9 to 264..325)
            part(12, 9 to 326..336, 10 to 337..381)
            part(13, 11 to 382..407, 12 to 408..421, 13 to 422..481)
            part(14, 13 to 482..516)
            part(15, 13 to 517..522, 14 to 523..578)
            part(16, 14 to 579..580, 15 to 581..589, 15 to 591..628)
            part(17, 15 to 629..642, 16 to 643..692, 17 to 693..746)
            part(18, 17 to 747..748, 18 to 749..779)
            part(19, 18 to 780..803, 19 to 804..877)
            part(20, 20 to 878..891)
            part(21, 21 to 892..1085)
            part(22, 21 to 1086..1088, 22 to 1089..1155)
            part(23, 23 to 1156..1180)
        }
        episodeGroup(type = 1) {
            part(1, 1 to 1..61)
            part(2, 2 to 62..77)
            part(3, 3 to 78..91, 4 to 92..92)
            part(4, 4 to 93..130)
            part(5, 5 to 131..143)
            part(6, 6 to 144..195)
            part(7, 7 to 196..228)
            part(8, 8 to 229..263)
            part(9, 9 to 264..336)
            part(10, 10 to 337..381)
            part(11, 11 to 382..407)
            part(12, 12 to 408..421)
            part(13, 13 to 422..456)
            part(14, 13 to 457..516)
            part(15, 13 to 517..522, 14 to 523..578)
            part(16, 14 to 579..580, 15 to 581..628)
            part(17, 15 to 629..642, 16 to 643..692, 17 to 693..746)
            part(18, 17 to 747..748, 18 to 749..782)
            part(19, 18 to 783..803, 19 to 804..877, 20 to 878..891)
            part(20, 21 to 892..1088)
            part(21, 22 to 1089..1155)
            part(0, 0 to 17..39)
            part(22, 23 to 1156..1180)
        }
        episodeGroup(type = 1) {
            part(1, 1 to 1..47)
            part(2, 1 to 48..61, 2 to 62..77, 3 to 78..91, 4 to 92..115)
            part(3, 4 to 116..130, 5 to 131..143, 6 to 144..168)
            part(4, 6 to 169..195, 7 to 196..206)
            part(5, 7 to 207..228, 8 to 229..263)
            part(6, 9 to 264..278)
            part(7, 9 to 279..283)
            part(8, 9 to 284..325)
            part(9, 9 to 326..336, 10 to 337..372)
            part(10, 10 to 373..381, 11 to 382..394)
            part(11, 11 to 395..407, 12 to 408..421, 13 to 422..425)
            part(12, 13 to 426..458)
            part(13, 13 to 459..492)
            part(14, 13 to 493..516)
            part(15, 13 to 517..522, 14 to 523..580, 15 to 581..590)
            part(16, 15 to 591..628)
            part(17, 15 to 629..642, 16 to 643..686)
            part(18, 16 to 687..692, 17 to 693..746)
            part(19, 17 to 747..748, 18 to 749..803, 19 to 804..806)
            part(20, 19 to 807..855)
            part(21, 19 to 856..877, 20 to 878..891)
            part(22, 21 to 892..934)
            part(23, 21 to 935..999)
            part(26, 21 to 1005..1027)
            part(29, 21 to 1074..1088)
            part(30, 22 to 1089..1122)
            part(31, 22 to 1123..1138)
            part(32, 22 to 1139..1155)
            part(33, 23 to 1156..1168)
            part(27, 21 to 1028..1030)
            part(28, 21 to 1031..1073)
            part(24, 21 to 1000..1000)
            part(25, 21 to 1001..1004)
        }
        episodeGroup(type = 2) {
            part(1, 1 to 1..4, 1 to 6..6, 1 to 5..5, 1 to 7..14, 1 to 19..19, 1 to 15..15, 1 to 17..17, 1 to 16..16, 1 to 18..18, 1 to 20..61, 2 to 62..77, 3 to 78..91, 4 to 92..130, 5 to 131..143, 6 to 144..195, 7 to 196..228, 8 to 229..263, 9 to 264..336, 10 to 337..381, 11 to 382..407, 12 to 408..421, 13 to 422..522, 14 to 523..580, 15 to 581..642, 16 to 643..692, 17 to 693..748, 18 to 749..803, 19 to 804..877, 20 to 878..891, 21 to 892..1088, 22 to 1089..1131, 22 to 1133..1133, 22 to 1132..1132, 22 to 1134..1155, 23 to 1156..1180)
        }
        episodeGroup(type = 2) {
            part(0, 0 to 1..5, 0 to 13..13, 0 to 6..12, 0 to 14..39)
            part(1, 1 to 1..61, 2 to 62..77, 3 to 78..91, 4 to 92..130, 5 to 131..143, 6 to 144..195, 7 to 196..228, 8 to 229..263, 9 to 264..336, 10 to 337..381, 11 to 382..407, 12 to 408..421, 13 to 422..522, 14 to 523..580, 15 to 581..642, 16 to 643..692, 17 to 693..748, 18 to 749..803, 19 to 804..877, 20 to 878..891, 21 to 892..1088, 22 to 1089..1155, 23 to 1156..1180)
        }
        episodeGroup(type = 4) {
            part(1, 1 to 1..61)
            part(2, 2 to 62..77)
            part(3, 3 to 78..91, 4 to 92..92)
            part(4, 4 to 93..130)
            part(5, 5 to 131..143)
            part(0, 0 to 1..5, 0 to 13..13, 0 to 6..12, 0 to 14..16)
            part(6, 6 to 144..173)
            part(8, 7 to 196..206)
            part(7, 6 to 174..195)
            part(9, 7 to 207..228)
            part(10, 8 to 229..263)
            part(11, 9 to 264..284)
            part(12, 9 to 285..306)
            part(13, 9 to 307..325)
            part(14, 9 to 326..336)
            part(15, 10 to 337..381)
            part(16, 11 to 382..405)
            part(17, 11 to 406..407)
            part(18, 12 to 408..421)
            part(19, 13 to 422..458)
            part(20, 13 to 459..491, 13 to 493..516)
            part(35, 22 to 1089..1122)
            part(22, 14 to 575..580, 15 to 581..628)
            part(21, 13 to 517..522, 14 to 523..541, 14 to 543..574)
            part(23, 15 to 629..642, 16 to 643..692, 17 to 693..746)
            part(24, 17 to 747..748, 18 to 749..750)
            part(36, 22 to 1123..1155)
            part(25, 18 to 751..760)
            part(26, 18 to 761..771)
            part(27, 18 to 772..782)
            part(28, 18 to 783..796)
            part(29, 18 to 797..803, 19 to 804..809)
            part(30, 19 to 810..822)
            part(36, 23 to 1156..1171)
            part(31, 19 to 823..835)
            part(32, 19 to 836..849)
            part(33, 19 to 850..863)
            part(34, 19 to 864..877)
        }
        episodeGroup(type = 4) {
            part(1, 1 to 1..53)
            part(2, 1 to 54..61, 2 to 62..77, 3 to 78..91, 4 to 92..130, 5 to 131..143)
            part(3, 6 to 144..195, 7 to 196..206)
            part(4, 7 to 207..228, 8 to 229..263)
            part(5, 9 to 264..336)
            part(6, 10 to 337..381, 11 to 382..384)
            part(7, 11 to 385..407, 12 to 408..421, 13 to 422..456)
            part(8, 13 to 457..491, 13 to 493..516)
            part(9, 13 to 517..522, 14 to 523..541, 14 to 543..574)
            part(10, 14 to 575..580, 15 to 581..589, 15 to 591..628)
            part(11, 15 to 629..642, 16 to 643..692, 17 to 693..746)
            part(12, 17 to 747..748, 18 to 749..770)
        }
        episodeGroup(type = 6) {
            part(1, 1 to 1..61)
            part(2, 2 to 62..77)
            part(0, 0 to 1..5, 0 to 13..13, 0 to 6..12, 0 to 14..39)
            part(3, 3 to 78..91)
            part(4, 4 to 92..130)
            part(5, 5 to 131..143)
            part(6, 6 to 144..195)
            part(7, 7 to 196..228)
            part(8, 8 to 229..263)
            part(9, 9 to 264..325)
            part(10, 9 to 326..336, 10 to 337..381)
            part(11, 11 to 382..407)
            part(12, 12 to 408..421)
            part(14, 13 to 459..516)
            part(15, 13 to 517..522, 14 to 523..574)
            part(16, 14 to 575..580, 15 to 581..628)
            part(17, 15 to 629..642, 16 to 643..692, 17 to 693..746)
            part(18, 17 to 747..748, 18 to 749..779)
            part(19, 18 to 780..803, 19 to 804..877)
            part(20, 20 to 878..891)
            part(13, 13 to 422..458)
            part(21, 21 to 892..1085)
            part(22, 21 to 1086..1088, 22 to 1089..1122)
            part(23, 22 to 1123..1155)
            part(24, 23 to 1156..1163)
        }
    }
    tv(111110, "ONE PIECE", 2023)
    tv(8771, "Oppenheimer", 1980)
    tv(297315, "Oppenheimer und die Bombe", 2025)
    tv(64196, "Overlord", 2015, original = "オーバーロード", imdb = "tt4869896") {
        alias("OVERLORD")
        alias("Ōbārōdo")
        alias("Overlord Ple Ple Pleiades")
        alias("Soushuuhen Overlord")
        alias("Overlord")
        alias("Overlord 3", season = 3)
        alias("Overlord II", season = 2)
        alias("Overlord III", season = 3)
        alias("Overlord 2", season = 2)
        alias("Overlord IV", season = 4)
    }
    tv(114868, "Record of Ragnarok", 2021, original = "終末のワルキューレ", imdb = "tt13676344") {
        alias("Shūmatsu no Walküre")
        alias("Shuumatsu no Valkyrie")
        alias("Shūmatsu no Valkyrie")
        alias("Shuumatsu no Walküre")
        alias("Record of Ragnarok")
        alias("Walkirie kresu dziejów - Record of Ragnarok")
        alias("Record of Ragnarok II", season = 2)
        alias("Record of Ragnarok III", season = 3)
        season(1, 1..12)
        season(2, 1..15)
        season(3, 1..15)
    }
    tv(11129, "S.W.A.T.", 1975)
    tv(71790, "S.W.A.T.", 2017)
    tv(292742, "S.W.A.T. Exiles", 2026) {
        alias("S W A T Exiles")
    }
    tv(134, "Space: 1999", 1975, imdb = "tt0072564") {
        season(1, 1..24)
    }
    tv(580, "Star Trek: Deep Space Nine", 1993) {
        alias("Star Trek: Espacio Profundo Nueve")
        alias("Star Trek: DS9")
    }
    tv(60554, "Star Wars Rebels", 2014) {
        alias("Star Wars : Rebels")
        alias("Star Wars: Rebels")
    }
    tv(4194, "Star Wars: The Clone Wars", 2008) {
        alias("Star Wars: Klonové Vojny")
        alias("Star Wars: The Clone Wars—The Lost Missions", season = 6)
        alias("Star Wars: The Clone Wars—Battle Lines", season = 4)
        alias("Star Wars: The Clone Wars—Secrets Revealed", season = 3)
        alias("Star Wars: The Clone Wars—Rise of the Bounty Hunters", season = 2)
        alias("Star Wars: The Clone Wars—The Final Season", season = 7)
        alias("The Clone Wars")
    }
    tv(114478, "Star Wars: Visions", 2021)
    tv(289324, "Star Wars: Visions Presents - The Ninth Jedi", 2026, original = "スター・ウォーズ：ビジョンズ／九人目のジェダイ", imdb = "tt43337681") {
        season(1, 1..8)
    }
    tv(42509, "Steins;Gate", 2011) {
        alias("Steins;Gate")
        alias("Steins_Gate")
        alias("Steins Gate")
    }
    tv(311882, "Tale Gate", 2026)
    tv(2473, "The Avengers", 1961)
    tv(33623, "The Avengers: Earth's Mightiest Heroes", 2010) {
        alias("Os Vingadores")
        alias("The Avengers: Nejmocnější hrdinové světa")
    }
    tv(256128, "The Best Thing", 2025, original = "爱你")
    tv(28172, "The Boys", 1993)
    tv(76479, "The Boys", 2019, imdb = "tt1190634") {
        alias("Băieții", season = 1)
        season(3, 1..8)
    }
    tv(152483, "The Boys Presents: Diabolical", 2022) {
        alias("The Boys Sunar: Şeytani")
        alias("The Boys: Diabolical")
    }
    tv(112945, "The Hardy Boys", 2021)
    tv(201289, "The Last Thing He Told Me", 2023)
    tv(10360, "The New Avengers", 1976)
    tv(241709, "THE ONE PIECE", null)
    tv(9621, "The Real Thing", 1980)
    tv(125714, "The Thing About Pam", 2022)
    tv(197848, "The Unwanted Undead Adventurer", 2024, original = "望まぬ不死の冒険者", imdb = "tt30177477") {
        alias("Nozomanu Fushi no Boukensha")
        alias("Nozomanu Fushi no Bōkensha")
        season(1, 1..12)
    }
    tv(454, "Totally Doctor Who", 2006)
    tv(12306, "Tropical Heat", 1991)
    tv(81940, "WHAT / IF", 2019)
    tv(66565, "What If", 2016, original = "Tänk om")
    tv(203255, "What If", 2018)
    tv(235614, "What If", 2024, original = "生活在别处的我")
    tv(286433, "What If", 2025, original = "三命")
    tv(91363, "What If...?", 2021, imdb = "tt10168312") {
        alias("What If... ? de Marvel Animation", season = 3)
        alias("What If...")
        alias("What If")
        alias("Marvel Animation's What If...?", season = 3)
        // Names as long as the real ones, so the nine-episode file's name has to be cut to 255 bytes.
        season(1, 1..9) { "What If... Episode $it Went Another Way Entirely" }
    }
    tv(50116, "What If?", 2011, original = "Wat als?")
    tv(65621, "What if?", 2013, original = "Et si?")
    tv(155386, "What If?", 2019, original = "ماذا لو؟")
    tv(312815, "what if?", 2020, original = "ماذا إذا؟")
    tv(245842, "Wistoria: Wand and Sword", 2024, original = "杖と剣のウィストリア", imdb = "tt31889371") {
        alias("Wistoria: Varinha e Espada")
        alias("Wistoria: Varinhas e Espada")
        alias("Tsue to Tsurugi no Wistoria")
        alias("Wistoria's Wand and Sword")
        alias("The Wistoria of Staves and Swords")
        season(2, 1..12)
        episodeGroup(type = 6) {
            part(1, 1 to 1..12)
            part(2, 2 to 1..2)
        }
        episodeGroup(type = 7) {
            part(1, 1 to 1..12)
            part(2, 2 to 1..1)
        }
    }
    tv(30188, "WWE Heat", 1998)
    tv(4574, "X-Men", 1992)
    tv(43146, "X-Men", 2011, original = "エックスメン")
    tv(138502, "X-Men '97", 2024) {
        alias("X-Men 97")
    }
    tv(668, "X-Men: Evolution", 2000) {
        alias("X-Men: Začátek")
    }
    tv(3720, "Young Dracula", 2006)

    search("cyberpunk edgerunners", "tv/105248")
    search("devil may cry", "tv/235930", "tv/12577", "movie/1260450", "movie/339102", "movie/1618462", "movie/663056", "movie/663364")
    search("fullmetal alchemist brotherhood", "tv/31911", "movie/1467973", "movie/1412074")
    search("gate", "tv/42509", "movie/622", "tv/63663", "tv/311882", "movie/1103694", "movie/40729")
    search("kashita maryoku wa revo barai de kyousei choushuu", "tv/333622")
    search("kimetsu no yaiba", "tv/85937", "movie/1311031", "movie/1141171")
    search("kimetsu no yaiba hashira geiko hen", "tv/85937")
    search("kimetsu no yaiba yuukaku hen", "tv/85937")
    search("kimetsu no yaiba mugen ressha hen tv", "tv/85937")
    search("mamonogurai no boukensha ore dake mamono wo kuratte tsuyoku naru", "tv/310921")
    search("nozomanu fushi no boukensha", "tv/197848")
    search("record of ragnarok", "tv/114868")
    search("record of ragnarok 2")
    search("shuumatsu no walkure", "tv/114868")
    search("shuumatsu no walkure iii", "tv/114868")
    search("star wars visions presents the ninth jedi", "tv/289324")
    search("the lord of the rings the war of the rohirrim", "movie/839033")
    search("tsue to tsurugi no wistoria", "tv/245842")
    search("and justice for all", "movie/17443", "tv/324407", "movie/682407", "movie/790862", "movie/1357565")
    search("ant man and the wasp", "movie/363088", "movie/640146", "movie/1127361")
    search("barbie", "movie/346698", "tv/234586", "movie/15016", "movie/18198", "movie/139588")
    search("batman begins", "movie/272", "movie/498471")
    search("blade runner", "movie/335984", "movie/78", "tv/171802", "tv/84553", "movie/833898", "movie/57656", "movie/1527118")
    search("blade runner 2049", "movie/335984")
    search("breaking bad", "tv/1396", "movie/559969", "tv/232533", "movie/1309626", "movie/635602", "movie/1399762")
    search("dark", "tv/2883", "tv/70523", "tv/196322", "tv/108847")
    search("doctor who", "tv/57243", "tv/121", "tv/239770", "tv/1056", "tv/454", "movie/15691", "tv/129387", "tv/61643", "tv/317641", "movie/434605")
    search("dracula", "movie/1246049", "tv/86850", "tv/58928", "tv/3720", "movie/49017", "movie/138", "tv/42467", "tv/219562", "movie/10577", "movie/11868", "tv/16576", "movie/33521", "movie/1655673", "tv/194814")
    searchMovies("dracula", 1974, "movie/39043", "movie/86889", "movie/25944", "movie/153281")
    search("dune", "movie/438631", "movie/1170608", "tv/90228", "movie/693134", "movie/841", "movie/697620", "movie/271683", "movie/627150")
    search("durarara!!x2 ten", "tv/42410")
    search("el camino a breaking bad movie", "movie/559969", "movie/934809")
    search("heat", "tv/30188", "movie/949", "tv/3180", "tv/12306", "tv/86973", "tv/252554", "tv/230529", "tv/2332")
    search("inception", "movie/27205", "movie/542438", "movie/1359046", "movie/64956", "movie/613092", "movie/250845", "movie/1644507")
    search("internal affairs", "tv/19163", "movie/11060", "movie/643963", "movie/836688", "movie/793067", "movie/545434", "movie/1209629", "movie/1388175", "movie/862079")
    search("interstellar", "movie/157336", "tv/212171", "movie/529107", "movie/398188", "movie/649745", "movie/336592", "movie/1443305", "tv/198237", "movie/1391726")
    search("monsters inc", "movie/585", "movie/1647601", "movie/1365124", "movie/339026", "tv/90461")
    search("one piece", "tv/37854", "tv/111110", "tv/318918", "movie/1659389", "movie/900667", "movie/374205", "movie/176983", "tv/241709")
    search("oppenheimer", "movie/872585", "tv/8771", "movie/1143770", "movie/1521971", "movie/1634345", "tv/297315", "movie/1156529", "movie/1230570")
    search("s w a t", "tv/71790", "tv/292742", "tv/11129", "movie/9257", "movie/54445", "movie/461053", "movie/559937", "movie/744089")
    search("space", "tv/134", "tv/580", "tv/3051", "movie/2300", "tv/68746")
    search("space 1999", "tv/134", "movie/542499", "movie/1775853", "movie/1599287", "movie/1768685")
    search("spider man no way home", "movie/634649", "movie/961651")
    search("star wars", "movie/11", "tv/4194", "tv/60554", "tv/114478")
    search("the boys", "tv/76479", "tv/112945", "tv/199001", "tv/152483", "movie/1547", "tv/28172")
    search("the avengers", "movie/24428", "tv/2473", "tv/10360", "movie/9320", "tv/33623", "movie/1750432", "movie/14609", "movie/432413", "movie/109088", "movie/275663", "movie/685795", "tv/328571")
    search("the matrix", "movie/603", "movie/604", "movie/605", "movie/624860", "movie/684431", "movie/344225", "movie/1386216", "tv/116501", "movie/1502836", "movie/1268926")
    search("the thing", "movie/1091", "movie/60935", "tv/201289", "movie/8992", "tv/256128", "tv/125714", "movie/11812", "tv/9621")
    search("what if", "tv/91363", "tv/81940", "movie/212716", "tv/50116", "tv/286433", "tv/235614", "movie/42950", "movie/792494", "movie/1352212", "tv/203255", "movie/1005011", "tv/66565", "movie/1390359", "movie/822188", "movie/1773380", "tv/65621", "movie/264351", "tv/155386", "tv/312815", "tv/100334")
    search("wonder woman 1984", "movie/464052")
    search("x men", "tv/4574", "tv/138502", "tv/668", "movie/246655", "movie/127585", "tv/43146", "movie/36657")
}
