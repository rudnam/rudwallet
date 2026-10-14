// Shared fixtures. Import { test, expect } from here instead of from
// @playwright/test so every test gets them.
const base = require('@playwright/test');

const test = base.test.extend({
  // Fails the test if the page throws or logs a console error (which includes
  // Content-Security-Policy violations).
  page: async ({ page }, use) => {
    const errors = [];
    page.on('pageerror', (e) => errors.push('pageerror: ' + e.message));
    page.on('console', (m) => {
      // The browser logs every 4xx response as an error, but some are expected:
      // a 401 from /api/me means nobody is signed in, and a 400, 401, 403 or
      // 409 (e.g. a bad sign-in, a wrong password, or a refused change) is
      // shown in the form. Tests assert on what the user sees.
      if (m.type() === 'error' && !/status of 40[0139] /.test(m.text())) errors.push('console: ' + m.text());
    });
    await use(page);
    base.expect(errors, 'errors in the browser').toEqual([]);
  },
});

let n = 0;
/** A unique email per call, so parallel tests never share an account. */
function uniqueEmail() {
  return `user-${Date.now()}-${process.pid}-${n++}@example.com`;
}

const PASSWORD = 'a long password';

/**
 * Creates a fresh account through the API (the browser context gets its
 * session cookie) and opens the app signed in.
 */
async function signUp(page) {
  const email = uniqueEmail();
  const res = await page.request.post('/api/signup', { data: { email, password: PASSWORD } });
  base.expect(res.status(), await res.text()).toBe(201);
  // Open the emailed link, so screens look as they do for a settled account.
  await page.request.get(await confirmationLink(page.request, email));
  await page.goto('/app/');
  await base.expect(page.getByText('Total balance')).toBeVisible();
  return email;
}

const MAIL_API = process.env.MAIL_API || 'http://mail:8025'; // the mail catcher in e2e/compose.yml

/** The newest confirmation link the app emailed to "to", read from the mail catcher. */
async function confirmationLink(request, to) {
  let text = '';
  await base
    .expect(async () => {
      const found = await (await request.get(`${MAIL_API}/api/v1/search?query=${encodeURIComponent(`to:"${to}"`)}`)).json();
      base.expect(found.messages.length).toBeGreaterThan(0);
      text = (await (await request.get(`${MAIL_API}/api/v1/message/${found.messages[0].ID}`)).json()).Text;
    })
    .toPass({ timeout: 10_000 });
  const link = text.match(/https?:\/\/\S+\/api\/verify-email\?token=[\w-]+/);
  base.expect(link, text).not.toBeNull();
  return link[0];
}

const INBOUND_SECRET = 'e2e-inbound-secret-0123456789'; // matches e2e/compose.yml

/**
 * A PayPay Card usage alert (利用速報) laid out like the real ones: UTF-8
 * text, base64, and the sender's name in ISO-2022-JP. when is a Date.
 */
function payPayEmail({ merchant, yen, when, id }) {
  // PayPay writes Japan time, e.g. 2026年10月11日 13:21.
  const jst = new Date(when.getTime() + 9 * 3600_000);
  const stamp = `${jst.getUTCFullYear()}年${jst.getUTCMonth() + 1}月${jst.getUTCDate()}日 ${jst.getUTCHours()}:${String(jst.getUTCMinutes()).padStart(2, '0')}`;
  const body = `PayPayカード ゴールド（Visa）利用速報\n\n${merchant}\n${stamp}\n${yen.toLocaleString('en-US')}円\n\nPayPayで取引履歴とポイントを確認\n`;
  const subject = Buffer.from('PayPayカード ゴールド（Visa）利用速報').toString('base64');
  return [
    'From: =?ISO-2022-JP?Q?PayPay=1B=24B=25+!=3C=25I=1B=28B?= <paypaycard-info@mail.paypay-card.co.jp>',
    'To: user@example.com',
    `Subject: =?UTF-8?B?${subject}?=`,
    'MIME-Version: 1.0',
    'Content-Type: text/plain; charset=UTF-8',
    'Content-Transfer-Encoding: base64',
    `Message-ID: <${id}@ap-northeast-1.amazonses.com>`,
    `Date: ${when.toUTCString()}`,
    '',
    Buffer.from(body).toString('base64'),
  ].join('\r\n');
}

/** Delivers an email to the inbound webhook, as the mail provider would. */
async function deliverEmail(request, to, raw) {
  const res = await request.post('/api/inbound/email', {
    headers: { Authorization: `Bearer ${INBOUND_SECRET}`, 'X-Inbound-To': to, 'Content-Type': 'message/rfc822' },
    data: raw,
  });
  base.expect(res.status(), await res.text()).toBe(200);
  return res.json();
}

/** The signed-in user's forwarding address. */
async function inboxAddress(page) {
  const res = await page.request.get('/api/inbox');
  return (await res.json()).address;
}

module.exports = { test, expect: base.expect, signUp, uniqueEmail, PASSWORD, confirmationLink, payPayEmail, deliverEmail, inboxAddress };
