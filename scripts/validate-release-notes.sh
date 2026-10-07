#!/usr/bin/env bash
# ==============================================================================
# Release notes validator
#
# Checks the GitHub release notes that Gemini writes in the release workflow,
# following .claude/skills/release-notes, before they are published. Model output is
# checked, never trusted raw: the pipeline runs this as its own step, and notes that
# fail it are discarded in favour of GitHub's generated notes.
#
# Every failure line is written to be fed straight back to the generator as
# correction input, so it names the rule and what was found.
#
# Usage:
#   scripts/validate-release-notes.sh <notes.md> <from-tag> <to-tag>
# ==============================================================================
set -uo pipefail

readonly REPO_URL="https://github.com/Hospes/plexify"
# Shortest plausible real note (summary + downloads + changelog link), and a cap far
# below GitHub's 125,000-character limit that no honest release of this project needs.
readonly MIN_CHARS=200
readonly MAX_CHARS=20000

if [ $# -ne 3 ]; then
  echo "usage: $0 <notes.md> <from-tag> <to-tag>" >&2
  exit 2
fi
file="$1" from="$2" to="$3"

if [ ! -s "$file" ]; then
  echo "FAIL: $file does not exist or is empty. Write the GitHub release notes there."
  exit 1
fi

failures=0
fail() { echo "FAIL: $1"; failures=$((failures + 1)); }

# Characters, not bytes: emoji and non-ASCII titles count once.
chars="$(LC_ALL=C.UTF-8 wc -m < "$file" | tr -d ' ')"
[ "$chars" -ge "$MIN_CHARS" ] || fail "only $chars characters; the notes need at least a summary, downloads and changelog link ($MIN_CHARS+)."
[ "$chars" -le "$MAX_CHARS" ] || fail "$chars characters, over the $MAX_CHARS cap. Merge related bullets and cut the least important ones."

first_line="$(grep -m1 -v '^[[:space:]]*$' "$file")"
case "$first_line" in
  '### Summary') ;;
  '```'*) fail "the file starts with a code fence. In file mode write the Markdown itself, not a fenced block." ;;
  '# '*) fail "the file starts with a title ('$first_line'). The release title is set by the workflow; start with '### Summary'." ;;
  *) fail "the first line is '$first_line'; it must be '### Summary'." ;;
esac

last_line="$(grep -v '^[[:space:]]*$' "$file" | tail -n1)"
[[ "$last_line" == '```'* ]] && fail "the file ends with a code fence. Remove the wrapping fence."

grep -qx '### Downloads' "$file" || fail "missing the '### Downloads' section."

changelog="**Full Changelog**: ${REPO_URL}/compare/${from}...${to}"
grep -qF "$changelog" "$file" || fail "missing the exact changelog line: $changelog"

if grep -nE '\{\{|\}\}|TODO|TBD|<!--|lorem ipsum' "$file"; then
  fail "unfilled placeholder or comment on the line(s) above."
fi

if grep -nE '^[[:space:]]*[-*] +(feat|fix|perf|refactor|build|ci|chore|docs|test)(\([a-z]+\))?!?:' "$file"; then
  fail "raw Conventional Commit subjects on the line(s) above. Rewrite them as user impact."
fi

if [ "$failures" -gt 0 ]; then
  echo "$failures problem(s) in $file."
  exit 1
fi
echo "OK: $file ($chars characters) is valid for ${from}..${to}."
