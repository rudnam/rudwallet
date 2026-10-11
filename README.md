# Wallet

**Wallet** tracks your money: accounts, categories, records, budgets, and a
monthly overview. Payment emails become records for you: forward your card's
payment emails to your private address and each one waits under *To review*
with the shop, amount and time filled in. Confirming one teaches it the shop's
category and the card's account. See [docs/email.md](docs/email.md). Records
exported from BudgetBakers' Wallet app (CSV) can be imported.

A Go API, a React frontend, and PostgreSQL, shipped as one Docker image.

- **Backend:** Go, standard library plus a PostgreSQL driver (`github.com/lib/pq`) and the Anthropic SDK (for the optional AI email reader).
- **Frontend:** React + TypeScript, bundled by Vite and embedded in the Go binary. Installable as a PWA.
- **Database:** PostgreSQL. Migrations are embedded SQL files, applied on startup.
- **Packaging:** one small, non-root Docker image (under 20 MB) with no shell.
- **Android:** the same site, packaged as an app (see [android/README.md](android/README.md)).

## Quick start

You need Docker. Node is only needed for frontend hot reload.

```sh
cp .env.example .env            # the defaults run it on this machine only
docker compose up -d --build    # http://localhost:8080
```

## The three commands

| Command | What it does | When |
|---|---|---|
| `./verify.sh` | Go vet, Go unit and integration tests, and browser tests, in throwaway containers | Before every commit or deploy. CI runs it too. |
| `./preview.sh` | Builds the working tree (or `--ref <branch>`) and runs it privately at http://localhost:8081 with a fresh database | To **see** a change, e.g. what an AI just did, before deploying it |
| `docker compose up -d --build` | Builds and (re)starts the real stack | To deploy |

### Previewing a change before you deploy it

```sh
./preview.sh                          # what's in the working tree right now
./preview.sh --ref ai/new-feature     # a branch, without checking it out
./preview.sh --restore backup.sql.gz  # with a copy of production data (see Backups)
./preview.sh down                     # stop it and delete its data
```

Sign in as `preview@example.com` / `preview-password`. The preview runs exactly the image production would run, in its own Compose project with its own database, so it cannot touch the live app or its data. `/healthz` shows which version you are looking at.

You can also skip running anything: `./verify.sh` saves screenshots of the main screens at phone and desktop width in `e2e/test-results/`, and CI attaches them to every push and pull request as the `e2e-results` artifact.

### Frontend hot reload

With the local stack running (Quick start), start Vite's dev server. It forwards `/api` to `localhost:8080`:

```sh
cd web && npm install && npm run dev  # http://localhost:5173
```

After Go changes, run `docker compose up -d --build` again.

## Deploying

[docs/deploy.md](docs/deploy.md) is a step-by-step path. Each step changes only the infrastructure, never the app:

1. **This machine:** `deploy/local.yml`.
2. **Your PC on the internet:** Cloudflare Tunnel, with no open ports (`deploy/cloudflare.yml`).
3. **A server with a public IP** (any VPS, or AWS EC2/Lightsail): Caddy with automatic HTTPS (`deploy/caddy.yml`).
4. **Managed AWS:** ECS Fargate + RDS + a load balancer, for zero-downtime deploys, several instances and managed backups.

### Backups

```sh
docker compose exec -T db pg_dump -U app app | gzip > backup-$(date +%F).sql.gz   # back up
gunzip -c backup.sql.gz | docker compose exec -T db psql -U app app                # restore into a fresh stack
```

## Configuration

All settings are environment variables (read in `internal/config`).

| Variable | Default | Meaning |
|---|---|---|
| `DATABASE_URL` | required | PostgreSQL connection URL |
| `ADDR` | `:8080` | Listen address |
| `LOG_LEVEL` | `info` | `debug`, `info`, `warn` or `error`. Logs are JSON on stdout. |
| `COOKIE_SECURE` | `true` | Send the session cookie over HTTPS only. Set `false` only for local http. |
| `TRUST_PROXY` | `false` | Read the client IP from `CF-Connecting-IP`, or the last `X-Forwarded-For` entry. Enable only when the app is reachable solely through that proxy. `docker-compose.yml` enables it. |
| `PUBLIC_ORIGIN` | empty | The site's public address, e.g. `https://app.example.com`. Accepted by the CSRF check even if a proxy changes `Host`. |
| `ALLOW_SIGNUP` | `true` | Set `false` to stop new accounts being created. |
| `AUTH_RATE_LIMIT` | `10` | Sign-in and sign-up attempts per minute per client IP. |
| `ANDROID_PACKAGE`, `ANDROID_CERT_FINGERPRINTS` | empty | Publish `/.well-known/assetlinks.json` for the Android app. See [android/README.md](android/README.md). |
| `INBOUND_DOMAIN`, `INBOUND_SECRET` | empty | Turn on email automation: users get `pay-<token>@INBOUND_DOMAIN`, and the mail provider's hook calls `POST /api/inbound/email` with the secret (24+ characters). See [docs/email.md](docs/email.md). |
| `ANTHROPIC_API_KEY` | empty | Let Claude read payment emails from senders without a built-in rule. |
| `ANTHROPIC_MODEL` | `claude-opus-5-5` | The model for that. |
| `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET` | empty | Show "Continue with Google" on the sign-in page. Needs `PUBLIC_ORIGIN`. See [Sign in with Google](#sign-in-with-google). |
| `SMTP_HOST`, `SMTP_PORT`, `SMTP_USERNAME`, `SMTP_PASSWORD`, `MAIL_FROM` | empty, `587` | Email a link to confirm each new account's address (port 587 with STARTTLS, or 465). `MAIL_FROM` is e.g. `Wallet <no-reply@example.com>`. Needs `PUBLIC_ORIGIN`. Without these, nobody is asked to confirm. |

### Sign in with Google

1. In the [Google Cloud console](https://console.cloud.google.com/apis/credentials), create a project, set up the OAuth consent screen (External; it asks only for the email address, so Google doesn't need to verify the app), then create an **OAuth client ID** of type *Web application*.
2. Add the authorized redirect URI `https://<your domain>/api/auth/google/callback`, the same origin as `PUBLIC_ORIGIN`.
3. Put the client ID and secret in `.env` as `GOOGLE_CLIENT_ID` and `GOOGLE_CLIENT_SECRET`, and redeploy.

A Google sign-in uses the account with the same email if there is one. If that account's email was never confirmed, its password stops working and its other sessions end, because whoever set the password never proved they own the address.

### Confirming email addresses

With the `SMTP_*` settings, new accounts get an email with a link, and the app shows a reminder until it's opened. People can keep using the app meanwhile. Signing in with Google counts as confirmed. Any SMTP provider works (Resend, Amazon SES, Postmark, or a Gmail account with an app password).

## How the code is organized

The code is split by **feature**: each one owns its SQL, HTTP handlers and page. The layers underneath are shared and small.

| Path | What it does |
|---|---|
| `cmd/server/` | Entry point: loads config, connects, migrates, serves, and shuts down cleanly on `SIGTERM`. |
| `internal/config/` | Reads and validates every setting from the environment. |
| `internal/db/` | Connection pool, migrations (`migrations/*.sql`, embedded), shared errors. |
| `internal/httpx/` | HTTP plumbing every feature shares: handlers that return errors, JSON in and out, middleware (security headers, CSRF origin check, logging, panic recovery), rate limiting. |
| `internal/account/` | Feature: sign-up, sign-in (password or Google), sessions, email confirmation, account deletion, and the `RequireUser` middleware. |
| `internal/mailer/` | Sends the app's own emails over SMTP. |
| `internal/wallet/` | Feature: accounts, categories, records, budgets, settings, and the BudgetBakers CSV import. |
| `internal/inbox/` | Feature: email automation. Forwarding addresses, the inbound webhook, and turning emails into records (`parse/` reads emails and knows the senders; `claude.go` is the AI fallback). |
| `internal/server/` | Wires the features into one handler; serves the web app. Its tests run the whole API against a real database. |
| `internal/testdb/` | Gives integration tests an empty, migrated database. |
| `web/src/` | The React app: `api.ts` (typed API client), `App.tsx` (sign-in gate and navigation), `wallet.tsx` (shared accounts and categories), `components/`, and `features/<name>/` pages. |
| `e2e/` | Playwright browser tests, and the throwaway stack that `verify.sh` and `preview.sh` use. |
| `deploy/` | Swappable ways to expose the app (local, Cloudflare Tunnel, Caddy), and `email-worker/` for inbound mail. |

### Adding a feature

Follow `internal/wallet` or `internal/inbox`; a feature is about five files:

1. `internal/db/migrations/00N_<name>.sql`: the tables. Never edit a migration that has been deployed; add a new one.
2. `internal/<name>/<name>.go`: a `Store` (all the SQL, every query scoped by user) and a `Handler` with `Register(mux, requireUser)`. Handlers return errors: `httpx.Errorf(404, "...")` reaches the user, and anything else becomes a logged 500.
3. One line in `internal/server/server.go` to register it.
4. `internal/server/<name>_test.go`: API tests against the real database.
5. `web/src/api.ts` (types and calls), `web/src/features/<name>/` (the page), and `e2e/tests/<name>.spec.js` (a browser test).

## API

| Method | Path | Auth | Returns |
|---|---|---|---|
| POST | `/api/signup`, `/api/login` | – | `{email}` and a session cookie. Body `{email, password}`. |
| POST | `/api/logout` | – | `204` |
| GET | `/api/me` | user | `{email, has_password, confirm_email}` |
| DELETE | `/api/me` | user | `204`. Deletes the account and all its data. Body `{password}`, or `{email}` for accounts without a password. |
| GET | `/api/auth/options` | – | `{google}`: which ways to sign in exist |
| GET | `/api/auth/google`, `/api/auth/google/callback` | – | Sign in with Google (redirects) |
| GET | `/api/verify-email?token=` | – | The link in a confirmation email; redirects to the app |
| POST | `/api/verify-email` | user | `204`. Sends the confirmation email again. |
| GET | `/api/wallet` | user | `{settings, accounts, categories, review_count}`. Creates default categories and a Cash account on first call. |
| PUT | `/api/settings` | user | `{currency, auto_confirm, default_account_id}`. The currency is fixed once records exist (`409`). |
| POST, PUT, DELETE | `/api/accounts[/{id}]` | user | Accounts: `{name, kind, initial_balance, color, archived}` |
| POST, PUT, DELETE | `/api/categories[/{id}]` | user | Categories: `{name, kind, icon, color}` |
| GET | `/api/transactions` | user | Records, newest first. Query: `from`, `to` (RFC 3339), `account_id`, `category_id` (or `none`), `q`, `status` |
| POST, PUT, DELETE | `/api/transactions[/{id}]` | user | Records: `{kind, amount, account_id, to_account_id, category_id, occurred_at, payee, note, status}`. Confirming an emailed record learns its shop and card. |
| GET, POST, PUT, DELETE | `/api/budgets[/{id}]` | user | Monthly budgets: `{name, category_id, amount}` |
| POST | `/api/import/budgetbakers` | user | Body: the CSV (`text/csv`). `{imported, duplicates, accounts_created, categories_created, problems}` |
| GET | `/api/inbox` | user | `{address, ai_enabled, emails}` |
| POST | `/api/inbox/address` | user | A new forwarding address |
| POST | `/api/inbox/emails` | user | Body: a raw email (`message/rfc822`), processed as if forwarded |
| POST | `/api/inbox/emails/{id}/dismiss` | user | `204` |
| POST | `/api/inbound/email` | `INBOUND_SECRET` | For the mail provider: raw email body, recipient in `X-Inbound-To`. `404` for unknown addresses. |
| GET | `/healthz` | – | `{status: "ok", version}` if the database is reachable, else `503` |

Errors are `{"error": "message"}` with a matching status code. Requests that change data must come from the site itself (the `Origin` check), and the session cookie is `HttpOnly` and `SameSite=Lax`.
