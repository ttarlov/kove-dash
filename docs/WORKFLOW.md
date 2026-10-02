# Feature / PR / merge workflow

`tools/dev.sh` drives every change from branch to merged `main`. It encodes two things that
have bitten this repo and are not optional:

1. **The SSH key passphrase is lost.** Plain `git push` prompts for it and fails. Every push in
   this script goes over the gh HTTPS token instead (`https://github.com/ttarlov/kove-dash.git`).
2. **The debug probe harness must never ride to `main` again.** The gate fails if harness
   symbols appear in app sources (see #36, where the harness was bundled into a feature PR).

## The four commands

```bash
tools/dev.sh gate              # build + unit tests + harness/secrets guards
tools/dev.sh branch <slug>     # fresh feature branch off up-to-date main
tools/dev.sh pr "<title>"      # gate, push, open PR for the current branch
tools/dev.sh merge <PR#>       # squash-merge an approved PR, then ff-pull main
```

Typical loop:

```bash
tools/dev.sh branch feat/street-line     # off a freshly pulled main
# ...write code, commit (Conventional Commits: feat/fix/docs/... scopes app/proto/re)...
tools/dev.sh gate                        # run as often as you like while working
tools/dev.sh pr "feat(nav): current-street line (msg_id=7)"
# ...wait for checks...
tools/dev.sh merge 42                    # only with an explicit OK (see below)
```

## The gate

`gate` runs four steps and prints a PASS/FAIL summary; any FAIL fails the gate.

| Step | What it checks |
|---|---|
| `assembleDebug` | `./gradlew :app:assembleDebug` — the debug APK compiles |
| `testDebugUnitTest` | `./gradlew :app:testDebugUnitTest` — protocol encode/decode unit tests |
| `harness-guard` | no debug-probe symbols in `app/app/src/main` (`testNotify`, `testRaw`, `ACTION_TEST_*`, `EXTRA_RAW`, `appNotify`, adb `--es raw`/`--es notify`) |
| `secrets-guard` | `app/local.properties` and `_private/` are not tracked |

JDK 17 is exported automatically (`/opt/homebrew/opt/openjdk@17/...`) if `JAVA_HOME` is unset.

### Working on the harness itself

The probe harness lives on branch `tools/notify-probe-harness` (and in history at `284e4ac`).
There, the harness symbols are supposed to be present, so run the gate with the guard off:

```bash
ALLOW_HARNESS=1 tools/dev.sh gate
```

Never merge that branch into `main`.

## Merging requires an explicit OK

Per the standing rule, **nothing merges to `main` without a per-action OK from Taras.**
`merge` enforces this two ways:

- It aborts unless `gh` reports the PR as `MERGEABLE`.
- It prompts for the PR number and only proceeds on an exact match. For a non-interactive run
  the human can set `CONFIRM=yes`, but that is the human's call to make, not Claude's.

Claude must get a per-action go-ahead from Taras before running `merge` at all; the prompt is
the backstop, not the authorization.

## What's hardware-gated

A green gate means it compiles and the unit tests pass — it does **not** mean the feature works
on the dash. Native rendering, BLE handshake timing, and projection still need the bike. Call
out in the PR how a protocol change was validated: unit test, emulator, or real hardware (and
firmware). See `CONTRIBUTING.md` for the full bench-vs-hardware split.

## adb raw-fire quoting (for on-dash testing)

When firing a frame by hand, wrap the whole `adb` command in double quotes and single-quote the
JSON, or the device shell brace-expands `{...,...}` and mangles it:

```bash
adb shell "am broadcast -n com.kovedash.app/.service.NavTestReceiver --es raw '{\"msg_id\":7,\"street\":\"Pearl Street\"}'"
```

That entry point only exists on the harness branch — on `main` the receiver takes the nav/alt/
ride extras only.
