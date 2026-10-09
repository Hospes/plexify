# Plexify

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Kotlin Version](https://img.shields.io/badge/Kotlin-2.4.20-blue.svg?logo=kotlin)](https://kotlinlang.org)
[![CI](https://github.com/Hospes/plexify/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/Hospes/plexify/actions/workflows/ci.yml)
[![Latest release](https://img.shields.io/github/v/release/Hospes/plexify)](https://github.com/Hospes/plexify/releases/latest)

Plexify is a command-line tool that turns a folder of downloaded movies and TV episodes into a clean library for **Plex** or **Jellyfin**. It reads each filename, looks the title up on [TMDB](https://www.themoviedb.org/), and hardlinks or moves the file into a properly named folder.

It is a single native executable for Linux (x64, arm64) and Windows. No Java or other runtime is needed.

```text
downloads/                                        library/
├── The.Matrix.1999.1080p.BluRay.x264-YTS.mkv     ├── The Matrix (1999) [tmdbid-603]/
└── Breaking Bad S01/                       ──►   │   └── The Matrix (1999) [tmdbid-603] - [1080p] [BluRay].mkv
    └── Breaking.Bad.S01E01.Pilot.1080p.mkv       └── Breaking Bad (2008) [tmdbid-1396]/
                                                      └── Season 01/
                                                          └── Breaking Bad (2008) - S01E01 - Pilot - [1080p].mkv
```

## ✨ Features

-   **Movies and TV episodes**: Processes single files or whole directory trees, and tells movies from episodes by the filename.
-   **Robust filename parsing**: Extracts title, year, season and episode, plus resolution, source, HDR format and edition, from scene-style (`S01E01`, `1x01`), underscore-separated, fansub (`[Group] Show - 01`) and bracket-numbered (`Show [01]`) names, including multi-episode files.
-   **TMDB matching with scoring**: Candidates are scored by title similarity and year. Weak matches are skipped, never guessed.
-   **Alias-aware matching**: Releases named with an original-language or alternative title (e.g. the romaji *Hametsu no Oukoku* for *The Kingdoms of Ruin*) still match, and the library uses the canonical title.
-   **Same-titled shows**: If the best-matching show doesn't have the release's season (say, *Being Human* US when the release is the UK original), other shows with the same title are tried.
-   **Anime numbering**: Releases that split a season TMDB keeps whole (*Gate* as two 12-episode seasons) are mapped through TMDB's episode groups, absolute numbers (*One Piece* `1071`) through TMDB's absolute order, and arc releases without a season number (*Kimetsu no Yaiba - Hashira Geiko-hen*) through TMDB's season titles. Files always get TMDB's own numbering.
-   **Manual overrides**: Force the title, year, season or an episode offset (`-t`, `-y`, `-s`, `--episode-offset`) when a release is too cryptic to parse.
-   **Plex, Jellyfin or custom naming**: Built-in templates follow each server's naming conventions, with TMDB, IMDb or TVDB IDs in the names. You can also write your own template.
-   **Hardlink or move**: Hardlinks (the default) keep the original in place for seeding, at no extra disk space.
-   **Safe to re-run**: Existing files in the library are never deleted or overwritten unless you pass `--overwrite`, and Plexify recognizes the names it writes itself, so running it over an existing library leaves files that are already in place alone.
-   **Dry run**: `--test` lists every folder and file it would create, without touching a single file.
-   **Readable output**: One line per file showing where it goes in the library, and a final summary. `--verbose` shows the whole pipeline: parsing, cache, TMDB calls and match scores.
-   **Fast on whole seasons**: Show and season lookups are cached per run, so a season of episodes costs one search and one season fetch.
-   **Long paths on Windows**: Paths of 260 characters or more work without changing any system setting.

## 🚀 Getting Started

### 1. Install

Download the archive for your platform from the [latest release](https://github.com/Hospes/plexify/releases/latest):

| Platform     | Archive                                  |
| ------------ | ---------------------------------------- |
| Linux x64    | `plexify-<version>-linux-x64.tar.gz`     |
| Linux arm64  | `plexify-<version>-linux-arm64.tar.gz`   |
| Windows x64  | `plexify-<version>-windows-x64.zip`      |

On Linux, for example (replace the version with the latest one):

```bash
VERSION=0.3.0
curl -LO "https://github.com/Hospes/plexify/releases/download/$VERSION/plexify-$VERSION-linux-x64.tar.gz"
tar -xzf "plexify-$VERSION-linux-x64.tar.gz"
sudo install plexify /usr/local/bin/
```

On Windows, extract `plexify.exe` from the zip into a folder on your `PATH`.

Check that it works:

```bash
plexify --version
```

### 2. Preview, then organize

Always start with a dry run. `--test` matches every file and lists the folders and files it would create, without creating, moving or linking anything:

```text
$ plexify --test "/data/downloads" "/data/media"
Plexify 0.4.0 | mode: HARDLINK | template: Jellyfin | destination: /data/media
!!! RUNNING IN TEST MODE (DRY RUN) - NO FILES WILL BE MODIFIED !!!
---
Processing directory: /data/downloads (3 media files)
  Matched show: Breaking Bad (2008) [tmdbid-1396]
  ✓ Breaking.Bad.S01E01.1080p.BluRay.x264.mkv → Breaking Bad (2008) [tmdbid-1396]/Season 01/Breaking Bad (2008) - S01E01 - Pilot - [1080p] [BluRay].mkv
  ✓ Breaking.Bad.S01E02.1080p.BluRay.x264.mkv → Breaking Bad (2008) [tmdbid-1396]/Season 01/Breaking Bad (2008) - S01E02 - Cat's in the Bag... - [1080p] [BluRay].mkv
  ✓ Inception.2010.1080p.BluRay.x264.mkv → Inception (2010) [tmdbid-27205]/Inception (2010) [tmdbid-27205] - [1080p] [BluRay].mkv
---
Done: 3 organized, 0 skipped, 0 failed.
```

When the result looks right, run the same command without `--test`. A real run prints the same lines. A file that is skipped or fails gets a `=` or `✗` line with the reason (see [Troubleshooting](#-troubleshooting)).

> **Important:** By default Plexify creates **hardlinks**, which only work when the source and the destination are on the **same drive or partition**. See [File operations](#-file-operations).

### 3. (Recommended) Use your own TMDB key

Release binaries include a TMDB key, so Plexify works out of the box. That key is shared by every user, so it can hit TMDB's rate limit or be revoked. If you organize large libraries, or Plexify reports that TMDB rejected the built-in key, get your own. It's free:

1.  Create a TMDB account and request an API key at [themoviedb.org/settings/api](https://www.themoviedb.org/settings/api).
2.  Copy the **API Read Access Token** (or the shorter **API Key**).
3.  Set it in your environment:

    ```bash
    export TMDB_API_ACCESS_TOKEN="your_read_access_token"
    ```

    On Windows (PowerShell): `setx TMDB_API_ACCESS_TOKEN "your_read_access_token"`, then open a new terminal.

Plexify uses the first credentials it finds, in this order:

1.  `--tmdb-access-token` / `--tmdb-api-key` on the command line
2.  The `TMDB_API_ACCESS_TOKEN` / `TMDB_API_KEY` environment variables
3.  The key built into the binary

Either credential works on its own; when both are given, the access token is used. Your own credentials are never mixed with the built-in key.

TMDB is Plexify's only metadata source, so it checks the credentials once at the start of each run. If they are missing (a build from source without a key) or TMDB rejects them, it stops with instructions before touching any file.

## 🖥️ Usage

```bash
plexify [OPTIONS] <source>... <destination>
```

-   `<source>...`: One or more files or directories to organize. Directories are searched recursively.
-   `<destination>`: The root folder of your library. Folders are created as needed.

Quote paths that contain spaces. On Windows, leave off the trailing backslash (`"D:\Media"`, not `"D:\Media\"`): a backslash before the closing quote escapes the quote.

### Options

| Option                      | Alias | Description                                                                                                         | Default    |
| --------------------------- | ----- | ------------------------------------------------------------------------------------------------------------------- | ---------- |
| `--mode <MODE>`             | `-m`  | `HARDLINK` or `MOVE` (case-insensitive). See [File operations](#-file-operations).                                  | `HARDLINK` |
| `--overwrite`               | `-o`  | Replace a different file already at the target path instead of skipping it. See [Existing files at the target](#existing-files-at-the-target). | off |
| `--test`                    |       | Dry run: match every file and list the target paths without touching any files.                                     | off        |
| `--verbose`                 |       | Show the detailed pipeline log (parsing, cache, TMDB calls, match scores).                                          | off        |
| `--template-jellyfin`       | `-tj` | Name files for Jellyfin.                                                                                            | ✓          |
| `--template-plex`           | `-tp` | Name files for Plex.                                                                                                |            |
| `--template-custom <T>`     | `-tc` | Name files with your own template. See [Custom templates](#custom-templates).                                       |            |
| `--title <TITLE>`           | `-t`  | Use this title instead of the one parsed from filenames.                                                            |            |
| `--year <YYYY>`             | `-y`  | Use this year. Acts as a strict filter: candidates from any other year are rejected.                                |            |
| `--season <N>`              | `-s`  | Use this season number for every episode (ignored for movies).                                                     |            |
| `--episode-offset <N>`      |       | Add N to every parsed episode number (negative subtracts). With `-s`, places a split season: S2E01 → S01E13.       |            |
| `--tmdb-access-token <T>`   |       | Your TMDB API Read Access Token (preferred over `--tmdb-api-key`). Env: `TMDB_API_ACCESS_TOKEN`.                    | built-in   |
| `--tmdb-api-key <KEY>`      |       | Your TMDB API key (v3). Env: `TMDB_API_KEY`.                                                                        | built-in   |
| `--version`                 | `-v`  | Show the version and exit.                                                                                          |            |
| `--help`                    | `-h`  | Show the help and exit.                                                                                             |            |

The template options are mutually exclusive.

> **Note:** `--title`, `--year`, `--season` and `--episode-offset` apply to **every file in the run**. Use them when pointing Plexify at one movie or at one season of a show, not at a mixed downloads folder.

### Examples

**Organize a downloads folder with hardlinks and Jellyfin naming (the defaults):**

```bash
plexify "/data/downloads" "/data/media"
```

**Move a single movie into a Plex library:**

```bash
plexify --mode MOVE --template-plex "/data/downloads/The.Matrix.1999.mkv" "/data/media/Movies"
```

**Process several sources at once:**

```bash
plexify "/data/torrents/movies" "/data/torrents/shows" "/data/media"
```

**Use a custom template for movies:**

```bash
plexify --template-custom "{CleanTitle} ({year})/{CleanTitle} - {year} [{resolution}].{ext}" \
  "/data/downloads/The.Matrix.1999.1080p.mkv" "/data/media/Movies"
```

This creates `The Matrix (1999)/The Matrix - 1999 [1080p].mkv`.

**Rescue a cryptic release with overrides (preview first):**
The season folder is just named `2`, and the filenames don't carry the show's name:

```bash
plexify --test -t "The Kingdoms of Ruin" -s 2 "/data/anime/SomeShow/2" "/data/media/Anime"
```

**Pin a remake to the right year:**
A remake often shares its title with the original; `--year` rejects candidates from any other year:

```bash
plexify -y 2011 "/data/downloads/The.Thing.1080p.mkv" "/data/media/Movies"
```

**Place a split-cour anime season:**
TMDB lists *Gate* as one 24-episode season, while releases ship it as two 12-episode seasons. Plexify normally resolves this on its own through TMDB's episode groups (`Gate S2 [01]` becomes `S01E13`). If the show has no matching episode group, place the season yourself:

```bash
plexify -s 1 --episode-offset 12 "/data/anime/Gate S2" "/data/media/Anime"
```

## 📂 What Plexify Recognizes

### Files

Only video files are processed: `mkv`, `mp4`, `avi`, `mov`, `wmv`, `m4v`, `mpg`, `mpeg`, `flv`. Other files, such as subtitles (`.srt`, `.ass`), `.nfo` files and artwork, are ignored and stay where they are.

### Filename patterns

A file is treated as a **TV episode** when its name matches one of these forms (checked in this order), and as a **movie** otherwise:

| Form                                                     | Example                                                                 | Season comes from |
| -------------------------------------------------------- | ----------------------------------------------------------------------- | ----------------- |
| `SxxEyy` (up to 4 digits each, optional `v2`)            | `Breaking.Bad.S01E01.720p.mkv`, `Show.S01.E01.mkv`, `Show S01E05v2 [1080p].mkv`, `One.Piece.S01E1071.mkv` | the name |
| Multi-episode `SxxEyy`                                   | `Show.S01E01E02.mkv`, `Show.S01E01-E02.mkv`, `Show.S01E01-02.mkv`       | the name          |
| `NxNN`                                                   | `Friends.1x01.mkv`, `Friends - 02x24 - Title.mkv`, `Friends.10x17-18.mkv` | the name        |
| Fansub `Show - NN` (optional `v2`, absolute numbers)     | `[SubsPlease] Sousou no Frieren - 01 (1080p) [ABCD1234].mkv`, `Show - 01v2.mkv`, `[SubsPlease] One Piece - 1071 (1080p).mkv` | `S2` / `Season 2` / `2nd Season` closing the title, else the parent folder |
| `Season N` / `SN` + `[NN]`                               | `Tsue_to_Tsurugi_no_Wistoria_Season_2_[01]_[HEVC].mkv`, `Gate_S2_[12].mkv` | the name       |
| `[NN]`                                                   | `Dungeon.Meshi.[13].[1080p].mkv`                                        | the parent folder (`Season 2`, `S02`, `S2`) |

-   A name that starts with the episode (`S01E01 - Pilot.mkv`) has no show title; set it with `-t`.
-   When no season is found, Season 1 is assumed (with a warning); use `-s` to set it. Two exceptions, both looked up on TMDB:
    -   An episode number past the end of that season is placed through the show's TMDB episode groups (split-cour releases) or its absolute order (*One Piece* `1071`).
    -   An anime arc release (`Kimetsu no Yaiba - Hashira Geiko-hen [01]`) takes the season whose TMDB title matches the arc. If TMDB has no such season title, the file is skipped with a hint to use `-s`, instead of overwriting Season 1.
-   A leading release-group tag such as `[SubsPlease]` or `[Erai-raws]` is not part of an episode's title; it fills `{releasegroup}`. Trailing tags like `(1080p)` and CRC checksums like `[ABCD1234]` are ignored.
-   A multi-episode file is named with its range, `Show (2008) - S01E01-E02 - Pilot & Cat's in the Bag.mkv`, the form Plex and Jellyfin read as several episodes. When the episodes after the first aren't the next ones on TMDB (the range runs past the season, or TMDB merges them into one), the file is filed as its first episode with a warning.
-   For movies, the year is taken from brackets when present (`Title (2024)`), otherwise from the last year in the name, so `2001.A.Space.Odyssey.1968.mkv` is matched as *2001: A Space Odyssey (1968)*. The title ends at the year or at the first technical tag.
-   Names Plexify writes itself (`Show (2015) - S01E13 - Title - [720p].mkv`, `Movie (2010) [tmdbid-27205].mkv`) parse back to the same media, so a library can be re-run. ID tags like `[tmdbid-603]`, `[imdbid-tt0133093]` or `{tmdb-603}` are never treated as part of the title.
-   In an episode name with a title after the episode (`Show (2010) - S01E01 - Pilot`), the tags come from the whole name, as Sonarr writes them (`… - Pilot WEBDL-1080p.mkv`), except the edition: *The Final Cut* is as likely an episode title. In Plexify's own names, which end in the ` - [1080p] [BluRay]` suffix, only that suffix counts.

### Technical tags

These tags are picked up from the filename and are available to naming templates:

| Tag        | Recognized values                                                                                       |
| ---------- | ------------------------------------------------------------------------------------------------------- |
| Resolution | `480p`, `720p`, `1080p`, `2160p`, `4K`                                                                  |
| Source     | `BluRay`, `Blu-ray`, `BDRip`, `BRRip`, `DVD`, `DVDRip`, `WEB-DL`, `WEBDL`, `WEBRip`, `WEB-Rip`, `WEB-DLRip`, `HDTV`, `HDRip` |
| HDR        | `HDR10+`, `HDR10`, `HDR`, `DV`, `DoVi`, `HLG`, `SDR`                                                    |
| Edition    | `Unrated`, `Extended`, `Limited`, `Theatrical`, `Remastered`, `Redux`, `IMAX`, `Director's Cut`, `Special Edition`, `Anniversary Edition`, `Final Cut` |

Tags keep the spelling used in the filename (`BluRay` stays `BluRay`), except HDR formats and editions, which are normalized.

## 📁 File Operations

| Mode                   | What happens                                                                                                             |
| ---------------------- | ------------------------------------------------------------------------------------------------------------------------ |
| `HARDLINK` (default)   | Creates a second name for the same data. The original stays where it is (keep seeding), and no extra disk space is used. |
| `MOVE`                 | Renames the file into the library. The original disappears from the source folder.                                       |

-   **Both modes need the source and destination on the same filesystem** (same drive, partition or volume). Plexify never copies data. Across drives, both a hardlink and a move fail with the operating system's error; for hardlinks, Plexify adds *"(source and destination must be on the same volume/partition)"*. In Docker, source and destination must come from the same mounted volume.
-   On Windows, hardlinks require an NTFS volume.

### Existing files at the target

By default, Plexify never deletes or overwrites a file that is already at the target path, in either mode. All three cases below count as *skipped* in the summary, and `--test` reports them the same way:

-   **The target is the file itself.** The source already sits at its target (for example, when you run Plexify over an existing library), or the target is a hardlink to the source (or to the file a symlinked source points at). The file is left alone: `= Movie.mkv — already in the library: <path>`.
-   **A different file is at the target.** Both files are left alone: `✗ Movie.mkv — target already exists: <path>`. To replace it, run again with `--overwrite` (`-o`), or delete or rename the existing file yourself.
-   **An earlier file of the same run has the target.** Two sources can get the same name, for example a release and its `REPACK` (`{version}` only holds resolution, source, HDR and edition). The first one processed keeps the target, and the later one is left alone: `✗ Movie.REPACK.mkv — same target as Movie.mkv earlier in this run: <path>`. Files are processed in name order (see *How It Works* below), so of two files in one folder, the one whose name sorts first wins, on every platform and file system. This also applies to a file that was already in place. To keep the later file instead, organize it on its own in a separate run with `--overwrite`.

With `--overwrite`, a different file at the target is replaced and counted as *organized*: `✓ Movie.mkv → Movie (2010)/Movie (2010).mkv (replaced existing file)`. In `HARDLINK` mode the new link is created under a temporary name (`.plexify-<8 hex digits>.tmp`) first and then renamed over the old file, so the old file stays if linking fails. If Plexify is killed between those two steps, the temporary file is left next to the old one; the next run that writes to that folder deletes it. Replacing only removes the library's name for the old file: if it is also hardlinked elsewhere (an old download folder, say), that copy stays on disk. `--overwrite` never applies to the other two cases: a file that already is its own target, or one this run already placed, is always left alone.

### Symlinked sources

A source file can be a symbolic link (pointing at a download in another folder or on a mount, say):

-   **`HARDLINK`**: the library gets a hardlink to the file the symlink points at, not to the symlink itself, so it keeps working however the symlink was written. The symlink is left alone. The file it points at must be on the same volume as the library.
-   **`MOVE`**: the symlink itself is moved into the library, the way a library made of symlinks (e.g. to a cloud or debrid mount) expects; the file it points at is not touched. Plexify checks that the moved symlink still leads to the same file. A relative symlink usually doesn't from its new folder: the move is then undone and reported as failed (`… won't resolve from the library`). Use `HARDLINK` mode for those, or recreate them as absolute links.

## 📝 Naming Templates

### Built-in templates

**Jellyfin** (default):

```text
The Matrix (1999) [tmdbid-603]/The Matrix (1999) [tmdbid-603] - [1080p] [BluRay].mkv
Breaking Bad (2008) [tmdbid-1396]/Season 01/Breaking Bad (2008) - S01E01 - Pilot - [1080p].mkv
```

**Plex** (`-tp`):

```text
The Matrix (1999) [imdbid-tt0133093]/The Matrix (1999) - [1080p] [BluRay].mkv
Breaking Bad (2008) [imdbid-tt0903747]/Season 01/Breaking Bad (2008) - S01E01 - Pilot - [1080p].mkv
```

Both name a multi-episode file with its range: `Breaking Bad (2008) - S01E01-E02 - Pilot & Cat's in the Bag... - [1080p].mkv`. When TMDB has no year for a title, the `(year)` part is left out.

The Plex template uses IMDb IDs, which Plexify looks up on TMDB for each matched movie or show (one extra request per title, not per file). When TMDB has no IMDb ID, the `[imdbid-…]` tag is left out.

### Custom templates

`--template-custom` takes a single string. The part before the last `/` is the folder and the part after it is the filename:

```text
{CleanTitle} ({year}) [tmdbid-{tmdbid}]/{CleanTitle} ({year}){version}.{ext}
└──────────── folder ────────────────┘ └──────────── file ──────────────┘
```

> **Custom templates are designed for movies.** For TV episodes, only the folder part is used, as the show folder. Episodes always go into `Season NN/` subfolders and are named `{CleanTitle} ({year}) - S{season:2}E{episode:2}{multiEpisode} - {episodeTitle}{version}.{ext}`. Always include a folder part when your sources contain TV shows, or every show's season folders end up in the library root.

### Placeholders

Placeholder names are case-insensitive: `{CleanTitle}` and `{cleantitle}` are the same.

| Placeholder      | Value                                                                                          | Example                           |
| ---------------- | ---------------------------------------------------------------------------------------------- | --------------------------------- |
| `{title}`        | Title from TMDB, as is.                                                                        | `Avatar: The Way of Water`        |
| `{cleantitle}`   | Title with characters that are invalid in file names (`< > : " / \ \| ? *`) removed. Use this one in paths. | `Avatar The Way of Water` |
| `{year}`         | Release year of a movie, or first-air year of a show. Empty when TMDB has none.                | `2022`                            |
| `{ext}`          | Original file extension.                                                                       | `mkv`                             |
| `{tmdbid}`       | TMDB ID.                                                                                       | `76600`                           |
| `{imdbid}`       | IMDb ID, looked up on TMDB for the match; empty when TMDB has none.                            | `tt1630029`                       |
| `{tvdbid}`       | TVDB ID, looked up on TMDB; TV shows only.                                                     | `81189`                           |
| `{season}`       | Season number (episodes only).                                                                 | `1`                               |
| `{episode}`      | Episode number (episodes only; the first one for a multi-episode file).                       | `5`                               |
| `{multiepisode}` | `-E` and the last episode for a multi-episode file, empty otherwise.                           | `-E06`                            |
| `{episodetitle}` | Episode title, cleaned like `{cleantitle}` (episodes only). For a multi-episode file, the titles joined with ` & `. | `The Ride` |
| `{resolution}`   | Resolution from the filename.                                                                  | `1080p`                           |
| `{quality}`      | Source from the filename.                                                                      | `BluRay`                          |
| `{hdr}`          | HDR format from the filename.                                                                  | `HDR10`                           |
| `{edition}`      | Edition from the filename.                                                                     | `Directors Cut`                   |
| `{releasegroup}` | Release group from the filename: a leading fansub tag on an episode (`[SubsPlease]`), or `LostFilm`. Scene suffixes like `-YTS` aren't recognized. | `SubsPlease` |
| `{version}`      | All technical tags present, with a leading ` - `; empty when there are none.                   | ` - [2160p] [BluRay] [HDR10]`     |

Because `{version}` brings its own leading ` - `, put it directly after the preceding text, without a space: `{CleanTitle} ({year}){version}.{ext}`.

### Formatting

-   **Zero padding:** add a width after a colon. `S{season:2}E{episode:2}` → `S01E05`, and `S{season:2}E{episode:2}{multiEpisode}` → `S01E05-E06` for a two-episode file.
-   **Missing year:** a placeholder alone in parentheses, like `({year})`, is dropped together with the parentheses when it has no value.
-   **Optional blocks:** text in square brackets is dropped when its placeholder has no value.
    -   `{CleanTitle} ({year}) [imdbid-{imdbid}]` → `The Matrix (1999) [imdbid-tt0133093]`
    -   …or `The Matrix (1999)` when no IMDb ID was found.
    -   Use one plain placeholder per block: only the first placeholder in a block is checked, and padded placeholders such as `{season:2}` don't make a block optional.
-   Repeated spaces are collapsed, and leading and trailing spaces are trimmed.

## ⚙️ How It Works

Plexify goes through the source folder one file at a time, in name order: each folder's entries are sorted ignoring case, with numbers compared by value (`E2` before `E10`), and a subfolder is entered where its name sorts. The order is the same on Linux and Windows and on every file system, so a rerun processes the same files in the same order. It matters when files compete: for the same target (the first one keeps it), and for a season's show (the first file of a season places it).

Every file goes through the same pipeline:

1.  **Parse:** The filename (and, for the season, its parent folder) is broken down into title, year, season, episode and technical tags. Overrides from the command line are applied on top.
2.  **Search:** TMDB is searched for the title. When a result doesn't resemble the parsed title by its display or original title, its alternative titles are fetched too. Every TMDB request has a time limit and is retried when TMDB is busy (HTTP 429), so a stalled connection can't hang the run.
3.  **Match:** Every candidate is scored. Title similarity is checked against every title the candidate is known by, and the year adds or subtracts points. For a TV show, a filename year later than the show's first-air year is normal (it's usually the season's year) and isn't penalized. Candidates below a minimum score are dropped; if none is left, the file is skipped with the reason.
4.  **Episode lookup:** For episodes, the whole season is fetched once and cached. If the episode isn't in that season, TMDB's episode groups and absolute order are tried, so releases numbered differently from TMDB still land on TMDB's episode. If the best-matching show has no such season at all, other candidates are tried, but only ones that match the release title at least as well and whose year doesn't contradict the filename. The show that places a season is used for all of that season's files.
5.  **IDs:** If the template uses `{imdbid}` or `{tvdbid}`, those IDs are looked up on TMDB for the matched movie or show.
6.  **Format:** The naming template is filled from the matched record (titles, year, IDs, episode title) and the filename (technical tags).
7.  **Organize:** Folders are created and the file is hardlinked or moved into place, unless something is already at the target or an earlier file of the run took it (see [Existing files at the target](#existing-files-at-the-target)). In `--test` mode, nothing is created, moved or linked.

## 🩺 Troubleshooting

Run with `--test --verbose` first. The log shows what was parsed from each filename, which candidates were found, and how each one scored.

| Message                                                                     | What to do                                                                                                                  |
| --------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------- |
| `no confident match for '…'` / `No match for '…'`                           | Check the parsed title in the verbose log. Set the title with `-t`, and the year with `-y` if the wrong year won.            |
| `No season number found in filenames, defaulting to Season 1`               | Set the season with `-s`.                                                                                                   |
| `… names an arc that matches no season title of …; set the season with -s/--season` | TMDB doesn't tag this arc with a season. Set the season with `-s`.                                                   |
| `episode SxEy not found`                                                    | The release is numbered differently from TMDB. Place it with `-s` and `--episode-offset`.                                    |
| `… is numbered differently by episode groups …`                             | Several TMDB episode groups fit, so Plexify won't guess. Place the season with `-s` and `--episode-offset`.                  |
| `Season N is not in …; matched show: …`                                     | The best-matching show didn't have this season, so another show with the same title was used. If that's the wrong one, set the year with `-y`. |
| `target already exists: <path>`                                             | A different file is already there. Use `--overwrite` to replace it, or remove it yourself.                                   |
| `already in the library: <path>`                                            | Nothing to do: the file already is at its target.                                                                           |
| `same target as … earlier in this run: <path>`                              | Two files got the same name, and the first one kept it. To keep the other one instead, organize it on its own with `--overwrite`. |
| `Hardlink failed: … (source and destination must be on the same volume/partition)` | Put the library on the same drive as the downloads.                                                                  |
| `Can't move '…' to '…': …`                                                  | With `--mode MOVE`, the source and the library must also be on the same drive.                                              |
| `… is a symlink to … that won't resolve from the library …`                 | A relative symlink can't be moved. Use `HARDLINK` mode, or make the symlink absolute. See [Symlinked sources](#symlinked-sources). |
| `TMDB request timed out …` / `Warning: … TMDB may be unreachable.`          | TMDB didn't answer in time. Check your connection (and proxy) and run again; files that timed out count as failed.          |
| `HTTP 5xx …` or another network error on a file                             | The TMDB search failed, which says nothing about the title. Run again later; those files count as failed.                   |
| `TMDB rejected the built-in API key (HTTP 401)`                             | The shared key was revoked. [Use your own key](#3-recommended-use-your-own-tmdb-key).                                       |
| `TMDB rejected your credentials (HTTP 401)`                                 | Check `TMDB_API_ACCESS_TOKEN` / `TMDB_API_KEY` (or the matching flags).                                                     |
| `TMDB rate limit reached (HTTP 429)`                                        | The shared key is busy. [Use your own key](#3-recommended-use-your-own-tmdb-key).                                            |
| `No TMDB credentials, and this build has no built-in key.`                  | Your binary was built without a key. [Set your own](#3-recommended-use-your-own-tmdb-key).                                   |
| An SSL / certificate error on Linux                                         | Plexify uses the system's CA bundle from the usual locations. On an unusual distribution or a minimal container, point `SSL_CERT_FILE` at your CA bundle file. |

### Known limitations

-   **Absolute episode numbers** (`One Piece - 1071`, or `S01E1071`) are placed through the show's TMDB absolute-order episode group. A show without one needs `-s` and `--episode-offset`.
-   **Fansub `Show - NN` names**: a number after ` - ` is read as an episode, so a movie named `Title - 2.mkv` is parsed as one (name it `Title 2 (Year).mkv`). Years are excluded: `Alien - 1979.mkv` stays a movie, and so does any `- 19xx`/`- 20xx`. Batch ranges (`Show - 01-12`), specials (`Show - OVA`, `Show - 12.5`) and episode-only names (`01.mkv`) are not recognized.
-   **Multi-episode ranges** must be consecutive episodes of one season. Ranges across seasons (`S01E24-S02E01`) are filed as their first episode, and so is a range TMDB lists as a single episode (*Friends* "The Last One", `10x17-18`).
-   **Release groups**: a movie named `[Group] Movie (2020).mkv` keeps the group in its search title, since a bracket can be the title itself (`[REC] (2007).mkv`). Scene suffixes (`-YTS`) aren't read as release groups.
-   **Year-numbered seasons** (`S2024E01`) are parsed, but match only if TMDB numbers the show's seasons by year.
-   **Date-based episodes** (`Show.2024.03.15.mkv`) aren't recognized.
-   **One file at a time**: sample files and extras inside a release folder are processed like any other video, and subtitles and other companion files are not carried along.

## 🛠️ Building from Source

Requirements: Git and JDK 21. The first build downloads the Kotlin/Native toolchain (several hundred MB). CI builds the Linux binaries on Linux (arm64 is cross-compiled on x64) and the Windows binary on Windows.

```bash
git clone https://github.com/Hospes/plexify.git
cd plexify
```

To build a TMDB key into your binary, create `local.properties` in the project root (it is git-ignored). Without a key, the binary needs one at runtime (see [Use your own TMDB key](#3-recommended-use-your-own-tmdb-key)).

```properties
TMDB_API_ACCESS_TOKEN=your_read_access_token
# or
# TMDB_API_KEY=your_api_key
```

At build time, keys are read from environment variables first, then `gradle.properties`, then `local.properties`.

Build with the Gradle wrapper:

```bash
./gradlew linkReleaseExecutableLinuxX64       # Linux x64
./gradlew linkReleaseExecutableLinuxArm64     # Linux arm64
./gradlew.bat linkReleaseExecutableWindows    # Windows
```

The executable is written to:

-   Linux x64: `build/bin/linuxX64/releaseExecutable/plexify.kexe`
-   Linux arm64: `build/bin/linuxArm64/releaseExecutable/plexify.kexe`
-   Windows: `build/bin/windows/releaseExecutable/plexify.exe`

On Linux, rename `plexify.kexe` to `plexify` and put it on your `PATH`.

Run the tests (they are native binaries, so run the task for your OS):

```bash
./gradlew linuxX64Test       # Linux
./gradlew.bat windowsTest    # Windows
```

## 🤝 Contributing

Bug reports, feature requests and pull requests are welcome. For a parsing or matching problem, include the exact filename and the `--test --verbose` output.

-   `main` is protected: changes land through pull requests, and the Linux and Windows CI checks must pass.
-   Commit messages must follow [Conventional Commits](https://www.conventionalcommits.org/) (`feat(parser): …`, `fix(naming): …`), because release notes and version bumps are generated from them.
-   [CLAUDE.md](CLAUDE.md) describes the architecture and the development workflow in more detail.

Releases are cut by pushing a version tag (e.g. `0.3.1`) on `main`. CI then builds the binaries and publishes the GitHub release.

## 📦 Built With

-   [Kotlin Multiplatform](https://kotlinlang.org/docs/multiplatform.html) / Kotlin/Native
-   [Clikt](https://github.com/ajalt/clikt) for the command-line interface
-   [Ktor](https://ktor.io/) (curl engine) for HTTP requests to the TMDB API
-   [kotlinx.serialization](https://github.com/Kotlin/kotlinx.serialization) for parsing API responses
-   [kotlinx.coroutines](https://github.com/Kotlin/kotlinx.coroutines) for asynchronous requests
-   [kotlinx-io](https://github.com/Kotlin/kotlinx-io) for file system operations

## 🎬 Attribution

<a href="https://www.themoviedb.org/"><img src="docs/tmdb-logo.svg" alt="The Movie Database (TMDB)" width="150"></a>

This product uses TMDB and the TMDB APIs but is not endorsed, certified, or otherwise approved by TMDB.

Metadata is provided by [The Movie Database (TMDB)](https://www.themoviedb.org/). The same notice is shown in `plexify --help`.

## 📜 License

This project is licensed under the MIT License. See the [LICENSE](LICENSE) file for details.
