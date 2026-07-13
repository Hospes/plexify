---
name: release-notes
description: Generate release notes for a Plexify release in two formats — GitHub (technical markdown) and a plain-text announcement for forums/chats. Asks for the tag range, gathers git log + diff itself, classifies changes by Conventional Commit type, and suggests the next semver version. Use when asked to write or generate release notes for a version/tag/release. Output only — does not publish or tag.
---

# Release notes

Generate clear, accurate release notes for **Plexify** (Kotlin/Native CLI, Windows + Linux) in two formats: **GitHub Release** (technical) and **plain-text announcement** (for forums, Reddit, chats). **Output only — never publish or create tags**; the user publishes the GitHub Release themselves, which triggers the release workflow that builds and attaches the binaries.

## STEP 0 — Inputs (ask first)

Before gathering anything, confirm with the user:

1. **Range** — from which tag to which ref. **Always ask; never assume the previous tag.** The base is the *last released* tag, and the head is usually `HEAD` (the release tag typically doesn't exist yet — it's created by publishing the GitHub Release). List recent tags to help them choose:
   ```bash
   git tag --sort=-v:refname | head -10
   ```
2. **Formats** — GitHub only, or GitHub + plain-text announcement. Default to both if the user doesn't care.

Then **suggest the next version number** from the commit types in the range (the user may override): any `!`/`BREAKING CHANGE` → major bump; any `feat` → minor; only `fix`/`perf` → patch. Excluded types alone (docs/test/build/ci/chore/refactor) → no release needed; say so.

## STEP 1 — Gather git data yourself

Gather **both** log and diff — commit messages alone do not show the full picture:

```bash
git log <from>..<to> --pretty="%h %s%n%b"   # subjects + bodies (BREAKING CHANGE footers)
git diff --stat <from>..<to>                 # scope of each change
git diff <from>..<to> -- <path>              # read deeper where a message is vague or a change is large
```

Lead with the log (Conventional Commit subjects are structured signal), use `--stat` to gauge scope, and read actual diff hunks to confirm user-facing impact — especially for terse messages or large changes.

## STEP 2 — Classification by commit type

Commits follow **Conventional Commits** (`<type>(<scope>)?: <summary>`, scopes: `parser`, `metadata`, `naming`, `cli`, `core`, `cache`, `build`, `ci`):

| Type | Category |
|---|---|
| `feat` | ✨ New Features |
| `fix` | 🐛 Bug Fixes |
| `perf` | ⚡ Performance |
| `!` after type/scope, or `BREAKING CHANGE:` footer | ⚠️ Breaking Changes (listed **first**, in addition to its normal category) |
| `refactor` | Excluded — unless the diff shows a user-visible change, then reclassify as fix/enhancement |
| `docs`, `test`, `build`, `ci`, `chore` | Excluded from release notes |

Platform notes: the code is cross-platform from a single codebase, so changes are **not** tagged per platform. Only if a change touches solely `windowsMain`/`mingwX64` or `linuxMain`/`linuxX64` source sets (or one platform's packaging), prefix that bullet with `[Windows]` or `[Linux]`.

## STEP 3 — Accuracy guardrails (both formats)

1. **No fabrication.** Every line traces to a commit or diff hunk. Don't infer features from names or invent benefits.
2. **Conservative on uncertainty.** If user impact is unclear, describe it broadly ("More reliable metadata matching") rather than guessing.
3. **Synthesize, don't transcribe.** Combine related commits into one bullet (a feature plus its follow-up fixes is *one* feature). Rewrite into user impact: `fix(naming): strip trailing space when {version} placeholder is empty` → "Fixed file names ending with a stray space when no version tag applies."
4. **No internal artifacts in the announcement.** Commit hashes, scopes, and code identifiers are fine on GitHub; the plain-text version gets none of that.
5. **Breaking changes** must state what breaks and what the user must do (e.g. a renamed flag: old → new).

## STEP 4 — GitHub Release Notes (always)

Audience: users who install from GitHub — assume CLI-literate. Wrap the whole thing in one ` ```markdown ` fenced block.

1. **Title:** `# v{{version}}`
2. **`### Summary`** — 2–4 sentences on what this release brings and why it matters. Non-technical, no commit-speak.
3. **Categories** (omit empty ones), in order: ⚠️ Breaking Changes · ✨ New Features · ⚡ Performance · 🐛 Bug Fixes. Highest user impact first within each.
4. **Bullets:** readable impact statements. New CLI flags shown as code (`` `-y/--year` ``) with a one-line example where it helps. Reference commits sparingly — only for changes a user might want to inspect.
5. **Footer:** a `### Downloads` line reminding that binaries for **Windows**, **Linux x64**, and **Linux arm64** are attached below (the release workflow uploads them), plus a `**Full Changelog**: https://github.com/Hospes/plexify/compare/<from>...<to>` link.

## STEP 5 — Plain-text announcement (if requested)

Audience: forum/Reddit/chat readers who may never have heard of Plexify. English only, **no markdown** (no `#`, `**`, backticks — plain text that survives any forum).

- **Length:** 80–150 words.
- **Structure:** one opener line `Plexify v{{version}} is out — <one-phrase hook>.`; a one-sentence reminder of what Plexify is (organizes movie/TV files into a clean Plex/Jellyfin library structure); 3–6 short lines starting with `- ` covering the highlights in user language; one closing line with the release link: `https://github.com/Hospes/plexify/releases/tag/<version>`.
- **Tone:** friendly and factual, no marketing superlatives. Flags may be mentioned by name (e.g. "new --year option") since the audience is CLI users.
- Skip breaking-change migration detail — just flag it exists and point to the release page.

Output as one fenced plain-text block so it can be copied verbatim.

## STEP 6 — Self-check before output

- [ ] Range and version confirmed with the user; version bump matches the commit types found.
- [ ] Every line traces to a commit or diff hunk — nothing invented.
- [ ] Excluded types (docs/test/build/ci/chore, non-user-visible refactor) appear in neither format.
- [ ] Breaking changes listed first on GitHub and state the required user action.
- [ ] GitHub notes include the Downloads footer and Full Changelog link.
- [ ] Announcement (if present): plain text only, 80–150 words, ends with the release link.
- [ ] Nothing was published, tagged, or pushed.
