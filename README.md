# Plexify

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Kotlin Version](https://img.shields.io/badge/Kotlin-2.4.20-blue.svg?logo=kotlin)](https://kotlinlang.org)
[![CI](https://github.com/Hospes/plexify/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/Hospes/plexify/actions/workflows/ci.yml)
[![Latest release](https://img.shields.io/github/v/release/Hospes/plexify)](https://github.com/Hospes/plexify/releases/latest)

Plexify is a powerful, cross-platform command-line tool designed to automatically organize your movie and TV show collections into a clean, structured library, perfect for media servers like Plex, Jellyfin, and Emby. It intelligently parses filenames, fetches accurate metadata from multiple online sources, and renames/organizes your files according to best practices.

## ✨ Key Features

-   **Automatic Media Organization**: Processes individual files or entire directories, sorting them into a clean library structure.
-   **Advanced Filename Parsing**: Intelligently extracts title, year, season, episode, resolution, and quality from even the most complex filenames.
-   **TMDB Metadata**: Looks up and verifies media information on TMDB to find the most accurate match.
-   **Alias-Aware Matching**: Also matches releases named with original-language or alternative titles (e.g. romaji anime titles like *Hametsu no Oukoku* → *The Kingdoms of Ruin*), while the library itself is named with the canonical title.
-   **Split-Season Anime**: Releases that split a season TMDB keeps whole (e.g. *Gate* as two 12-episode seasons) are mapped through TMDB episode groups onto TMDB's own numbering.
-   **Intelligent Consolidation**: Compares search results from all providers, scores them, and selects the best "golden record" for your media.
-   **Manual Overrides**: Force the title, season, episode offset, or year (`-t`, `-s`, `--episode-offset`, `-y`) when a release is too cryptic to parse — the season hidden in a folder named `2`, a remake matching the wrong year, and similar cases.
-   **Customizable Naming**: Comes with pre-configured, optimal naming templates for **Plex** and **Jellyfin**, or you can define your own powerful custom templates.
-   **Flexible File Operations**: Choose to either **move** your files or create **hardlinks**, preserving your original files for seeding or backup. Existing files in the library are never overwritten unless you pass `--overwrite`.
-   **Dry Run Mode**: Preview every rename and the final library layout with `--test` before a single file is touched.
-   **Readable Output**: One line per organized file with a final summary by default; a `--verbose` flag exposes the full pipeline (parsing, cache, providers, match scoring) for troubleshooting.
-   **Cross-Platform**: Built with Kotlin Multiplatform to run natively on **Linux** and **Windows**.

## ⚙️ How It Works

Plexify follows a simple but effective pipeline to organize your media:

1.  **Parse**: It deconstructs the original filename to make an educated guess about the media's title, year, season, episode, and other details.
2.  **Search**: It queries TMDB for metadata based on the parsed information, pulling in original-language and alternative titles when the primary title alone isn't convincing.
3.  **Consolidate**: It compares results from all sources, scores them against every title a candidate is known by, and selects the best possible match to create a canonical record. Show and season lookups are cached per run, so a whole season costs one search and one season fetch.
4.  **Format**: It uses the selected naming strategy (e.g., Jellyfin) and the canonical record to construct the ideal new file and folder path.
5.  **Organize**: It creates the necessary directories and moves or hardlinks the file to its final, clean destination.

## 🚀 Getting Started

### Download a release

Pre-built binaries for **Windows**, **Linux x64** and **Linux arm64** are attached to every [GitHub release](https://github.com/Hospes/plexify/releases). Download the archive for your platform, extract it, and put `plexify` (or `plexify.exe`) somewhere on your `PATH`.

Release binaries work out of the box: they include a TMDB key, so there is nothing to configure.

### TMDB API key

Plexify looks up metadata on [TMDB](https://www.themoviedb.org/). It uses the first credentials it finds, in this order:

1.  `--tmdb-access-token` / `--tmdb-api-key` on the command line
2.  The `TMDB_API_ACCESS_TOKEN` / `TMDB_API_KEY` environment variables
3.  The key built into release binaries

The built-in key is shared by everyone who uses a release, so it can hit TMDB's rate limit or be revoked. If Plexify reports that TMDB rejected the built-in key, or you organize large libraries regularly, use your own. It's free:

1.  Create a TMDB account and request an API key at [themoviedb.org/settings/api](https://www.themoviedb.org/settings/api).
2.  Copy the **API Read Access Token** (or the shorter **API Key**).
3.  Set it in your environment, for example:

    ```bash
    export TMDB_API_ACCESS_TOKEN="your_read_access_token"
    ```

    On Windows (PowerShell): `setx TMDB_API_ACCESS_TOKEN "your_read_access_token"`, then open a new terminal.

Either credential works on its own; when both are set, the access token is used.

### Build from source

Requires Git and JDK 21.

```bash
git clone https://github.com/Hospes/plexify.git
cd plexify
```

To build a key into your binary, create a `local.properties` file in the project root (it is git-ignored). Without one, your build has no built-in key and needs the environment variable at runtime instead.

```properties
TMDB_API_ACCESS_TOKEN=your_read_access_token
# or
# TMDB_API_KEY=your_api_key
```

> **Note:** At build time the keys are read in this order: environment variables > `gradle.properties` > `local.properties`.

Build the native executable with the Gradle wrapper:

```bash
./gradlew linkReleaseExecutableLinuxX64       # Linux x64
./gradlew linkReleaseExecutableLinuxArm64     # Linux arm64
./gradlew.bat linkReleaseExecutableWindows    # Windows
```

The executable is written to:
-   Linux x64: `build/bin/linuxX64/releaseExecutable/plexify.kexe`
-   Linux arm64: `build/bin/linuxArm64/releaseExecutable/plexify.kexe`
-   Windows: `build/bin/windows/releaseExecutable/plexify.exe`

> **Note:** On Linux you may need `chmod +x plexify.kexe`, and you can rename it to `plexify` for cleaner use in the terminal.

## 🖥️ Usage

The basic command structure is:

```bash
plexify [OPTIONS] <source...> <destination>
```

### Arguments

-   `<source...>`: One or more source paths. Can be a single media file or a directory containing multiple files.
-   `<destination>`: The root directory where the organized media library will be created.

### Options

| Option                  | Alias | Description                                                                                              | Default    |
| ----------------------- | ----- | -------------------------------------------------------------------------------------------------------- | ---------- |
| `--mode <MODE>`         | `-m`  | Operation mode: `MOVE` or `HARDLINK`.                                                                    | `HARDLINK` |
| `--overwrite`           | `-o`  | Replace a different file already at the target path instead of skipping it. See [Existing files at the target](#existing-files-at-the-target). | `false`    |
| `--test`                |       | Dry run: report what would be organized without touching any files.                                      | `false`    |
| `--template-plex`       | `-tp` | Use the predefined naming template for Plex.                                                             | `false`    |
| `--template-jellyfin`   | `-tj` | Use the predefined naming template for Jellyfin.                                                         | `true`     |
| `--template-custom <T>` | `-tc` | Use a custom naming template. See [Custom Naming Templates](#-custom-naming-templates) for syntax.       | `n/a`      |
| `--title <TITLE>`       | `-t`  | Override the title parsed from filenames. Applies to every file in the run.                              | `n/a`      |
| `--season <N>`          | `-s`  | Override the season number for TV episodes (ignored for movies).                                         | `n/a`      |
| `--episode-offset <N>`  |       | Add N to every parsed episode number (negative subtracts). With `-s`, places a split season, e.g. S2E01 → S01E13. | `n/a`      |
| `--year <YYYY>`         | `-y`  | Override the release year. Acts as a strict filter: candidates with a different year are rejected.       | `n/a`      |
| `--verbose`             |       | Show detailed pipeline logs (parsing, cache, providers, match scoring).                                  | `false`    |
| `--tmdb-access-token <T>` |     | Your TMDB API Read Access Token. Overrides the built-in key. Env: `TMDB_API_ACCESS_TOKEN`.                | built-in   |
| `--tmdb-api-key <KEY>`  |       | Your TMDB API key. Overrides the built-in key. Env: `TMDB_API_KEY`.                                      | built-in   |
| `--version`             | `-v`  | Show the version and exit.                                                                               |            |
| `--help`                | `-h`  | Show help message.                                                                                       |            |

> **Note:** The override options apply to *every* file in the run, so use them when pointing Plexify at a single movie or one show's season folder — not a mixed batch.

### Existing files at the target

By default, Plexify never deletes or overwrites a file that is already at the computed target path, in either mode. Both cases below are counted as *skipped* in the summary, and `--test` reports them the same way.

-   **The target is the file itself** — the source already sits at its target (for example, when Plexify is run over an existing library), or the target is a hardlink to the source. The file is left alone: `= Movie.mkv — already in the library: <path>`.
-   **A different file is at the target** — both files are left alone: `✗ Movie.mkv — target already exists: <path>`. To replace it, run again with `--overwrite` (`-o`), or delete or rename the existing file yourself.

With `--overwrite`, a different file at the target is replaced and counted as *organized*: `✓ Movie.mkv → Movie (2010) (replaced existing file)`. In `HARDLINK` mode the new link is created under a temporary name first and then renamed over the old file, so the old file stays if linking fails. Replacing only removes the library's name for the old file: if it is also hardlinked elsewhere (an old download folder, say), that copy stays on disk. `--overwrite` never applies to the first case: a file that already is its own target is always left alone.

If a hardlink can't be created, the error from the operating system is shown, e.g. when source and destination are on different volumes (hardlinks can't cross volumes or partitions).

### Examples

**1. Organize a downloads folder using hardlinks and the default Jellyfin naming:**

```bash
./plexify "/path/to/downloads" "/path/to/Media Library"
```

**2. Move a single movie and use the Plex naming convention:**

```bash
./plexify --mode MOVE --template-plex "/downloads/The.Matrix.1999.mkv" "/movies"
```

**3. Process multiple source folders at once:**

```bash
./plexify "/torrents/movies" "/torrents/shows" "/path/to/Media Library"
```

**4. Use a custom template for movies:**
This example creates a folder like `The Matrix (1999)` and a file inside named `The Matrix - 1999 [1080p].mkv`.

```bash
./plexify --template-custom "{CleanTitle} ({year})/{CleanTitle} - {year} [{resolution}].{ext}" \
"/downloads/The.Matrix.1999.1080p.mkv" "/movies"
```

**5. Preview a cryptic release with manual overrides (dry run):**
When filenames don't carry enough information — say a season folder just named `2` — force the title and season, and check the result with `--test` first:

```bash
./plexify --test -t "The Kingdoms of Ruin" -s 2 "/anime/SomeShow/2" "/library/Anime"
```

**6. Pin a remake to the right year:**
A remake often shares its title with the original; `--year` rejects candidates from any other year:

```bash
./plexify -y 2011 "/downloads/The.Thing.1080p.mkv" "/movies"
```

**7. Anime split into seasons that TMDB keeps as one:**
TMDB lists *Gate* as a single 24-episode season, while releases ship it as two 12-episode seasons. When a season isn't on TMDB, Plexify looks it up in the show's TMDB episode groups and files it under TMDB's numbering, so `Gate S2 [01]` becomes `S01E13`. If a show has no such group, place the season yourself:

```bash
./plexify -s 1 --episode-offset 12 "/anime/Gate S2" "/library/Anime"
```

## 📝 Custom Naming Templates

You can define your own folder and file structure using the `--template-custom` option. The template string uses a forward slash (`/`) to separate the folder path from the filename.

### Available Placeholders

The following placeholders can be used in your custom templates.

| Placeholder      | Description                                                                  | Example                                 |
| ---------------- | ---------------------------------------------------------------------------- | --------------------------------------- |
| **General**      |                                                                              |                                         |
| `{title}`        | The official title of the movie or show.                                     | `Avatar: The Way of Water`              |
| `{cleantitle}`   | The title with invalid filesystem characters removed.                        | `Avatar The Way of Water`               |
| `{year}`         | The release year of the movie or the first air date year of a show.          | `2022`                                  |
| `{ext}`          | The original file extension.                                                 | `mkv`                                   |
| **Metadata IDs** |                                                                              |                                         |
| `{imdbid}`       | The IMDb ID, looked up on TMDB for the match; empty when TMDB has none.      | `tt1630029`                             |
| `{tmdbid}`       | The TMDb ID (e.g., `76600`).                                                 | `76600`                                 |
| `{tvdbid}`       | The TVDb ID (if available), looked up on TMDB; TV shows only.                | `81189`                                 |
| **TV Shows**     |                                                                              |                                         |
| `{season}`       | The season number.                                                           | `1`                                     |
| `{episode}`      | The episode number.                                                          | `5`                                     |
| `{episodetitle}` | The title of the specific episode.                                           | `The Ride`                              |
| **Parsed Info**  |                                                                              |                                         |
| `{resolution}`   | The resolution parsed from the filename.                                     | `1080p`                                 |
| `{quality}`      | The quality/source parsed from the filename.                                 | `BluRay`                                |
| `{edition}`      | The edition parsed from the filename (e.g., Director's Cut).                 | `Directors Cut`                         |
| `{releasegroup}` | The release group parsed from the filename.                                  | `YTS`                                   |
| `{version}`      | A composite tag of resolution and edition.                                   | `- [1080p] [Directors Cut]`             |

### Advanced Formatting

-   **Padding:** You can pad numbers with leading zeros by specifying a length after a colon.
    -   `S{season:2}E{episode:2}` → `S01E05`

-   **Conditional Blocks:** You can make parts of the template optional based on whether a placeholder has a value. Wrap the section in square brackets `[]`. The block will only be included if the placeholder inside it is available.
    -   `{CleanTitle} ({year}) [tmdbid-{tmdbid}]` → `The Matrix (1999) [tmdbid-603]`
    -   If `tmdbid` is not found, it becomes: `The Matrix (1999)`

## 🛠️ Dependencies

This project is built with Kotlin and relies on several great open-source libraries:

-   [Clikt](https://github.com/ajalt/clikt) for building the command-line interface.
-   [Ktor](https://ktor.io/) for making HTTP requests to metadata APIs.
-   [Kotlinx Serialization](https://github.com/Kotlin/kotlinx.serialization) for parsing API responses.
-   [kotlinx-io](https://github.com/Kotlin/kotlinx-io) for multiplatform file system operations.

## 🤝 Contributing

Contributions are welcome! Whether it's bug reports, feature requests, or pull requests, please feel free to get involved.

## 🎬 Attribution

<a href="https://www.themoviedb.org/"><img src="docs/tmdb-logo.svg" alt="The Movie Database (TMDB)" width="150"></a>

This product uses TMDB and the TMDB APIs but is not endorsed, certified, or otherwise approved by TMDB.

Metadata is provided by [The Movie Database (TMDB)](https://www.themoviedb.org/). The same notice is shown in `plexify --help` and `plexify --version`.

## 📜 License

This project is licensed under the MIT License. See the [LICENSE](LICENSE) file for details.