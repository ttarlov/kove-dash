#!/usr/bin/env bash
# dev.sh — feature / PR / merge driver for kove-dash. See docs/WORKFLOW.md.
#
#   tools/dev.sh gate                 build + unit tests + harness/secrets guards
#   tools/dev.sh branch <slug>        fresh feature branch off up-to-date main
#   tools/dev.sh pr "<title>"         gate, push (gh HTTPS token), open PR for current branch
#   tools/dev.sh merge <PR#>          squash-merge an approved PR, then ff-pull main
#
# Two hard-won rules are encoded here and are NOT optional:
#   1. The SSH key passphrase is lost, so pushes go over the gh HTTPS token, never `git push`.
#   2. `merge` refuses to run without an explicit human OK (merge-deploy-approval). Claude must
#      get a per-action OK from Taras before invoking it; the confirm prompt is the backstop.
#
# Bash 3.2 compatible (macOS ships no newer bash on PATH).
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

# --- config ------------------------------------------------------------------
# HTTPS push URL — bypasses the dead SSH key; the gh token (keyring) authenticates it.
PUSH_URL="https://github.com/ttarlov/kove-dash.git"
# JDK 17 for Gradle, matching CONTRIBUTING.md / the OBD project.
default_jdk17="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
[[ -z "${JAVA_HOME:-}" && -d "$default_jdk17" ]] && export JAVA_HOME="$default_jdk17"

die()  { echo "" >&2; echo "dev.sh: ABORT — $*" >&2; exit 1; }
step() { echo ""; echo "=== $* ==="; }

# =============================================================================
# gate — must be green before any push or merge
# =============================================================================
cmd_gate() {
    local app_dir="$repo_root/app"
    [[ -x "$app_dir/gradlew" ]] || die "no gradlew at $app_dir"

    local pass=() fail=()
    run() {
        local name="$1"; shift
        echo ""; echo "==> $name"
        if "$@"; then pass+=("$name"); else fail+=("$name"); fi
    }

    run "assembleDebug"     bash -c "cd '$app_dir' && ./gradlew :app:assembleDebug"
    run "testDebugUnitTest" bash -c "cd '$app_dir' && ./gradlew :app:testDebugUnitTest"
    run "harness-guard"     cmd_harness_guard
    run "secrets-guard"     cmd_secrets_guard

    echo ""; echo "================ GATE SUMMARY ================"
    local s
    for s in "${pass[@]-}"; do [[ -n "$s" ]] && echo "  PASS  $s"; done
    for s in "${fail[@]-}"; do [[ -n "$s" ]] && echo "  FAIL  $s"; done
    echo "============================================="
    if [[ ${#fail[@]} -gt 0 ]]; then echo "GATE: FAIL"; return 1; fi
    echo "GATE: PASS"
}

# Fails if the debug probe harness is present in app sources. This is the guard that would
# have caught #36 (harness bundled into a feature PR). The harness lives on branch
# tools/notify-probe-harness; set ALLOW_HARNESS=1 there to work on it.
cmd_harness_guard() {
    if [[ "${ALLOW_HARNESS:-0}" == "1" ]]; then
        echo "  ALLOW_HARNESS=1 — skipping harness guard"; return 0
    fi
    local src="app/app/src/main/java/com/kovedash/app"
    # Symbols that only exist to drive the dash from adb for probing — never on trunk.
    local hits
    hits="$(git grep -n --untracked -E \
        'testNotify|runTestNotify|testRaw|runTestRaw|ACTION_TEST_NOTIFY|ACTION_TEST_RAW|EXTRA_NOTIFY_|EXTRA_RAW|fun appNotify|"--es raw"|"--es notify"' \
        -- "$src" 2>/dev/null || true)"
    if [[ -n "$hits" ]]; then
        echo "  debug harness symbols found in app sources:" >&2
        echo "$hits" >&2
        echo "  (if intentionally on tools/notify-probe-harness, run with ALLOW_HARNESS=1)" >&2
        return 1
    fi
    echo "  clean — no harness symbols in app sources"
}

# Fails if a gitignored secret file is tracked or staged.
cmd_secrets_guard() {
    local bad
    bad="$(git ls-files -- 'app/local.properties' '_private/*' 2>/dev/null || true)"
    if [[ -n "$bad" ]]; then
        echo "  tracked secret/private files:" >&2; echo "$bad" >&2; return 1
    fi
    echo "  clean — no tracked secrets"
}

# =============================================================================
# branch — fresh feature branch off up-to-date main
# =============================================================================
cmd_branch() {
    local slug="${1:-}"
    [[ -n "$slug" ]] || die "usage: dev.sh branch <slug>   (e.g. feat/foo or fix/bar)"
    git diff --quiet && git diff --cached --quiet || die "working tree dirty — commit or stash first"
    step "Update main"
    git checkout main
    git pull "$PUSH_URL" main --ff-only
    step "Create branch $slug"
    git checkout -b "$slug"
    echo "  on $slug off $(git rev-parse --short main)"
}

# =============================================================================
# pr — gate, push over HTTPS token, open PR
# =============================================================================
cmd_pr() {
    local title="${1:-}"
    [[ -n "$title" ]] || die "usage: dev.sh pr \"<title>\""
    local branch; branch="$(git rev-parse --abbrev-ref HEAD)"
    [[ "$branch" != "main" ]] || die "refusing to PR from main — make a feature branch first"
    git diff --quiet && git diff --cached --quiet || die "uncommitted changes — commit before PR"

    step "Gate before PR"
    cmd_gate || die "gate failed — fix before opening a PR"

    step "Push $branch over HTTPS token"
    git push "$PUSH_URL" "$branch:$branch"

    step "Open PR"
    gh pr create --base main --head "$branch" --title "$title" --fill \
        || die "gh pr create failed (transient GraphQL errors happen — retry)"
    gh pr view --json number,url -q '"PR #\(.number)  \(.url)"'
}

# =============================================================================
# merge — approved PR -> squash to main -> ff-pull. Gated on explicit human OK.
# =============================================================================
cmd_merge() {
    local pr="${1:-}"
    [[ -n "$pr" ]] || die "usage: dev.sh merge <PR#>"

    step "Pre-merge checks on PR #$pr"
    local mergeable; mergeable="$(gh pr view "$pr" --json mergeable -q .mergeable 2>/dev/null || true)"
    echo "  mergeable: ${mergeable:-unknown}"
    [[ "$mergeable" == "MERGEABLE" ]] || die "PR #$pr is not MERGEABLE (state: ${mergeable:-unknown}) — resolve first"

    # merge-deploy-approval: never merge to main without an explicit per-action OK.
    if [[ "${CONFIRM:-}" != "yes" ]]; then
        echo ""
        echo "  This SQUASH-MERGES PR #$pr into main and deletes the branch."
        printf "  Type the PR number to confirm: "
        read -r reply
        [[ "$reply" == "$pr" ]] || die "confirmation mismatch — aborted, nothing merged"
    fi

    step "Squash-merge PR #$pr"
    gh pr merge "$pr" --squash --delete-branch || die "gh pr merge failed"

    step "Fast-forward local main"
    git checkout main
    git pull "$PUSH_URL" main --ff-only
    echo ""
    echo "dev.sh: PR #$pr merged; main at $(git rev-parse --short HEAD)"
}

# -----------------------------------------------------------------------------
case "${1:-}" in
    gate)          shift; cmd_gate "$@" ;;
    harness-guard) shift; cmd_harness_guard "$@" ;;
    secrets-guard) shift; cmd_secrets_guard "$@" ;;
    branch)        shift; cmd_branch "$@" ;;
    pr)            shift; cmd_pr "$@" ;;
    merge)         shift; cmd_merge "$@" ;;
    ""|-h|--help)
        grep -E '^#( |$)' "$0" | sed -E 's/^# ?//' | sed -n '1,13p' ;;
    *) die "unknown command '$1' (try: gate | branch | pr | merge)" ;;
esac
