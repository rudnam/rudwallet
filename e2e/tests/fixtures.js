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
      // A test that takes the browser offline (context.setOffline) sees its requests fail.
      if (m.type() === 'error' && !/status of 40[0139] |ERR_INTERNET_DISCONNECTED/.test(m.text())) errors.push('console: ' + m.text());
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

/** The link in the newest email to "to" that has one matching pattern, read from the mail catcher. */
async function emailedLink(request, to, pattern) {
  let link = null;
  await base
    .expect(async () => {
      const found = await (await request.get(`${MAIL_API}/api/v1/search?query=${encodeURIComponent(`to:"${to}"`)}`)).json();
      for (const m of found.messages) {
        const text = (await (await request.get(`${MAIL_API}/api/v1/message/${m.ID}`)).json()).Text;
        link = text.match(pattern);
        if (link) break;
      }
      base.expect(link, `no link like ${pattern} emailed to ${to}`).not.toBeNull();
    })
    .toPass({ timeout: 10_000 });
  return link[0];
}

/** The newest confirmation link the app emailed to "to". */
function confirmationLink(request, to) {
  return emailedLink(request, to, /https?:\/\/\S+\/api\/verify-email\?token=[\w-]+/);
}

/** The newest password reset link the app emailed to "to". */
function resetLink(request, to) {
  return emailedLink(request, to, /https?:\/\/\S+\/app\/#\/reset\?token=[\w-]+/);
}

/**
 * Makes the page act as the Android app (web/src/native.ts): Capacitor sees
 * a native platform, and the native plugins the test needs answer from
 * here. build is the installed versionCode; installer, the app that
 * installed it. icCard is what a transit IC card held to the phone reads
 * as ({idm, history}; icEntry), for a phone with NFC turned on; tapICCard
 * taps one. Native calls are kept in window.calls ("Plugin.method"), and
 * links opened in the phone's browser in window.opened. nativeNav, if set,
 * is an app build with native navigation (android/…/web/NativeNavPlugin.kt):
 * its config ({nativePages, appearance, language}) is what the app says at
 * start, and what the web app asks of it is kept in window.native
 * ([['go', {path}], ['overlay', {open}], …]); nativeShow and nativeBack
 * send the app's events. Call before the first page.goto.
 */
async function asAndroidApp(page, { build = 3, installer = 'com.google.android.packageinstaller', icCard = null, nativeNav = null } = {}) {
  await page.addInitScript(
    ({ build, installer, icCard, nativeNav }) => {
      window.native = [];
      const asked = (name) => (o) => void window.native.push([name, o ?? {}]);
      const answers = {
        'App.getInfo': () => ({ name: 'RudWallet', id: 'com.rudnam.wallet', build: String(build), version: '2.0' }),
        'Updates.installer': () => ({ installer }),
        'Browser.open': ({ url }) => void window.opened.push(url),
        'ICCard.status': () => ({ nfc: !!icCard, enabled: !!icCard }),
        'ICCard.read': () => icCard,
        'ICCard.cancel': () => undefined,
        'ICCard.removeListener': ({ callbackId }) => void delete window.listeners[callbackId],
        'NativeNav.config': () => nativeNav,
        'NativeNav.go': asked('go'),
        'NativeNav.overlay': asked('overlay'),
        'NativeNav.setAppearance': asked('setAppearance'),
        'NativeNav.setLanguage': asked('setLanguage'),
        'NativeNav.removeListener': ({ callbackId }) => void delete window.listeners[callbackId],
      };
      window.listeners = {}; // callbackId: {event: "Plugin.event", callback}
      window.calls = [];
      window.opened = [];
      window.CapacitorCustomPlatform = { name: 'android', plugins: {} };
      window.Capacitor = {
        PluginHeaders: [
          { name: 'App', methods: [{ name: 'getInfo', rtype: 'promise' }] },
          { name: 'Updates', methods: [{ name: 'installer', rtype: 'promise' }] },
          { name: 'Browser', methods: [{ name: 'open', rtype: 'promise' }] },
          {
            name: 'ICCard',
            methods: [
              ...['status', 'read', 'cancel', 'removeListener'].map((name) => ({ name, rtype: 'promise' })),
              { name: 'addListener', rtype: 'callback' },
            ],
          },
          ...(nativeNav
            ? [
                {
                  name: 'NativeNav',
                  methods: [
                    ...['config', 'go', 'overlay', 'setAppearance', 'setLanguage', 'removeListener'].map((name) => ({ name, rtype: 'promise' })),
                    { name: 'addListener', rtype: 'callback' },
                  ],
                },
              ]
            : []),
        ],
        nativeCallback: (plugin, method, options, callback) => {
          window.calls.push(`${plugin}.${method}`);
          const id = String(Object.keys(window.listeners).length + 1);
          window.listeners[id] = { event: `${plugin}.${options.eventName}`, callback };
          return id;
        },
        nativePromise: async (plugin, method, options) => {
          window.calls.push(`${plugin}.${method}`);
          return answers[`${plugin}.${method}`](options);
        },
      };
    },
    { build, installer, icCard, nativeNav },
  );
}

/** Taps a transit IC card to the phone, as the Android app reports it (a page set up with asAndroidApp). */
/** The app shows a page in the web view (asAndroidApp with nativeNav). */
async function nativeShow(page, path) {
  await page.evaluate((path) => {
    for (const l of Object.values(window.listeners)) if (l.event === 'NativeNav.show') l.callback({ path });
  }, path);
}

/** The app's back gesture while the web app has a sheet or dialog open. */
async function nativeBack(page) {
  await page.evaluate(() => {
    for (const l of Object.values(window.listeners)) if (l.event === 'NativeNav.back') l.callback({});
  });
}

/** What the web app asked of the app so far: [['go', {path}], …]. */
function nativeAsked(page) {
  return page.evaluate(() => window.native);
}

async function tapICCard(page, read) {
  await page.evaluate((read) => {
    for (const l of Object.values(window.listeners)) if (l.event === 'ICCard.tapped') l.callback(read);
  }, read);
}

/**
 * One entry of a transit IC card's history, as the card stores it (see
 * internal/wallet/importic.go): terminal and process codes, the day in
 * October 2026, the balance after it, its sequence number and, for a
 * shop, the time [h, m, s]. codes sets bytes 6-9: a train's stations, or
 * a bus company.
 */
function icEntry(terminal, process, day, balance, seq, time, codes) {
  const b = Buffer.alloc(16);
  b[0] = terminal;
  b[1] = process;
  b[4] = (26 << 1) | (10 >> 3);
  b[5] = ((10 & 7) << 5) | day;
  if (time) {
    const [h, m, s] = time;
    b[6] = (h << 3) | (m >> 3);
    b[7] = ((m & 7) << 5) | (s >> 1);
  }
  if (codes) b.set(codes, 6);
  b.writeUInt16LE(balance, 10);
  b.writeUInt16BE(seq, 13);
  return b.toString('hex');
}

/**
 * A card's history, newest first as the card gives it: a fare where the
 * history starts (¥1,000 left), a ¥2,000 top-up, ¥210 from 新宿 to 渋谷,
 * a ¥210 bus fare on 京王バス, ¥150 at a shop, and a ¥50 refund, leaving
 * ¥2,480.
 */
const icCardRead = {
  idm: '01120312AB34CD56',
  history: [
    icEntry(0x16, 0x04, 4, 2480, 105),
    icEntry(0xc7, 0x46, 3, 2430, 104, [13, 45, 30]),
    icEntry(0x05, 0x0d, 3, 2580, 103, null, [0x0c, 0x4c]),
    icEntry(0x16, 0x01, 2, 2790, 102, null, [0x25, 0x0a, 0xce, 0x26]),
    icEntry(0x08, 0x02, 2, 3000, 101),
    icEntry(0x16, 0x01, 1, 1000, 100),
  ],
};

const PAYPAY = 'jp.ne.paypay.android.app';

/**
 * A PayPay Card payment notification as the PayPay app words it, with
 * full-width colons and the time in Japan time. when is a Date.
 */
function payPayCard({ merchant, yen, when }) {
  const jst = new Date(when.getTime() + 9 * 3600_000);
  const stamp = `${jst.getUTCFullYear()}年${jst.getUTCMonth() + 1}月${jst.getUTCDate()}日 ${jst.getUTCHours()}:${String(jst.getUTCMinutes()).padStart(2, '0')}`;
  return { package: PAYPAY, when: when.getTime(), title: '支払いを受け付けました', text: `お店：${merchant}\n金額：${yen.toLocaleString('en-US')}円\n日時：${stamp}` };
}

/** Sends notifications as the Android app would, and returns what each became. */
async function sendNotifications(request, ...notifications) {
  const res = await request.post('/api/notifications', { data: { notifications } });
  base.expect(res.status(), await res.text()).toBe(200);
  return (await res.json()).notifications;
}

module.exports = { test, expect: base.expect, signUp, asAndroidApp, uniqueEmail, PASSWORD, confirmationLink, resetLink, PAYPAY, payPayCard, sendNotifications, icCardRead, icEntry, tapICCard, nativeShow, nativeBack, nativeAsked };
