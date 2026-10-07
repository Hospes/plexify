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

Metadata providers need keys, resolved at **build time** in priority order: environment variables > `gradle.properties` > `local.properties` (untracked, in repo root). Keys: `TMDB_API_KEY`, `TMDB_API_ACCESS_TOKEN` (either one is enough for TMDB; the token is preferred), `TVDB_API_KEY`, `OMDB_API_KEY` (optional). They are baked into the binary via the `buildconfig` plugin (generated `BuildConfig` class).

At **runtime**, `data/tmdb/TmdbCredentials.resolve()` picks the user's own credentials (`--tmdb-access-token`/`--tmdb-api-key` or the `TMDB_API_ACCESS_TOKEN`/`TMDB_API_KEY` env vars) as a set, and falls back to the built-in `BuildConfig` values only when the user gave none — never a mix. Release binaries get the built-in key from the `TMDB_API_ACCESS_TOKEN`/`TMDB_API_KEY` repo secrets; it is shared by every user and extractable, so TMDB may revoke it. `App` checks the credentials once per run (`TmdbProvider.verifyCredentials()`) and fails with instructions on HTTP 401, distinguishing user vs built-in credentials. The TMDB terms (section 3) require the attribution notice `TMDB_ATTRIBUTION`, shown in `--help`, `--version` and the README — keep it.

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
3. **Search** — `domain/service/MetadataService` fans out concurrently to `MetadataProvider` implementations in `data/` (TMDB, IMDb; TVDB/OMDB scaffolded). Providers are selected dynamically: TMDB (or IMDb) is always primary, others are added only if the active naming template references an ID they can supply (`NamingStrategy.requiredMetadataFields()`).
4. **Consolidate** — `core/MediaProcessor.findAndConsolidateBestMatch()` groups results across providers by normalized title+year, scores them (Levenshtein title similarity, year proximity, multi-provider agreement, provider confidence), and merges the winning group's IDs into a `CanonicalMedia` "golden record". Matches below `MINIMUM_CONFIDENCE_SCORE` are rejected.
5. **Format** — `domain/strategy/NamingStrategy` (sealed: `Plex`, `Jellyfin` (default), `Custom`) holds template strings; `domain/service/PathFormatter` renders placeholders like `{CleanTitle}`, `{season:2}` (zero-padding), and `[...]` conditional blocks that drop out when a placeholder is missing.
6. **Organize** — `core/DefaultFileOrganizer` builds the final path and either `atomicMove`s or hardlinks (`OperationMode`, default `HARDLINK`). `--test` performs a dry run.

**Caching**: `data/MetadataCache` is in-memory, per-run, mutex-guarded. TV shows are cached by `title:year`; seasons are fetched whole (one API call) and cached by `showId:season`, so processing a season's worth of episodes costs one season fetch.

**Domain models**: `ParsedMediaInfo` (guess from filename) vs `CanonicalMedia` (verified truth from providers) — keep this distinction; `PathFormatter` receives both because parsed info supplies `{resolution}`/`{quality}`-style placeholders while canonical media supplies titles/years/IDs.

### Platform-specific code (`expect`/`actual`)

Only two things are platform-specific; everything else must stay in `commonMain`:
- `core/FileSystemUtils.kt` — `expect fun createHardLink(...)` (Win32 API on Windows, POSIX `link` on Linux)
- `data/HttpClientFactory.kt` — Ktor engine selection (curl on both, but per-target setup)

### Kotlin language features in use

- **Context parameters** (stable since Kotlin 2.4, no compiler flag needed): the logging system ([logging/Logger.kt](src/commonMain/kotlin/io/github/hospes/plexify/logging/Logger.kt)) passes `LoggingContext` implicitly via `context(_: LoggingContext)`. Nested pipeline steps use `indent { ... }` to increase log indentation — follow this pattern for any new logging inside the pipeline.
- **Test names:** Kotlin/Native rejects commas in backticked test function names (`Name contains illegal characters: ","`); spaces are fine.
