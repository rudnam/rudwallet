# Notes for AI agents

Go API (`internal/`, one package per feature), PostgreSQL, and a React + TypeScript frontend (`web/src/`, built by Vite into `web/dist/` and embedded in the binary). The Android app (`android/`) is a native app (Kotlin, Compose, Hilt, Room; Android's recommended architecture), with no web view and nothing of `web/` inside it; Android work follows `android/README.md`, "Native app". README.md explains the layout, the API and how to add a feature; follow `internal/wallet` or `internal/notify`. Deploys and migrations: README.md, "Production". The Android app, and its reader for payment notifications: `android/README.md`.

## Verify your work before calling it done

**On local, always run targeted commands first — never run full `./verify.sh` or full test suites locally if a targeted check or CI can do it.** Whenever you want a full check, **use GitHub CI instead**: CI runs `./verify.sh` (in the `verify` job) and Android tests + lint + release build (in the `android` job) on every pull request and on pushes to `main`:

1. Commit, push your branch (never `main`), and open a **draft** pull request if the branch doesn't have one yet (CI doesn't run on plain branch pushes).
2. Follow it with `gh pr checks <number> --watch`.
3. Read the `verify` / `android` check results: the `verify` log ends with `VERIFY PASSED` or `VERIFY FAILED`, and screenshots are in the run's `e2e-results` artifact.
4. Report what CI actually printed (and say the result came from CI, not from this machine). If something was skipped or couldn't run, say so.

### Targeted local checks (run these first while working)

Run only the narrowest check for what you changed, and let CI run the full suite:

- **Go:** `go vet ./internal/<pkg>` and `go test ./internal/<pkg> -run <TestName>`. Plain `go test` on the host **skips** the integration tests (they need `TEST_DATABASE_URL`); API tests live in `internal/server/<feature>_test.go` and run the whole API against a real database (most feature packages have no tests of their own, so a change to `internal/wallet` is tested there). Use `./verify.sh go` only when local integration tests are specifically needed, otherwise let CI run them.
- **Web:** `cd web && npm run typecheck` (run `npm install` first if needed; the Docker build type-checks the frontend, so a TypeScript error fails `./verify.sh`).
- **Browser (E2E):** `./verify.sh e2e tests/<file>.spec.js` or `./verify.sh e2e -g <pattern>` (narrow to the relevant spec or test rather than running all browser tests). When a browser test fails, read its screenshot at `e2e/test-results/<test>/test-failed-1.png` before guessing.
- **Android:** `cd android && ./gradlew :<module>:testDebugUnitTest --tests '*<TestName>'` (and `./gradlew lintNative` when checking lint). `./verify.sh` tests the server and the website, not `android/`; let CI's `android` job run the full test suite, lint, and release build.

### Full local verify (last resort only)

Run full `./verify.sh` locally **only** when the user explicitly asks for it, or when you can't push (no remote access, or the user doesn't want a PR yet):

```sh
./verify.sh                 # everything: Go lint, vet, govulncheck and tests, browser tests (prefer CI)
./verify.sh go              # Go only (unit + integration tests)
./verify.sh e2e -g wallet   # browser tests only; extra args go to `playwright test`
```

- It needs only Docker. It builds the working tree, runs it against a throwaway database, and tears everything down. It ends with `VERIFY PASSED` or `VERIFY FAILED`, or, if another run in this folder is in progress, stops at once with `VERIFY BUSY` (exit 3) without touching it: two runs at once would share one database.
- Report what the run actually printed. If something was skipped or couldn't run, say so.
- **If this machine can't run it, let CI run it.** That means: it printed `VERIFY BUSY`, Docker won't start, or the user says the machine is busy.

## Show the user what changed

The user reviews changes by looking at them, not by deploying them. When you change anything visible:

1. Extend a browser test in `e2e/tests/` (one file per feature; import `{ test, expect }` from `./fixtures`, which fails on any console error or CSP violation). For new screens, add a step to `e2e/tests/screens.spec.js`, which saves phone and desktop screenshots.
2. Verify (on CI, as above, or with targeted `./verify.sh e2e ...`), then **Read the screenshots** from CI's `e2e-results` artifact (or `e2e/test-results/` after a local run) and check the change looks right at both widths.
3. On the user's machine, run `./preview.sh` and tell the user: "Preview at http://localhost:8081, sign in as preview@example.com / preview-password", plus what to click to see the change. In a cloud session the user can't open that address, so send them the screenshots instead. You don't need to stop it: it stops by itself after two hours (`PREVIEW_MINUTES`) and when the branch's pull request is merged or closed (or run `./preview.sh down`).

## Rules

- **Production is on AWS, deployed only by CI** when `main` changes (`.github/workflows/ci.yml`; infrastructure in rudnam/rudnam-infra). Nothing on this machine is production: the Compose project named after this folder is local development. `verify.sh` and `preview.sh` use their own projects (`<folder>-verify`, `<folder>-preview`). Don't push to `main` or run AWS commands against production unless asked.
- Never edit a migration in `internal/db/migrations/` that may have been deployed; add a new file numbered one above the highest. The number is the version recorded in `schema_migrations`, so never reuse one: `002` was deleted, and a database that ran it would silently skip a new `002_*`. Keep each migration compatible with the previous release (README.md, "Production").
- **Fetch and merge `origin/main` into your branch before starting work** (worktrees and local branches may be branched from a stale `main`), before choosing a migration number, and again before opening a pull request. Merge rather than rebase a branch you've pushed.
- **Keep the API backward compatible.** Phones run their installed Android build, which can be months old (older ones still run the web app bundled in them), against the current server. Add fields and endpoints; don't rename, remove or change the meaning of existing ones.
- Every query on user data is scoped by user ID in the feature's `Store`. Another user's row is a 404, not a 403.
- Handlers return errors: `httpx.Errorf(status, msg)` for messages the user should see, and any other error for a logged 500. Don't leak internal errors to the client.
- Keep dependencies minimal: standard library first in Go; no new npm packages without a clear reason.
- When you add or change an endpoint, a setting or a package, update its table in README.md (API, Configuration, or How the code is organized).
- **Label a pull request `android-release` when it changes what ships inside the Android app**: anything under `android/` (other than its README and scripts). Changes to `web/` don't ship in the app any more. Once it's merged and CI passes on `main`, `.github/workflows/android-release.yml` builds, signs and publishes a new APK, and every phone on a recent build is offered it. Don't label server-only, test-only or docs-only changes: phones would be nagged to update for nothing. Say in the PR description whether you labelled it and why. For user-visible Android changes, include a `## What's new` (and optionally `## 新機能`) bullet list in the PR description: `android/changelog.py` puts those into the app's update notice, the GitHub release and the Play Store release notes, and leaves out PRs without them, so technical changes stay internal.

## Environment gotchas

On the user's machine (WSL + Docker Desktop):

- Containers can't reach a server started in WSL, and `--network host` means the Docker VM, not WSL. Run things in Docker, as the scripts do.
- Playwright's browsers can't launch directly on this host (missing system libraries). Use `./verify.sh e2e`.
- `JAVA_HOME`, `ANDROID_HOME`, and local toolchain paths are configured in `~/.bashrc` (not sourced by non-interactive shells), and Docker-based scripts (`./verify.sh`, `./preview.sh`) need host Docker access (or fall back to CI).
