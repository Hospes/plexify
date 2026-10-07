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
- Merge with **rebase** (history is linear, no merge commits); head branches are deleted automatically after merge.
- Release by pushing a tag on `main` once CI is green there (see below); never publish a GitHub Release by hand.

### API keys

Metadata providers need keys, resolved at **build time** in priority order: environment variables > `gradle.properties` > `local.properties` (untracked, in repo root). Keys: `TMDB_API_KEY`, `TMDB_API_ACCESS_TOKEN` (either one is enough for TMDB; the token is preferred). They are baked into the binary via the `buildconfig` plugin (generated `BuildConfig` class).

At **runtime**, `data/tmdb/TmdbCredentials.resolve()` picks the user's own credentials (`--tmdb-access-token`/`--tmdb-api-key` or the `TMDB_API_ACCESS_TOKEN`/`TMDB_API_KEY` env vars) as a set, and falls back to the built-in `BuildConfig` values only when the user gave none — never a mix. Release binaries get the built-in key from the `TMDB_API_ACCESS_TOKEN`/`TMDB_API_KEY` repo secrets; it is shared by every user and extractable, so TMDB may revoke it. `App` checks the credentials once per run (`TmdbProvider.verifyCredentials()`) and fails with instructions on HTTP 401, distinguishing user vs built-in credentials. The TMDB terms (section 3) require the attribution notice `TMDB_ATTRIBUTION`, shown in the `--help` epilog and the README (with the TMDB logo) — keep it. `--version` prints only the version.

### Versioning & releases

`version` is derived from `git describe --tags` plus a branch-based suffix (`release/*` → `-RC`, `feature/*` → `-FEATURE`) — see [build.gradle.kts](build.gradle.kts). A release is cut by pushing a semver tag (e.g. `0.2.1`, no `v` prefix) on `main`: [.github/workflows/release.yml](.github/workflows/release.yml) builds Linux x64/arm64 + Windows binaries and, in parallel, has Gemini CLI write the notes by following the `release-notes` skill. [scripts/validate-release-notes.sh](scripts/validate-release-notes.sh) checks those notes, and the GitHub Release is created last, with the binaries and the notes together. If notes generation or validation fails, the release still ships with GitHub's generated notes and a warning on the run; use the `release-notes` skill locally to rewrite them. Re-running the workflow uses the workflow files from the tagged commit, so a workflow fix needs the tag moved to a commit that has it.

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
2. **Parse** — `domain/service/MediaFilenameParser` (pure object, regex-tiered) extracts title/year/season/episode/resolution/quality/HDR/edition from filenames into `ParsedMediaInfo.Movie` or `.Episode`. The parent directory name is used as a fallback for season detection. This is the most test-covered area (`src/commonTest`).
3. **Search** — `domain/service/MetadataService` fans out concurrently to `MetadataProvider` implementations in `data/` (currently only TMDB). Providers are selected dynamically: TMDB is always primary, others are added only if the active naming template references an ID they can supply (`NamingStrategy.requiredMetadataFields()`). Every TMDB call has connect/request timeouts (`TmdbTimeouts`; files are processed sequentially, so a stall would block the run) and is retried on HTTP 429 and, once, on a timeout; a timeout fails as `MetadataTimeoutException` with a short message that never includes the URL, and a search that times out counts the file as failed, not skipped.
4. **Consolidate** — `core/MediaProcessor.findAndConsolidateBestMatch()` groups results across providers by normalized title+year, scores them (Levenshtein title similarity, year proximity, multi-provider agreement, provider confidence), and merges the winning group's IDs into a `CanonicalMedia` "golden record". Matches below `MINIMUM_CONFIDENCE_SCORE` are rejected.
5. **Format** — `domain/strategy/NamingStrategy` (sealed: `Plex`, `Jellyfin` (default), `Custom`) holds template strings; `domain/service/PathFormatter` renders placeholders like `{CleanTitle}`, `{season:2}` (zero-padding), and `[...]` conditional blocks that drop out when a placeholder is missing.
6. **Organize** — `core/DefaultFileOrganizer` builds the final path and either `atomicMove`s or hardlinks (`OperationMode`, default `HARDLINK`), returning an `OrganizeOutcome`. It never touches the source itself at the target (or a hardlink to it): that is `AlreadyInPlace`, counted as skipped. A different file at the target is `TargetExists` (skipped), or `Replaced` with `-o`/`--overwrite` (hardlink to a temp name, then rename over it). Every target a file of the run was organized to or already was at is claimed for that run (`claimedTargets`, also in `--test`, where nothing is written): a later, different source for it is `TakenInThisRun` (skipped), even with `-o`, so the first file wins and `MOVE` can't lose it. `--test` performs a dry run, including those checks.

**Caching**: `data/MetadataCache` is in-memory, per-run, mutex-guarded. TV shows are cached by `title:year`; seasons are fetched whole (one API call) and cached by `showId:season`, so processing a season's worth of episodes costs one season fetch. When an episode isn't in the TMDB season (typically anime split into cours that TMDB keeps as one season), `MediaProcessor` falls back to the show's TMDB episode groups (cached per show) and `domain/service/EpisodeGroupMapper` maps the release's S/E onto TMDB's own episode, so the file gets TMDB numbering.

**Domain models**: `ParsedMediaInfo` (guess from filename) vs `CanonicalMedia` (verified truth from providers) — keep this distinction; `PathFormatter` receives both because parsed info supplies `{resolution}`/`{quality}`-style placeholders while canonical media supplies titles/years/IDs.

### Platform-specific code (`expect`/`actual`)

Only these are platform-specific; everything else must stay in `commonMain`:
- `core/FileSystemUtils.kt` — `expect fun createHardLink(...)` (Win32 API on Windows, POSIX `link` on Linux; never replaces an existing destination) and `expect fun isSameFile(...)` (file identity: volume serial + file index on Windows, device + inode on Linux)
- `data/HttpClientFactory.kt` — Ktor engine selection (curl on both, but per-target setup)
- `core/FileSystemUtils.kt` — `expect val PlatformFileSystem` (`kind`/`list`/`createDirectories`/`atomicMove`/`delete`). Use it, not kotlinx-io's `SystemFileSystem`, for anything touching the source tree or the library: kotlinx-io on mingw goes through the ANSI C runtime and fails on paths of 260+ characters (MAX_PATH). The Windows actual calls the wide Win32 API with `\\?\` paths through C wrappers in [src/nativeInterop/cinterop/win32.def](src/nativeInterop/cinterop/win32.def), because a `GetLastError()` made from Kotlin can come back as 0 after the runtime resets it.

### Kotlin language features in use

- **Context parameters** (stable since Kotlin 2.4, no compiler flag needed): the logging system ([logging/Logger.kt](src/commonMain/kotlin/io/github/hospes/plexify/logging/Logger.kt)) passes `LoggingContext` implicitly via `context(_: LoggingContext)`. Nested pipeline steps use `indent { ... }` to increase log indentation — follow this pattern for any new logging inside the pipeline.
- **Test names:** Kotlin/Native rejects commas in backticked test function names (`Name contains illegal characters: ","`); spaces are fine.
