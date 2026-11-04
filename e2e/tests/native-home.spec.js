// The Android app's native Wallet (android/…/screens), from the web app's
// side: the switch in Settings, when the web app shows and hides it, and
// the pages and records it asks the web app to open. How it looks is
// tested natively (android/app/src/test; CI keeps the screenshots).
const { test, expect, signUp, asAndroidApp, nativeOpen } = require('./fixtures');

const nativeWallet = (page) => page.evaluate(() => window.nativeHome);

/** Signs up, with the first-run checklist already put away (the native Wallet steps aside for it). */
async function settled(page) {
  await signUp(page);
  await page.evaluate(() => localStorage.setItem('wallet:setup-dismissed', '1'));
  await page.reload();
}

test('Settings turns the native Wallet on, and it covers Wallet only', async ({ page }) => {
  await asAndroidApp(page, { build: 9999, nativeHome: { on: false } });
  await settled(page);
  // Off by default: the web Wallet stays.
  await expect.poll(() => nativeWallet(page)).toBe('hidden');

  await page.goto('/app/#/settings');
  await page.getByRole('link', { name: /Native Wallet/ }).click();
  const box = page.getByRole('checkbox', { name: 'Use the native Wallet' });
  await expect(box).not.toBeChecked();
  await box.check();

  await page.goto('/app/#/home');
  await expect.poll(() => nativeWallet(page)).toBe('shown');
  // It's told the app's locale and appearance.
  expect(await page.evaluate(() => window.nativeLook)).toEqual({ locale: expect.stringMatching(/^en/), appearance: 'auto' });
  await page.goto('/app/#/records');
  await expect.poll(() => nativeWallet(page)).toBe('hidden');
  await page.goto('/app/#/home');
  await expect.poll(() => nativeWallet(page)).toBe('shown');

  // Turned off again, the web Wallet is back.
  await page.goto('/app/#/settings?section=native');
  await page.getByRole('checkbox', { name: 'Use the native Wallet' }).uncheck();
  await page.goto('/app/#/home');
  await expect.poll(() => nativeWallet(page)).toBe('hidden');
});

test('a dark Appearance reaches the native Wallet', async ({ page }) => {
  await asAndroidApp(page, { build: 9999, nativeHome: { on: true } });
  await settled(page);
  await page.goto('/app/#/settings?section=appearance');
  await page.getByRole('radio', { name: 'Dark', exact: true }).check();
  await page.goto('/app/#/home');
  await expect.poll(() => page.evaluate(() => window.nativeLook?.appearance)).toBe('dark');
});

test('the first-run checklist keeps the web Wallet until it’s put away', async ({ page }) => {
  await asAndroidApp(page, { build: 9999, nativeHome: { on: true } });
  await signUp(page);
  const setup = page.getByLabel('Get set up');
  await expect(setup).toBeVisible();
  await expect.poll(() => nativeWallet(page)).toBe('hidden');
  await setup.getByRole('button', { name: 'Hide' }).click();
  await expect.poll(() => nativeWallet(page)).toBe('shown');
});

test('the native Wallet opens pages, the add sheet and records in the web app', async ({ page }) => {
  await asAndroidApp(page, { build: 9999, nativeHome: { on: true } });
  await settled(page);
  await expect.poll(() => nativeWallet(page)).toBe('shown');

  // A card in the stack: its account.
  const [account] = (await (await page.request.get('/api/wallet')).json()).accounts;
  await nativeOpen(page, { page: 'account', params: { id: String(account.id) } });
  await expect(page).toHaveURL(new RegExp(`#/account\\?id=${account.id}$`));
  await expect.poll(() => nativeWallet(page)).toBe('hidden');

  // The plus: the sheet opens over Wallet, with the native Wallet out of its way.
  await page.goto('/app/#/home');
  await expect.poll(() => nativeWallet(page)).toBe('shown');
  await nativeOpen(page, { page: 'add' });
  const sheet = page.getByRole('dialog', { name: 'New record' });
  await expect(sheet).toBeVisible();
  await expect.poll(() => nativeWallet(page)).toBe('hidden');
  await page.keyboard.press('Escape');
  await expect(sheet).toBeHidden();
  await expect.poll(() => nativeWallet(page)).toBe('shown');

  // A record tapped in the list: the native Wallet hands over the server's JSON.
  const res = await page.request.post('/api/transactions', {
    data: { kind: 'expense', amount: 1200, account_id: account.id, to_account_id: null, category_id: null, occurred_at: new Date().toISOString(), payee: 'Corner bakery', note: '' },
  });
  const record = await res.json();
  await nativeOpen(page, { record });
  const edit = page.getByRole('dialog', { name: 'Edit record' });
  await expect(edit).toBeVisible();
  await expect.poll(() => nativeWallet(page)).toBe('hidden');
  await page.keyboard.press('Escape');
  await expect(edit).toBeHidden();
  await expect.poll(() => nativeWallet(page)).toBe('shown');
});

test('signing out takes the native Wallet away', async ({ page }) => {
  await asAndroidApp(page, { build: 9999, nativeHome: { on: true } });
  await settled(page);
  await expect.poll(() => nativeWallet(page)).toBe('shown');
  await page.goto('/app/#/settings');
  await page.getByRole('button', { name: 'Sign out' }).click();
  await expect(page.getByRole('button', { name: 'Sign in' })).toBeVisible();
  await expect.poll(() => nativeWallet(page)).toBe('hidden');
});

test('an app build without native screens says so in Settings', async ({ page }) => {
  await asAndroidApp(page, { build: 9999 });
  await signUp(page);
  await page.goto('/app/#/settings?section=native');
  await expect(page.getByText('This version of the app can’t do this yet.')).toBeVisible();
});
