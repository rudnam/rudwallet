# Wallet

**Wallet** tracks your money: accounts, categories, records, budgets, a
monthly overview, and stats over any period with trends. Payment emails become records for you: forward your card's
payment emails to your private address and each one waits under *To review*
with the shop, amount and time filled in. Confirming one teaches it the shop's
category and the card's account. See [docs/email.md](docs/email.md). In the
Android app, Google Wallet's payment notifications do the same, and a payment
reported both ways is recorded once. PayPay's
transaction history (CSV, from the PayPay app) and records exported from
BudgetBakers' Wallet app (CSV) can be imported.

A Go API, a React frontend, and PostgreSQL, shipped as one Docker image.

- **Backend:** Go, standard library plus a PostgreSQL driver (`github.com/lib/pq`) and the Anthropic SDK (for the optional Claude features).
- **Frontend:** React + TypeScript, bundled by Vite and embedded in the Go binary. Installable as a PWA.
- **Database:** PostgreSQL. Migrations are embedded SQL files, applied on startup.
- **Packaging:** one small, non-root Docker image (under 20 MB) with no shell.
- **Android:** the same web app, packaged with Capacitor, plus native home screen widgets (this month, budgets, and a New record button) and a reader for Google Wallet's payment notifications (see [android/README.md](android/README.md)).

## Quick start

You need Docker. Node is only needed for frontend hot reload.

```sh
cp .env.example .env            # the defaults run it on this machine only
docker compose up -d --build    # http://localhost:8080 (the app is at /app/)
```

## The three commands

| Command | What it does | When |
|---|---|---|
| `./verify.sh` | Go vet, Go unit and integration tests, and browser tests, in throwaway containers | Before every commit or deploy. CI runs it too. |
| `./preview.sh` | Builds the working tree (or `--ref <branch>`) and runs it privately at http://localhost:8081 with a fresh database of sample data | To **see** a change, e.g. what an AI just did, before deploying it |
| `docker compose up -d --build` | Builds and (re)starts the local stack at http://localhost:8080 | To use it on this machine |

### Previewing a change before you deploy it

```sh
./preview.sh                          # what's in the working tree right now
./preview.sh --ref ai/new-feature     # a branch, without checking it out
./preview.sh --restore backup.sql.gz  # with a copy of production data (see Production)
./preview.sh down                     # stop it and delete its data
PREVIEW_MINUTES=0 ./preview.sh        # keep it until `down` (default: it stops after 120 minutes)
```

Sign in as `preview@example.com` / `preview-password`. A fresh preview comes with six months of sample records, accounts and budgets (`e2e/seed`) and a few payment emails to review. The preview runs exactly the image production would run, in its own Compose project with its own database, so it cannot touch the live app or its data. `/healthz` shows which version you are looking at. It cleans up after itself: it stops, data and all, after two hours, or when the Claude Code session in that folder ends. `verify.sh` tears its stack down when it exits, and clears what a killed run left before it starts.

You can also skip running anything: `./verify.sh` saves screenshots of the main screens at phone and desktop width in `e2e/test-results/`, and CI attaches them to every push and pull request as the `e2e-results` artifact.

### Frontend hot reload

With the local stack running (Quick start), start Vite's dev server. It forwards `/api` to `localhost:8080`:

```sh
cd web && npm install && npm run dev  # http://localhost:5173 (the introduction page is /landing.html)
```

After Go changes, run `docker compose up -d --build` again.

## Production

https://wallet.rudnam.com runs on AWS: one EC2 instance behind CloudFront, shared with the other rudnam apps. The infrastructure, backups and server access are in [rudnam/rudnam-infra](https://github.com/rudnam/rudnam-infra).

- **Deploy:** push to `main`. CI (`.github/workflows/ci.yml`) runs `./verify.sh`, builds the image, pushes it to ECR and starts it on the instance with `deploy/aws.yml`. A failed check stops the deploy.
- **Roll back:** Actions → CI → **Run workflow** on `main`, with the full SHA of an earlier commit. It reuses that commit's image, or rebuilds it.
- **Settings and secrets:** the production `.env` is the SSM parameter `/rudnam/wallet-app/env`, not a file in this repo. To change one, update the parameter and redeploy (rudnam-infra README, "App secrets").
- **Backups:** nightly database dumps (kept 30 days) and daily disk snapshots (7 days). To preview a change against real data:
  ```sh
  aws s3 ls --profile rudnam-ec2 s3://rudnam-backups-394567896817/wallet-app/
  aws s3 cp --profile rudnam-ec2 s3://rudnam-backups-394567896817/wallet-app/<dump>.sql.gz backup.sql.gz
  ./preview.sh --restore backup.sql.gz
  ```
- **Migrations** run on startup and only go forward, and a deploy briefly runs the old and new versions side by side. So every migration must keep the previous release working: adding a table, a nullable column or a column with a default is safe; renaming or dropping a column takes two releases (stop using it, then drop it). Don't roll back past a commit that changed the schema.

## Contributing

1. Fork the repository (or branch, if you have write access) and run it locally (Quick start).
2. Make the change, following `CLAUDE.md` (it applies to people too).
3. Run `./verify.sh` until it prints `VERIFY PASSED`, and look at the change with `./preview.sh`.
4. Open a pull request against `main`. CI runs the same checks and attaches screenshots; nothing deploys until it is merged.

You never need production credentials: pull requests, including ones from forks, run without them.

## Configuration

All settings are environment variables (read in `internal/config`).

| Variable | Default | Meaning |
|---|---|---|
| `DATABASE_URL` | required | PostgreSQL connection URL |
| `ADDR` | `:8080` | Listen address |
| `LOG_LEVEL` | `info` | `debug`, `info`, `warn` or `error`. Logs are JSON on stdout. |
| `COOKIE_SECURE` | `true` | Send the session cookie over HTTPS only. Set `false` only for local http. |
| `TRUST_PROXY` | `false` | Read the client IP from the last `X-Forwarded-For` entry, which the reverse proxy adds. Enable only when the app is reachable solely through that proxy. `docker-compose.yml` enables it; `deploy/local.yml` turns it off. |
| `PUBLIC_ORIGIN` | empty | The site's public address, e.g. `https://app.example.com`. Accepted by the CSRF check even if a proxy changes `Host`, and named as the issuer when MCP clients sign in. |
| `ALLOW_SIGNUP` | `true` | Set `false` to stop new accounts being created. |
| `DEMO` | as `ALLOW_SIGNUP` | Offer "Try the demo": each visitor gets a throwaway account with six months of sample data in their language, without signing up. It has no password, can't forward email, save API keys or make access tokens, and is deleted when they end it or after a day. |
| `AUTH_RATE_LIMIT` | `10` | Sign-in and sign-up attempts per minute per client IP. |
| `ANDROID_PACKAGE`, `ANDROID_CERT_FINGERPRINTS` | empty | The Android app's package, which Sign in with Google in the app returns to, and its signing keys, published in `/.well-known/assetlinks.json`. See [android/README.md](android/README.md). |
| `ANDROID_APK` | empty | Path of the Android app's APK. While the file exists, the introduction page offers it at `/download/wallet.apk`. `docker-compose.yml` sets `/downloads/wallet.apk`, mounted from `downloads/`, which `./android/build.sh` fills. |
| `INBOUND_DOMAIN`, `INBOUND_SECRET` | empty | Turn on email automation: users get `pay-<token>@INBOUND_DOMAIN`, and the mail provider's hook calls `POST /api/inbound/email` with the secret (24+ characters). See [docs/email.md](docs/email.md). |
| `ANTHROPIC_API_KEY` | empty | Let Claude read payment emails from senders without a built-in rule, for every user. Without it, users who add their own key in Settings → Claude get this for themselves. |
| `ANTHROPIC_MODEL` | `claude-opus-5-5` | The model for that, and for the Claude features users turn on with their own key. |
| `SECRETS_KEY` | empty | Encrypts what users save in the app: their Claude API keys (AES-256-GCM). 32+ characters, e.g. `openssl rand -base64 32`. Without it, users can't save a key. Keep it out of backups of the database, and don't change it: keys saved under an old one can't be read, and users are asked to add them again. |
| `ANTHROPIC_BASE_URL` | Anthropic's API | Where Claude is reached. The browser tests point it at a fake (`e2e/fake-claude.js`). |
| `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET` | empty | Show "Continue with Google" on the sign-in page. Needs `PUBLIC_ORIGIN`. See [Sign in with Google](#sign-in-with-google). |
| `SMTP_HOST`, `SMTP_PORT`, `SMTP_USERNAME`, `SMTP_PASSWORD`, `MAIL_FROM` | empty, `587` | Email a link to confirm each new account's address (port 587 with STARTTLS, or 465). `MAIL_FROM` is e.g. `Wallet <no-reply@example.com>`. Needs `PUBLIC_ORIGIN`. Without these, nobody is asked to confirm. |

### Sign in with Google

1. In the [Google Cloud console](https://console.cloud.google.com/apis/credentials), create a project, set up the OAuth consent screen (External; it asks only for the email address, so Google doesn't need to verify the app), then create an **OAuth client ID** of type *Web application*.
2. Add the authorized redirect URI `https://<your domain>/api/auth/google/callback`, the same origin as `PUBLIC_ORIGIN`.
3. Put the client ID and secret in `.env` as `GOOGLE_CLIENT_ID` and `GOOGLE_CLIENT_SECRET`, and redeploy.

A Google sign-in uses the account with the same email if there is one. If that account's email was never confirmed, its password stops working and its other sessions end, because whoever set the password never proved they own the address.

### Confirming email addresses

With the `SMTP_*` settings, new accounts get an email with a link, and the app shows a reminder until it's opened. People can keep using the app meanwhile. Signing in with Google counts as confirmed. Any SMTP provider works (Resend, Amazon SES, Postmark, or a Gmail account with an app password). The email is in Japanese for someone using the app in Japanese.

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
| `internal/wallet/` | Feature: accounts, categories, records (deleted ones kept a day so they can be restored), budgets, settings, and the PayPay and BudgetBakers CSV imports. |
| `internal/inbox/` | Feature: email automation. Forwarding addresses, the inbound webhook, and turning emails into records (`parse/` reads emails and knows the senders; `claude.go` is the AI fallback). |
| `internal/notify/` | Feature: payment notifications the Android app sends (Google Wallet), read into records (`parse.go` knows the apps). A payment already recorded from its email, an import or by hand is matched, not added. |
| `internal/ai/` | Feature: Claude with the user's own API key. Stores the key encrypted (`seal.go`), and suggests categories for uncategorised records (`claude.go`). |
| `internal/mcp/` | Feature: the MCP server at `/mcp`, so Claude (as a connector, Claude Desktop or Claude Code) and other MCP clients can read and tidy a user's records, with access tokens made in Settings or got by signing in (OAuth 2.1, `oauth.go`). |
| `internal/widget/` | Feature: the month summary the Android home screen widgets draw. |
| `internal/demo/`, `internal/sample/` | Feature: the demo. `sample` makes months of believable records, accounts and budgets in English or Japanese; `demo` fills a visitor's demo wallet with them. The demo account itself is `internal/account/demo.go`. `e2e/seed` fills the preview account with the same sample (`-lang ja` for Japanese). |
| `internal/server/` | Wires the features into one handler; serves the introduction page at `/` and the web app at `/app/`. Its tests run the whole API against a real database. |
| `internal/testdb/` | Gives integration tests an empty, migrated database. |
| `web/landing.html`, `web/src/landing.*` | The introduction page at `/`: plain HTML in the app's styles. It sends links meant for the app (`/#/records`, `/?email=…`) and installs from before the app moved on to `/app/`. |
| `web/src/` | The React app (`web/index.html`, served at `/app/`): `api.ts` (typed API client), `App.tsx` (sign-in gate and navigation), `wallet.tsx` (shared accounts and categories), `components/`, and `features/<name>/` pages. |
| `web/src/i18n.tsx`, `language.ts`, `locales/ja.ts` | English and Japanese. Text is written in English and passed through `t()`; `locales/ja.ts` has each one in Japanese, and a missing entry fails the type check. The language is the device's unless picked in Settings › Language or on the sign-in screen (kept in the browser). Server messages are translated in `locales/ja.ts` too. |
| `e2e/` | Playwright browser tests, and the throwaway stack that `verify.sh` and `preview.sh` use. |
| `deploy/` | Where the app runs: `local.yml` (this machine) and `aws.yml` (production), plus `email-worker/` for inbound mail. |

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
| POST | `/api/logout` | – | `204`. Ending a demo deletes it. |
| GET | `/api/me` | user | `{email, has_password, confirm_email, demo}` |
| DELETE | `/api/me` | user | `204`. Deletes the account and all its data. Body `{password}`, or `{email}` for accounts without a password. |
| GET | `/api/auth/options` | – | `{google, demo}`: which ways to sign in exist, and whether visitors can try a demo |
| POST | `/api/demo` | – | Body `{tz}` (the visitor's time zone). Signs in to a new demo account filled with sample data, named in the `Accept-Language` language; `201` with `/api/me`'s answer. Only when `DEMO` is on; rate-limited like sign-up. |
| GET | `/api/auth/google`, `/api/auth/google/callback` | – | Sign in with Google (redirects) |
| GET | `/api/verify-email?token=` | – | The link in a confirmation email; redirects to the app |
| POST | `/api/verify-email` | user | `204`. Sends the confirmation email again. |
| GET | `/api/wallet` | user | `{settings, accounts, categories, review_count}`. Creates default categories and a Cash account on first call, named in Japanese when `Accept-Language` prefers `ja`. |
| PUT | `/api/settings` | user | `{currency, auto_confirm, default_account_id}`. The currency is fixed once records exist (`409`). |
| POST, PUT, DELETE | `/api/accounts[/{id}]` | user | Accounts: `{name, kind, initial_balance, color, archived}` |
| POST, PUT, DELETE | `/api/categories[/{id}]` | user | Categories: `{name, kind, icon, color}` |
| GET | `/api/categories/missing` | user | The default categories the user doesn't have (added since they signed up, or deleted), to add with POST |
| GET | `/api/transactions` | user | Records, newest first. Query: `from`, `to` (RFC 3339), `account_id`, `category_id` (or `none`), `q`, `status` |
| POST, PUT, DELETE | `/api/transactions[/{id}]` | user | Records: `{kind, amount, account_id, to_account_id, category_id, occurred_at, payee, note, status}`. Confirming an emailed record learns its shop and card; with `remember: false` on the update, the shop's learned category is left as it was. A deleted record is kept for a day, for restore. |
| POST | `/api/transactions/bulk` | user | Changes many records at once. Body `{ids, action}`: `delete`, `categorize` with `category_id` (`null` clears it; only records of the category's kind change, never transfers), `move` with `account_id`, or `confirm` (records to review with a category and an amount, not flagged as possible duplicates; shops and cards are learned only if nothing was learned for them yet). Up to 2000 IDs. `{changed, skipped}` |
| POST | `/api/transactions/restore` | user | Undoes deletes from the last day. Body `{ids}`; each record comes back with its ID and is linked again to its emails, notifications and duplicates. One deleted with its account, or imported again since, stays gone. Up to 2000 IDs. `{restored}`, or 404 if none came back |
| GET | `/api/rules` | user | What confirmed payments taught the app: `{payees: [{key, payee, category_id, records}], cards: [{source, account_id, records}]}` |
| PUT, DELETE | `/api/rules/payees` | user | Set a shop's category (body `{payee, category_id}`, returns the rule), or forget it (`?payee=`, `204`) |
| PUT, DELETE | `/api/rules/cards` | user | Move a learned card to another account (body `{source, account_id}`), or forget it (`?source=`). `204` |
| GET | `/api/stats` | user | Confirmed income and expenses over a period (transfers count as neither). Query: `from`, `to` (RFC 3339, up to 20 years apart), `tz` (IANA zone for grouping days, default `UTC`), `account_id`. `{spent, income, categories: [{category_id, kind, amount, count}], days: [{day, spent, income}]}` |
| GET, POST, PUT, DELETE | `/api/budgets[/{id}]` | user | Monthly budgets: `{name, category_id, amount}` |
| POST | `/api/import/budgetbakers?preview=1` | user | Body: the CSV (`text/csv`). `{records, from, to, accounts, categories, new_categories, problems}`: each account with a suggested account, each category with a suggested one of the user's (`category_id`) or a new one (`new_name`); `new_categories` are the missing defaults |
| POST | `/api/import/budgetbakers` | user | Body: the CSV (`text/csv`; accounts and categories matched by name, created when missing), or JSON `{csv, accounts: [{name, account_id}], categories: [{name, kind, category_id, new_name}]}` with the choices from the preview. `{imported, duplicates, matched, accounts_created, categories_created, problems}` |
| POST | `/api/import/paypay?preview=1` | user | Body: PayPay's 取引履歴 CSV (`text/csv`), Japanese or English. `{records, from, to, methods, categories, new_categories, problems}`: each way of paying (取引方法) with a suggested account, and each shop with a suggested category (`learned`: the one chosen before) |
| POST | `/api/import/paypay` | user | Body: the same CSV, with `account=<id>:<取引方法>` per way of paying (`0`: a new account), or JSON `{csv, accounts: [{name, account_id}], categories: [{name, kind, category_id, new_name}]}`; a shop's expense category is remembered as a shop rule. Returns the import result; `matched` counts payments already recorded from emails or by hand |
| GET | `/api/inbox` | user | `{address, ai_enabled, emails}` |
| POST | `/api/inbox/address` | user | A new forwarding address |
| POST | `/api/inbox/emails` | user | Body: a raw email (`message/rfc822`), processed as if forwarded |
| POST | `/api/inbox/emails/{id}/dismiss` | user | `204` |
| POST | `/api/inbound/email` | `INBOUND_SECRET` | For the mail provider: raw email body, recipient in `X-Inbound-To`. `404` for unknown addresses. |
| GET | `/api/notifications` | user | `{notifications}`: the latest payment notifications and what each became (`payment`, `matched`, `ignored`, `unparsed`) |
| POST | `/api/notifications` | user | For the Android app. Body `{notifications: [{package, when, title, text}]}` (`when` in ms, at most 50). Returns `{notifications}` in the same order; one sent before is marked `duplicate` |
| GET | `/api/ai` | user | `{can_save, has_key, key_hint, key_unreadable, uncategorized}`: the user's Claude key (only its last 4 characters). `can_save` is false without `SECRETS_KEY`; `key_unreadable` means the saved key must be added again. |
| PUT, DELETE | `/api/ai/key` | user | Body `{key}`: an Anthropic API key, saved encrypted once Claude accepts it (`400` if not, `409` without `SECRETS_KEY`) |
| POST | `/api/ai/categorize?offset=` | user | `{suggestions, remaining}`: a category Claude suggests (or `null`) for the next 100 payees whose confirmed records have none, skipping `offset`. Changes nothing. `409` without a key. |
| POST | `/api/ai/categorize/apply` | user | Body `{items: [{payee, kind, category_id}]}`. Files each payee's uncategorised records and remembers the category for its emails. `{updated}` |
| GET, POST, DELETE | `/api/mcp/tokens[/{id}]` | user | Access tokens for MCP clients: `{id, name, created_at, last_used_at, signed_in}`; `signed_in` marks a connection made by signing in. `POST {name}` also returns the `token`, once. Deleting a connection revokes its tokens. |
| POST | `/mcp` | MCP token | The MCP server (Streamable HTTP, JSON responses only). `Authorization: Bearer <token>`; without one, `401` with `WWW-Authenticate: Bearer resource_metadata="…"` for clients that sign in. Tools: `get_overview`, `list_records`, `list_uncategorized_payees`, `categorize_payees`, `set_record_category`, `set_record_account`, `create_category`, `delete_category`, `spending_by_category`. |
| GET | `/.well-known/oauth-protected-resource[/mcp]`, `/.well-known/oauth-authorization-server` | – | Sign-in metadata for MCP clients (RFC 9728, RFC 8414). The issuer is `PUBLIC_ORIGIN`, or the request's own address. |
| POST | `/oauth/register` | – | Dynamic client registration (RFC 7591). JSON `{client_name, redirect_uris}`; redirect URIs must be https, http on localhost, or a reverse-domain app scheme. `201 {client_id, …}`. 10 a minute per IP. |
| GET | `/oauth/authorize` | – | Checks the client, its redirect URI and PKCE (S256 only), then sends the browser to the web app's Connect page (`/app/#/connect`). |
| GET, POST | `/api/mcp/authorize` | user | For the Connect page. `GET ?client_id&redirect_uri` gives `{client_name, redirect_host}`. `POST {client_id, redirect_uri, code_challenge, state, allow}` gives `{redirect_to}`: back to the client with a one-time code (5 minutes), or `error=access_denied`. `409` at 20 connections. |
| POST | `/oauth/token` | – | Form-encoded. `grant_type=authorization_code` (with `code`, `code_verifier`, `redirect_uri`, `client_id`) or `refresh_token`. `{access_token, token_type, expires_in: 3600, refresh_token}`; each refresh replaces both tokens. |
| GET | `/api/widget` | user | For the Android widgets. Query: `from`, `to` (RFC 3339). `{currency, currency_exponent, spent, balance, budget, budgets, review_count}` |
| GET | `/`, `/app/` | – | The introduction page; the web app, which also answers every path under `/app/` |
| GET | `/api/downloads` | – | `{android}`: `{url, size, updated}` of the APK, or `null` if there is none |
| GET | `/download/wallet.apk` | – | The Android app's APK (`ANDROID_APK`), or `404` |
| GET | `/healthz` | – | `{status: "ok", version}` if the database is reachable, else `503` |

Errors are `{"error": "message"}` with a matching status code. Requests that change data must come from the site itself (the `Origin` check), and the session cookie is `HttpOnly` and `SameSite=Lax`.
