#!/usr/bin/env bash
# ==============================================================================
# Release gate: CI passed on the tagged commit
#
# The release workflow runs this first, so a tag on a commit that is not on main, or
# whose CI failed, was never run, or never finished, ships nothing. It passes once the
# commit is on main and the latest GitHub Actions check run of each required check
# concluded `success` on it.
#
# A tag pushed right after a merge finds CI still running on that commit, so the
# gate waits for it, up to WAIT_SECONDS. A check that never appears (a commit CI did
# not run on) fails after APPEAR_SECONDS, unless a CI run for the commit is still
# pending; a failed check fails at once.
#
# Every failure is an ::error:: annotation and a line in the job summary that says
# what to do next.
#
# Usage:
#   GH_REPO=owner/repo scripts/check-ci-passed.sh <commit-sha>
# Tunable (for testing): CI_WAIT_SECONDS, CI_APPEAR_SECONDS, CI_POLL_SECONDS.
# ==============================================================================
set -euo pipefail

# The job names in .github/workflows/ci.yml, which are the required status checks of
# the "main – safety" ruleset. Renaming a job there means renaming it here too.
readonly REQUIRED_CHECKS=("Build & test (Linux)" "Build & test (Windows)")
# CI jobs time out after 30 minutes; the rest is margin for a busy runner queue.
readonly WAIT_SECONDS="${CI_WAIT_SECONDS:-2400}"
# GitHub creates the check runs, queued, within seconds of the push that starts CI.
readonly APPEAR_SECONDS="${CI_APPEAR_SECONDS:-300}"
readonly POLL_SECONDS="${CI_POLL_SECONDS:-30}"
# Consecutive API failures tolerated while polling, so one blip doesn't fail a release.
readonly MAX_API_FAILURES=3

if [ $# -ne 1 ] || [ -z "${GH_REPO:-}" ]; then
  echo "usage: GH_REPO=owner/repo $0 <commit-sha>" >&2
  exit 2
fi
sha="$1"
summary="${GITHUB_STEP_SUMMARY:-/dev/null}"

fail() {
  echo "::error title=$1::$2"
  echo "> [!CAUTION]"$'\n'"> **$1.** $2" >> "$summary"
  exit 1
}

# One line per check name: "<name>\t<status>\t<conclusion>\t<url>" of its latest run.
# A re-run or a second workflow run adds check runs to the same commit; the highest
# ID is the newest. Only GitHub Actions counts: another app could report a check of
# the same name.
latest_runs() {
  local runs
  runs="$(gh api --paginate "repos/$GH_REPO/commits/$sha/check-runs?filter=all&per_page=100" \
    --jq '.check_runs[] | select(.app.slug == "github-actions")
          | [.id, .name, .status, (.conclusion // "none"), .html_url] | @tsv')" || return 1
  printf '%s\n' "$runs" | sort -t$'\t' -k1,1n | awk -F'\t' 'NF { last[$2] = $2 FS $3 FS $4 FS $5 }
    END { for (name in last) print last[name] }'
}

# Whether a CI run for a push of the commit exists and hasn't finished. Its check
# runs can be missing until it starts: ci.yml's concurrency group holds the run of a
# second, quick push to main as pending until the first one finishes. An API error
# counts as no, which leaves the APPEAR_SECONDS limit in charge.
ci_run_pending() {
  local count
  count="$(gh api "repos/$GH_REPO/actions/runs?head_sha=$sha&event=push&per_page=100"     --jq '[.workflow_runs[] | select(.path == ".github/workflows/ci.yml" and .status != "completed")]
          | length')" || return 1
  [ "$count" -gt 0 ]
}

# Joins its arguments with "; ".
join() { local out; out="$(printf '%s; ' "$@")"; printf '%s' "${out%; }"; }

# The tree CI tested must be the tree released. A pull request's checks test its
# merge with the main of that time, not its head, and a fork's pull request runs its
# own ci.yml; so only a commit on main counts: its tip or an ancestor.
if ! on_main="$(gh api "repos/$GH_REPO/compare/main...$sha" --jq .status)"; then
  fail "Could not read CI results" "$sha could not be compared with main through the GitHub API. Re-run this workflow."
fi
case "$on_main" in
  identical | behind) ;;
  *) fail "Tag is not on main" "$sha is not on main (compared with main it is '$on_main'), so its CI results, if any, did not test this tree. Nothing was built or published. Tag a commit on main." ;;
esac

start=$SECONDS
api_failures=0
echo "Checking $(join "${REQUIRED_CHECKS[@]}") on $sha"
while true; do
  if ! runs="$(latest_runs)"; then
    api_failures=$((api_failures + 1))
    [ "$api_failures" -lt "$MAX_API_FAILURES" ] ||
      fail "Could not read CI results" "The check runs of $sha could not be read from the GitHub API ($api_failures attempts). Re-run this workflow."
    echo "::warning::Could not read the check runs of $sha; retrying."
    sleep "$POLL_SECONDS"
    continue
  fi
  api_failures=0

  waiting=() missing=()
  for name in "${REQUIRED_CHECKS[@]}"; do
    line="$(printf '%s\n' "$runs" | awk -F'\t' -v n="$name" '$1 == n')"
    if [ -z "$line" ]; then
      missing+=("$name")
      waiting+=("$name (no run yet)")
      continue
    fi
    IFS=$'\t' read -r _ status conclusion url <<< "$line"
    if [ "$status" != completed ]; then
      waiting+=("$name ($status)")
    elif [ "$conclusion" != success ]; then
      fail "CI did not pass" "'$name' concluded '$conclusion' on $sha ($url). Nothing was built or published. Fix it on main and tag the fixed commit; for a flaky failure, re-run that CI job and then re-run this workflow."
    fi
  done

  if [ ${#waiting[@]} -eq 0 ]; then
    echo "CI passed on $sha: $(join "${REQUIRED_CHECKS[@]}")"
    echo "CI passed on \`$sha\`: $(join "${REQUIRED_CHECKS[@]}")." >> "$summary"
    exit 0
  fi

  elapsed=$((SECONDS - start))
  if [ ${#missing[@]} -gt 0 ] && [ "$elapsed" -ge "$APPEAR_SECONDS" ] && ! ci_run_pending; then
    fail "No CI run for this commit" "$sha has no '${missing[0]}' check from GitHub Actions, and no CI run for it is pending. CI runs on each push to main and tests only the commit pushed, so a commit from the middle of a merged pull request has none. Tag the tip of main once its CI is green."
  fi
  if [ "$elapsed" -ge "$WAIT_SECONDS" ]; then
    fail "CI still running" "CI on $sha did not finish within $((WAIT_SECONDS / 60)) minutes (waiting for: $(join "${waiting[@]}")). Once it is green, re-run this workflow."
  fi
  echo "$((elapsed / 60))m$((elapsed % 60))s: waiting for $(join "${waiting[@]}")"
  sleep "$POLL_SECONDS"
done
