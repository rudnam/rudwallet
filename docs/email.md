# Email automation

Each user gets a private address such as `pay-k3x9q2m4ab@in.example.com`.
They set up a Gmail filter that forwards payment emails (for example PayPay Card's
利用速報 usage alerts) to it, and every payment becomes a record waiting for review.
Confirming one teaches the app the shop's category and the card's account. With
auto-confirm on, later payments at known shops go straight in.

```
Gmail filter ──forward──▶ Cloudflare Email Routing ──▶ Email Worker ──POST──▶ /api/inbound/email
                         (catch-all on in.example.com)   (deploy/email-worker)   (INBOUND_SECRET)
```

Nothing here reads anyone's mailbox. The app only sees emails users choose to forward.

## What it understands

| Sender | Becomes |
|---|---|
| PayPay Card usage alert (利用速報), `paypay-card.co.jp` | A payment: shop, amount, time (JST), card. `／NFC` shops are marked "Tap to pay". |
| PayPay Card verification (本人認証), statements (請求) and adverts | Skipped with a reason, so nothing is counted twice. |
| Gmail forwarding confirmation | A **Confirm forwarding** button under *To review*. |
| Anything else | Read by Claude if `ANTHROPIC_API_KEY` is set; otherwise it waits under *To review* for the user. |

The rules live in `internal/inbox/parse/classify.go`, with fixtures in
`internal/inbox/parse/testdata/`. To add a sender, add a fixture (anonymised: change
names, amounts and addresses), a test, and a case in `Classify`. To check a real
email without committing it:

```sh
PARSE_REAL_EMAIL=~/Downloads/message.eml go test -run TestRealEmail -v ./internal/inbox/parse
```

The same email delivered twice is recorded once (matched by `Message-ID`). A payment
with the same amount within a day of a record from somewhere else (e.g. one typed in
by hand) is flagged as a possible duplicate and never auto-confirmed.

## Turning it on

You need a domain on Cloudflare (the same one as the Cloudflare Tunnel is fine) and
the app reachable over HTTPS.

1. **Pick the inbound domain**, e.g. `in.example.com`, and a secret:
   `openssl rand -base64 32`. In `.env` set
   ```
   INBOUND_DOMAIN=in.example.com
   INBOUND_SECRET=<the secret>
   ```
   and redeploy. Settings → Email automation now shows each user's address.
2. **Create the Worker.** In the Cloudflare dashboard, go to **Workers & Pages → Create → Worker**,
   paste `deploy/email-worker/worker.js`, and deploy. Under **Settings → Variables and Secrets** add
   `APP_URL` (e.g. `https://wallet.example.com`) and `INBOUND_SECRET` (type *Secret*).
3. **Route the mail.** In the domain's **Email → Email Routing**, enable it for
   `in.example.com` (Cloudflare adds the MX and SPF records), then under **Routing rules →
   Catch-all address** choose **Send to a Worker** and pick the worker.
4. **Test it.** Send any email to `pay-<your token>@in.example.com`. It should appear in
   Settings → Recent emails within seconds. If not, check the Worker's logs
   (**Workers → your worker → Logs**) and `docker compose logs app`.

The webhook answers 404 for unknown addresses, so the Worker rejects mail to them.
Users can get a new address at any time, and the old one stops working at once.

## The AI reader (optional)

With `ANTHROPIC_API_KEY` set, emails no rule recognises are sent to Claude, which
answers in a fixed JSON format whether the email is a payment and, if so, with
the amount, currency, shop, time and payment method. Notes:

- Only those emails are sent to Anthropic. PayPay Card alerts are read by the
  built-in rules and never leave your server.
- The model is `ANTHROPIC_MODEL` (default `claude-opus-5-5`, at low effort). A typical
  receipt costs around a cent on Opus; `claude-haiku-5-5` costs a few percent of that.
- A failed or declined read leaves the email under *To review* for the user, never a
  guessed record. Payments in another currency get amount 0 and a note, and the user
  enters what it cost.

## Without a domain

Users can still paste an email's source (Gmail: ⋮ → *Show original*) under
Settings → Email automation → *Test with an email*. It goes through exactly the same rules.
