# Notes for AI agents

Go API (`internal/`, one package per feature), PostgreSQL, and a React + TypeScript frontend (`web/src/`, built by Vite into `web/dist/` and embedded in the binary). The Android app (`android/`) is the same web app packaged with Capacitor, plus native widgets. README.md explains the layout, the API and how to add a feature; follow `internal/wallet` or `internal/inbox`. Deploys and migrations: `docs/deploy.md`. Email automation: `docs/email.md`. The Android app: `android/README.md`.

## Verify your work before calling it done

```sh
./verify.sh                 # everything: go vet, all Go tests, browser tests
./verify.sh go              # Go only
./verify.sh e2e -g wallet   # browser tests only; extra args go to `playwright test`
```

- It needs only Docker. It builds the working tree, runs it against a throwaway database, and tears everything down. It ends with `VERIFY PASSED` or `VERIFY FAILED`. CI runs the same script on every push and pull request.
- Plain `go test ./...` on the host **skips** the integration tests (they need `TEST_DATABASE_URL`). `./verify.sh go` runs them.
- The API tests live in `internal/server/<feature>_test.go` and run the whole API against a real database. Most feature packages have no tests of their own, so a change to `internal/wallet` is tested there.
- The Docker build type-checks the frontend, so a TypeScript error fails `./verify.sh`. Quick check on the host: `cd web && npm install && npm run typecheck`.
- When a browser test fails, its screenshot is at `e2e/test-results/<test>/test-failed-1.png`. Read it before guessing.
- Report what the run actually printed. If something was skipped or couldn't run, say so.

## Show the user what changed

The user reviews changes by looking at them, not by deploying them. When you change anything visible:

1. Extend a browser test in `e2e/tests/` (one file per feature; import `{ test, expect }` from `./fixtures`, which fails on any console error or CSP violation). For new screens, add a step to `e2e/tests/screens.spec.js`, which saves phone and desktop screenshots.
2. Run `./verify.sh`, then **Read the screenshots** in `e2e/test-results/` and check the change looks right at both widths.
3. On the user's machine, run `./preview.sh` and tell the user: "Preview at http://localhost:8081, sign in as preview@example.com / preview-password", plus what to click to see the change. In a cloud session the user can't open that address, so send them the screenshots instead.

## Rules

- **Never touch the live stack.** The Compose project named after this folder (`docker compose ...` in the repo root, which reads `.env`) may be production. Don't start, stop, rebuild or exec into it. `verify.sh` and `preview.sh` use their own projects (`<folder>-verify`, `<folder>-preview`).
- Never edit a migration in `internal/db/migrations/` that may have been deployed; add a new file numbered one above the highest. The number is the version recorded in `schema_migrations`, so never reuse one: `002` was deleted, and a database that ran it would silently skip a new `002_*`. Keep each migration compatible with the previous release (see docs/deploy.md, "Migrations").
- **Keep the API backward compatible.** Phones run the web app from their installed Android build, which can be months old, against the current server. Add fields and endpoints; don't rename, remove or change the meaning of existing ones.
- Every query on user data is scoped by user ID in the feature's `Store`. Another user's row is a 404, not a 403.
- Handlers return errors: `httpx.Errorf(status, msg)` for messages the user should see, and any other error for a logged 500. Don't leak internal errors to the client.
- Keep dependencies minimal: standard library first in Go; no new npm packages without a clear reason.
- When you add or change an endpoint, a setting or a package, update its table in README.md (API, Configuration, or How the code is organized).

## Environment gotchas

On the user's machine (WSL + Docker Desktop):

- Containers can't reach a server started in WSL, and `--network host` means the Docker VM, not WSL. Run things in Docker, as the scripts do.
- Playwright's browsers can't launch directly on this host (missing system libraries). Use `./verify.sh e2e`.

In Claude Code cloud sessions, `.claude/hooks/session-start.sh` starts Docker, makes the base images trust the sandbox's TLS proxy, and sets `DOCKER_BUILDKIT=0` so builds use those images. The scripts then work as above. If Docker isn't running, run the hook again (`CLAUDE_CODE_REMOTE=true CLAUDE_PROJECT_DIR=$PWD .claude/hooks/session-start.sh`).
