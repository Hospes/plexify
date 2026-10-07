package io.github.hospes.plexify.core

import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class RelativePathTest {

    private val episode = arrayOf("Breaking Bad (2008) [tmdbid-1396]", "Season 01", "Breaking Bad (2008) - S01E01 - Pilot.mkv")
    private val expected = Path(episode[0], *episode.copyOfRange(1, episode.size)).toString()

    @Test
    fun `strips a relative destination root`() {
        val library = Path("library")
        assertEquals(expected, Path(library, *episode).relativeTo(library))
    }

    @Test
    fun `strips an absolute destination root`() {
        val library = Path("/mnt/library")
        assertEquals(expected, Path(library, *episode).relativeTo(library))
    }

    @Test
    fun `strips a destination root given with a trailing separator`() {
        val library = Path("library")
        assertEquals(expected, Path(library, *episode).relativeTo(Path("library/")))
    }

    @Test
    fun `strips a windows destination root with backslashes`() {
        val target = Path("""D:\Movies\Inception (2010)\Inception (2010).mkv""")
        assertEquals("""Inception (2010)\Inception (2010).mkv""", target.relativeTo(Path("""D:\Movies""")))
        assertEquals("""Inception (2010)\Inception (2010).mkv""", target.relativeTo(Path("""D:\Movies\""")))
    }

    @Test
    fun `keeps the full path when it is not under the root`() {
        val library = Path("library")
        val sibling = Path("library-old", "Movie.mkv")
        assertEquals(sibling.toString(), sibling.relativeTo(library))
        val elsewhere = Path("other", "Movie.mkv")
        assertEquals(elsewhere.toString(), elsewhere.relativeTo(library))
    }

    @Test
    fun `keeps the full path when it is the root itself`() {
        val library = Path("library")
        assertEquals(library.toString(), library.relativeTo(library))
    }
}
