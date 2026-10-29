# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Plexify is a cross-platform CLI tool (Kotlin Multiplatform / Native, no JVM at runtime) that organizes movie and TV show files into a clean library structure for media servers (Plex, Jellyfin). It targets `linuxX64`, `linuxArm64`, and `mingwX64` (named `windows`). Single Gradle module; all logic lives in `src/commonMain`, with tiny platform source sets for `expect`/`actual` implementations.

## Commands

```bash
# Build release executable (pick target for your host)
./gradlew linkReleaseExecutableWindows      # -> build/bin/windows/releaseExecutable/plexify.exe
./gradlew linkReleaseExecutableLinuxX64     # -> build/bin/linuxX64/releaseExecutable/plexify.kexe
./gradlew linkReleaseExecutableLinuxArm64

# Faster debug builds during development
./gradlew linkDebugExecutableWindows

# Run tests (tests are native binaries — run the task matching the host OS)
./gradlew windowsTest                       # on Windows
./gradlew linuxX64Test                      # on Linux
./gradlew allTests                          # all targets runnable on this host

# Run a single test class or method
./gradlew windowsTest --tests "io.github.hospes.plexify.domain.service.MovieFilenameParserTest"
./gradlew windowsTest --tests "*EpisodeFilenameParserTest.some test name*"
```

There is no linter configured. Kotlin/Native linking is slow; prefer running tests over full links for iteration.

`core/TranscriptTest` pins everything the processor prints (concise and verbose) for representative scenarios, through `LoggingContext.output` and `runProcessor`'s captured `ProcessorRun.output`. After an intended output change, run it and open the test report (`build/reports/tests/windowsTest/index.html`, or `linuxX64Test`): the failure message gives each transcript that differs, ready to paste over the old one; check the diff before committing.

### Verifying a change end to end

Unit tests don't touch the network, and several bugs (alias matching, 404 season bodies, year scoring) only showed up against live TMDB. For anything in the parse → search → organize path, also run the binary as a dry run on real filenames, with keys from `local.properties` built in:

```bash
./gradlew linkDebugExecutableWindows      # Linux: linkDebugExecutableLinuxX64
build/bin/windows/debugExecutable/plexify.exe --test <source-dir> <scratch-dest-dir>
#   Linux: build/bin/linuxX64/debugExecutable/plexify.kexe
```

`--test` touches no files. Report the matched titles to the maintainer, who checks the result against real releases before a change is committed.

### Workflow

- `main` is protected by two repo rulesets: changes land only through a pull request, and the `Build & test (Linux)` and `Build & test (Windows)` checks must pass. Work on a branch and open a PR; the maintainer reviews and merges.
- `main – review` requires no approval: the maintainer is the only one with write access, so nothing merges without them, and GitHub doesn't let authors approve their own PRs. [.github/CODEOWNERS](.github/CODEOWNERS) still requests the maintainer's review on other people's PRs. If collaborators get write access, set required approvals to 1 with code owner review. `main – safety` (CI) has no bypass.
- Merge with **rebase** (history is linear, no merge commits; the repo and the ruleset allow nothing else); head branches are deleted automatically after merge.
- Release by pushing a tag on `main` once CI is green there (see below); never publish a GitHub Release by hand.

### API keys

Metadata providers need keys, resolved at **build time** in priority order: environment variables > `gradle.properties` > `local.properties` (untracked, in repo root). Keys: `TMDB_API_KEY`, `TMDB_API_ACCESS_TOKEN` (either one is enough for TMDB; the token is preferred). They are baked into the binary via the `buildconfig` plugin (generated `BuildConfig` class).

At **runtime**, `data/tmdb/TmdbCredentials.resolve()` picks the user's own credentials (`--tmdb-access-token`/`--tmdb-api-key` or the `TMDB_API_ACCESS_TOKEN`/`TMDB_API_KEY` env vars) as a set, and falls back to the built-in `BuildConfig` values only when the user gave none — never a mix. Release binaries get the built-in key from the `TMDB_API_ACCESS_TOKEN`/`TMDB_API_KEY` repo secrets; it is shared by every user and extractable, so TMDB may revoke it. `App` checks the credentials once per run (`TmdbProvider.verifyCredentials()`) and fails with instructions on HTTP 401, distinguishing user vs built-in credentials. The TMDB terms (section 3) require the attribution notice `TMDB_ATTRIBUTION`, shown in the `--help` epilog and the README (with the TMDB logo) — keep it. `--version` prints only the version.

### Versioning & releases

`version` is derived from `git describe --tags` plus a branch-based suffix (`release/*` → `-RC`, `feature/*` → `-FEATURE`) — see [build.gradle.kts](build.gradle.kts). A release is cut by pushing a semver tag (e.g. `0.2.1`, no `v` prefix) on `main`. [.github/workflows/release.yml](.github/workflows/release.yml) first runs a gate, [scripts/check-ci-passed.sh](scripts/check-ci-passed.sh), which fails the release unless the tagged commit is on `main` and the latest run of each required CI check concluded success on it (it waits up to 40 minutes for CI still running; after a failure, re-run the workflow once CI is green). Then it builds Linux x64/arm64 + Windows binaries and, in parallel, has Gemini CLI write the notes by following the `release-notes` skill. [scripts/validate-release-notes.sh](scripts/validate-release-notes.sh) checks those notes, and the GitHub Release is created last, with the binaries and the notes together. If notes generation or validation fails, the release still ships with GitHub's generated notes and a warning on the run; use the `release-notes` skill locally to rewrite them. Re-running the workflow uses the workflow files from the tagged commit, so a workflow fix needs the tag moved to a commit that has it.

CI ([.github/workflows/ci.yml](.github/workflows/ci.yml)) runs on every PR and push to `main`: Linux (`linuxX64Test` + arm64 test link) and Windows (`windowsTest`). Its job names are required status checks in the repo's `main – safety` ruleset — renaming a job means updating the ruleset. Dependabot opens grouped monthly PRs for Gradle and Actions.

## Commit Convention (required)

Commits follow **Conventional Commits** — release notes are generated automatically from commit messages, so the format is mandatory:

```
<type>(<scope>)?: <summary>

[optional body]

[optional footer, e.g. BREAKING CHANGE: ...]
```

- **Types** (drive release-note sections and semver bumps):
  - `feat:` — new user-facing capability → minor bump
  - `fix:` — bug fix → patch bump
  - `perf:` — performance improvement → patch bump
  - `refactor:` — code change with no behavior change
  - `docs:`, `test:`, `build:`, `ci:`, `chore:` — excluded from release notes
- **Breaking changes**: append `!` after the type/scope (`feat!:` or `feat(cli)!:`) and/or add a `BREAKING CHANGE:` footer → major bump.
- **User-visible changes that aren't marked breaking** (renamed library folders or files, a removed or changed option): keep the type (`feat`/`fix`) and add a paragraph to the body that starts with `Existing libraries:` or `What changes for users:`, saying what changes, what the next run does, and what to do. The `release-notes` skill turns it into a ⚠️ Upgrade Notes entry. The 0.x releases use this instead of `!`, which would force 1.0.0.
- **Scopes** (optional, lowercase): `parser`, `metadata`, `naming`, `cli`, `core`, `cache`, `build`, `ci`, `deps` (dependency bumps, as Dependabot writes them: `build(deps): …`, `ci(deps): …`).
- Summary: imperative mood, lowercase after the colon, no trailing period, ≤ 72 chars.

Examples:

```
feat(parser): detect HDR and edition tags in filenames
fix(naming): strip trailing space when {version} placeholder is empty
feat(cli)!: rename --dry-run flag to --test
```

## Architecture

The processing pipeline (README "How It Works"): **Parse → Search → Consolidate → Format → Organize**. Understanding one file requires knowing where it sits in this flow:

1. **CLI entry** — [App.kt](src/commonMain/kotlin/io/github/hospes/plexify/App.kt) (Clikt command) wires everything and calls `MediaProcessor.process()` per source path. Platform `main.kt` in `linuxMain`/`windowsMain` just delegates to `commonMain()`.
2. **Parse** — `domain/service/MediaFilenameParser` (pure object, regex-tiered; the only entry point, over internal readers: `EpisodeNameParser` holds the episode tiers, `MovieNameParser` the movie fallback, `ReleaseGroupReader` the release groups, `PathIdReader` the path IDs, and `ReleaseTags` the tags, stop words and years they share) extracts title/year/season/episode/resolution/quality/HDR/edition from filenames into `ParsedMediaInfo.Movie` or `.Episode`. The parent directory name is used as a fallback for season detection. It also reads the movie or show ID the path names (`ParsedMediaInfo.mediaId`): an ID tag in the file name, its folder, or the show folder above an episode's season folder (a folder only when it names the file's own title), else Plexify's own ID-fallback title (`tmdb-1721501 (2026)`). `fromTitle` marks an ID whose name has such an ID for its title. This is the most test-covered area (`src/commonTest`).
3. **Search** — When the path names an ID (and no `-t`/`-y` is set), `MediaProcessor` looks it up directly (`MetadataService.findMovie`/`findShow`: TMDB `movie/{id}` or `tv/{id}`, one request that brings the alternative titles and the IMDb ID too (`append_to_response`), after `find/{imdb_id}` for an IMDb ID) instead of searching. The record must still pass `MatchRanker` for the parsed title and year, as a search result would (a `fromTitle` ID instead needs a record title Plexify names by its ID, `isNamedById`, and no contradicting year); otherwise, or when TMDB has no such record, the title is searched. Otherwise the title is searched through `domain/service/MetadataService`, which queries the `MetadataProvider`s (`data/`) that `resolveActiveProviders()` picks: TMDB first, others only when the naming template uses an ID they supply (`NamingStrategy.requiredMetadataFields()`). `App` gives it only `TmdbProvider`. Every TMDB call has connect/request timeouts (`TmdbTimeouts`; files are processed sequentially, so a stall would block the run) and is retried on HTTP 429 and, once, on a timeout; a timeout fails as `MetadataTimeoutException`, and a connection error as `MetadataConnectionException` with curl's reason, each with a short message that never includes the URL. After `unreachableAfter` (3) requests in a row with no answer, `TmdbHttp` (the client under `TmdbProvider`'s endpoints; `TmdbMappers.kt` turns its DTOs into domain models) fails every later request of the run at once (`MetadataUnreachableException`), and `App` stops before any file when the credential check gets no answer. A search also fetches the alternative titles of up to 3 results whose title and original title don't resemble the query (`MAX_ALT_TITLES_LOOKUPS`). These lookups are optional: a shorter request timeout, no retry on a timeout, and one that fails (no answer, or an HTTP error other than 404) fails the search, unless a result already resembles the query (the missing alias may be the match). One with no answer, or a 429 after its retries, also ends that search's lookups; after another HTTP error the next ones go on. A search that fails (timeout, HTTP error, network error) counts the file as failed, not skipped, and a failed show search is not cached, so the next episode searches again. The same holds for the IMDb lookup the Plex template needs (`MetadataService.getExternalIds`): a failure fails the file and isn't cached, while a 404 or a record without an IMDb ID is a cached "no ID" (no tag). `TmdbProvider` reads every body through `TmdbHttp.getJson`, which turns Ktor's `NoTransformationFoundException` (an `UnsupportedOperationException`, which `MetadataService` skips as "not supported") into a plain failure naming the request.
4. **Consolidate** — `domain/matching/MatchRanker.rank()` (pure: it returns how each candidate scored and why none matched, and `MediaProcessor` prints them) groups results across providers by media type and TMDB ID (IMDb ID when there is none; never by title, so distinct titles that look alike stay apart), scores them (Levenshtein title similarity, year proximity, multi-provider agreement, provider confidence), and merges each group's IDs into a `CanonicalMedia` "golden record" (`consolidate`), best first. Matches below `MINIMUM_CONFIDENCE_SCORE` are rejected. Every weight and threshold of this ranking, and TMDB's alias-lookup policy (`ALT_TITLES_CONFIDENCE_THRESHOLD`, `MAX_ALT_TITLES_LOOKUPS`), is in `domain/matching/MatchScoring`, whose KDoc gives the formula; `MatchScoringTest` pins the scores, so a changed value is a deliberate behavior change.
5. **Format** — `domain/strategy/NamingStrategy` (sealed: `Plex`, `Jellyfin` (default); there are no user-defined templates) holds template strings; `domain/service/PathFormatter` renders placeholders like `{CleanTitle}`, `{season:2}` (zero-padding), and ID tags (`[tmdbid-{tmdbid}]`, `{imdb-{imdbid}}`) that drop out when their ID is missing. Each template renders one file or folder name.
6. **Organize** — `core/DefaultFileOrganizer` builds the final path and either `atomicMove`s or hardlinks (`OperationMode`, default `HARDLINK`), returning an `OrganizeOutcome`. It never touches the source itself at the target (or a hardlink to it): that is `AlreadyInPlace`, counted as skipped. A different file at the target is `TargetExists` (skipped), or `Replaced` with `-o`/`--overwrite` (hardlink to a temp name, then rename over it). Every target a file of the run was organized to or already was at is claimed for that run (`claimedTargets`, also in `--test`, where nothing is written): a later, different source for it is `TakenInThisRun` (skipped), even with `-o`, so the first file wins and `MOVE` can't lose it. "First" is well defined: `walkFiles` visits each directory in `FileNameOrder` (case-insensitive, numbers by value), never the file system's own listing order, which differs between platforms and file systems. A source whose folder is on another volume than the target's nearest existing folder fails before anything is created (`checkSameVolume`; for a symlinked source, the folder of the file it points at in `HARDLINK` mode, of the link in `MOVE` mode), since neither a hardlink nor a rename crosses volumes. `--test` performs a dry run, including those checks.

**Caching**: `core/MetadataRepository` is `MediaProcessor`'s only way to the providers: it owns the `MetadataService` and the run's `core/MetadataCache` (in-memory, per-run, mutex-guarded, typed keys). Seasons, episode groups and IMDb IDs are cached by its rules; the show cache keeps what `MediaProcessor`'s ranking decided, under the processor's `ShowKey`: the title and year searched, or, when found by the ID in the path, that ID with the title and year (its season and arc-season notes use that key too, since two shows may share a cleaned title). A show lookup that succeeded but matched nothing is negative-cached (`markShowUnmatched`); a failed one is not. Seasons are fetched whole (one API call) and cached by show identity (`ShowIdentity`: TMDB ID, else IMDb, else title) and season, so processing a season's worth of episodes costs one season fetch. A season TMDB reports as nonexistent (404) is cached as empty; a fetch that failed (timeout, HTTP or network error) is not cached, and the file counts as failed with the error. When an episode isn't in the TMDB season (typically anime split into cours that TMDB keeps as one season), `MediaProcessor` falls back to the show's TMDB episode groups (cached per show, like seasons: a failed lookup is not cached and fails the file) and `domain/service/EpisodeGroupMapper` maps the release's S/E onto TMDB's own episode, so the file gets TMDB numbering.

**Domain models**: `ParsedMediaInfo` (guess from filename) vs `CanonicalMedia` (verified truth from providers) — keep this distinction; `PathFormatter` receives both because parsed info supplies the `{version}` tags (resolution, quality, release group…) while canonical media supplies titles/years/IDs.

### Platform-specific code (`expect`/`actual`)

Only these are platform-specific; everything else must stay in `commonMain`:
- `core/FileSystemUtils.kt` — `expect fun createHardLink(...)` (Win32 API on Windows, POSIX `link` on Linux; links the file a symlink points at; never replaces an existing destination), `expect fun resolveSymbolicLink(...)` (the file a symlink finally points at; null for anything else), and `expect val PlatformFileSystem` (`kind`/`identity`/`list`/`createDirectories`/`atomicMove`/`delete`; `identity` is volume serial + file index on Windows, device + inode on Linux, and common `isSameFile` compares it). Use `PlatformFileSystem`, not kotlinx-io's `SystemFileSystem`, for anything touching the source tree or the library: kotlinx-io on mingw goes through the ANSI C runtime and fails on paths of 260+ characters (MAX_PATH). The Windows actual calls the wide Win32 API with `\\?\` paths through C wrappers in [src/nativeInterop/cinterop/win32.def](src/nativeInterop/cinterop/win32.def), because a `GetLastError()` made from Kotlin can come back as 0 after the runtime resets it.
- `data/HttpClientFactory.kt` — `expect fun createHttpClientEngine()`: Ktor's curl engine on both, with per-target setup (on Linux, the distro's CA bundle)

### Kotlin language features in use

- **Context parameters** (stable since Kotlin 2.4, no compiler flag needed): the logging system ([logging/Logger.kt](src/commonMain/kotlin/io/github/hospes/plexify/logging/Logger.kt)) passes `LoggingContext` implicitly via `context(_: LoggingContext)`. Nested pipeline steps use `indent { ... }` to increase log indentation — follow this pattern for any new logging inside the pipeline.
- **Test names:** Kotlin/Native rejects commas in backticked test function names (`Name contains illegal characters: ","`); spaces are fine.
