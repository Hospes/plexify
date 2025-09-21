# Plexify

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Kotlin Version](https://img.shields.io/badge/Kotlin-2.2.10-blue.svg?logo=kotlin)](https://kotlinlang.org)
[![Build Status](https://img.shields.io/badge/build-passing-brightgreen.svg)](https://github.com/hospes/plexify) <!-- Placeholder -->

Plexify is a powerful, cross-platform command-line tool designed to automatically organize your movie and TV show collections into a clean, structured library, perfect for media servers like Plex, Jellyfin, and Emby. It intelligently parses filenames, fetches accurate metadata from multiple online sources, and renames/organizes your files according to best practices.

## ✨ Key Features

-   **Automatic Media Organization**: Processes individual files or entire directories, sorting them into a clean library structure.
-   **Advanced Filename Parsing**: Intelligently extracts title, year, season, episode, resolution, and quality from even the most complex filenames.
-   **Multi-Source Metadata**: Fetches and verifies media information from multiple databases (TMDB, IMDb) to find the most accurate match.
-   **Intelligent Consolidation**: Compares search results from all providers, scores them, and selects the best "golden record" for your media.
-   **Customizable Naming**: Comes with pre-configured, optimal naming templates for **Plex** and **Jellyfin**, or you can define your own powerful custom templates.
-   **Flexible File Operations**: Choose to either **move** your files or create **hardlinks**, preserving your original files for seeding or backup.
-   **Cross-Platform**: Built with Kotlin Multiplatform to run natively on **Linux** and **Windows**.

## ⚙️ How It Works

Plexify follows a simple but effective pipeline to organize your media:

1.  **Parse**: It deconstructs the original filename to make an educated guess about the media's title, year, season, episode, and other details.
2.  **Search**: It queries multiple online databases (currently TMDB and IMDb) for metadata based on the parsed information.
3.  **Consolidate**: It compares results from all sources, scores them based on confidence, and selects the best possible match to create a canonical record.
4.  **Format**: It uses the selected naming strategy (e.g., Jellyfin) and the canonical record to construct the ideal new file and folder path.
5.  **Organize**: It creates the necessary directories and moves or hardlinks the file to its final, clean destination.

## 🚀 Getting Started

### Prerequisites

-   Git
-   Java Development Kit (JDK) 11 or higher

### 1. Clone the Repository

```bash
git clone https://github.com/hospes/plexify.git
cd plexify
```

### 2. Configure API Keys

Plexify requires API keys to fetch metadata. You need to provide them by creating a `local.properties` file in the root of the project.

Create the file `local.properties`:

```properties
# Required for TMDB (The Movie Database)
TMDB_API_KEY="your_tmdb_api_key"
TMDB_API_READ_ACCESS_TOKEN="your_tmdb_access_token"

# Optional, for future use
# TVDB_API_KEY="your_tvdb_api_key"
# OMDB_API_KEY="your_omdb_api_key"
```

> **Note:** The application prioritizes keys in this order: Environment Variables > `gradle.properties` > `local.properties`.

### 3. Build the Executable

Use the included Gradle wrapper to build the native executable for your platform.

**For Linux:**

```bash
./gradlew linkReleaseExecutableLinux
```

**For Windows (in PowerShell or CMD):**

```bash
./gradlew.bat linkReleaseExecutableWindows
```

The compiled executable will be located in:
-   Linux: `build/bin/linux/releaseExecutable/plexify.kexe`
-   Windows: `build/bin/windows/releaseExecutable/plexify.exe`

You can move this executable to a directory in your system's `PATH` (e.g., `/usr/local/bin` or `C:\Windows`) for easy access.

> **Note:** For linux users you may need to run `chmod +x plexify.kexe` to make it executable. Also, you can rename the file to `plexify` if you want for cleaner usage in terminal

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
| `--template-plex`       | `-tp` | Use the predefined naming template for Plex.                                                             | `false`    |
| `--template-jellyfin`   | `-tj` | Use the predefined naming template for Jellyfin.                                                         | `true`     |
| `--template-custom <T>` | `-tc` | Use a custom naming template. See [Custom Naming Templates](#-custom-naming-templates) for syntax.       | `n/a`      |
| `--help`                | `-h`  | Show help message.                                                                                       |            |

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
| `{imdbid}`       | The IMDb ID (e.g., `tt1630029`).                                             | `tt1630029`                             |
| `{tmdbid}`       | The TMDb ID (e.g., `76600`).                                                 | `76600`                                 |
| `{tvdbid}`       | The TVDb ID (if available).                                                  | `12345`                                 |
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
    -   `{CleanTitle} ({year}) [imdbid-{imdbid}]` → `The Matrix (1999) [imdbid-tt0133093]`
    -   If `imdbid` is not found, it becomes: `The Matrix (1999)`

## 🛠️ Dependencies

This project is built with Kotlin and relies on several great open-source libraries:

-   [Clikt](https://github.com/ajalt/clikt) for building the command-line interface.
-   [Ktor](https://ktor.io/) for making HTTP requests to metadata APIs.
-   [Kotlinx Serialization](https://github.com/Kotlin/kotlinx.serialization) for parsing API responses.
-   [kotlinx-io](https://github.com/Kotlin/kotlinx-io) for multiplatform file system operations.

## 🤝 Contributing

Contributions are welcome! Whether it's bug reports, feature requests, or pull requests, please feel free to get involved.

## 📜 License

This project is licensed under the MIT License. See the [LICENSE](LICENSE) file for details.