// The web app inside an Android app build with native navigation
// (web/src/native.ts, "embedded"): the app draws the tabs and the plus and
// runs navigation; this shows the pages that aren't native yet, as the app
// says. The app's side is tested natively (android/, ./gradlew testDebugUnitTest).
const { test, expect, signUp, asAndroidApp, nativeShow, nativeBack, nativeAsked } = require('./fixtures');

const app = { nativePages: ['home'], appearance: 'auto', language: '' };

test('the app draws the navigation: links ask it for pages, and it says which to show', async ({ page }) => {
  await asAndroidApp(page, { build: 9999, nativeNav: app });
  await signUp(page);
  // No tabs and no plus of its own.
  await expect(page.getByRole('navigation', { name: 'Main' })).toBeHidden();
  await expect(page.locator('.fab')).toBeHidden();

  // A link asks the app, and the page stays until the app answers.
  await page.getByRole('link', { name: 'All records' }).click();
  await expect.poll(() => nativeAsked(page)).toContainEqual(['go', { path: 'records', replace: false }]);
  await expect(page).toHaveURL(/#\/?$|#\/home$/);

  await nativeShow(page, 'records');
  await expect(page.getByRole('heading', { name: 'Activity' })).toBeVisible();
  await expect(page).toHaveURL(/#\/records$/);
});

test('#/add is the sheet alone, and closing it leaves the page', async ({ page }) => {
  await asAndroidApp(page, { build: 9999, nativeNav: app });
  await signUp(page);
  await nativeShow(page, 'add');
  const sheet = page.getByRole('dialog', { name: 'New record' });
  await expect(sheet).toBeVisible();
  // The app knows a sheet is open, so its back gesture closes the sheet first.
  await expect.poll(() => nativeAsked(page)).toContainEqual(['overlay', { open: true }]);
  await expect(page.getByText('Total balance')).toBeHidden();

  await nativeBack(page);
  await expect(sheet).toBeHidden();
  await expect.poll(() => nativeAsked(page)).toContainEqual(['back', {}]);
  await expect.poll(() => nativeAsked(page)).toContainEqual(['overlay', { open: false }]);
});

test('a record the app opens comes up in its form', async ({ page }) => {
  await asAndroidApp(page, { build: 9999, nativeNav: app });
  await signUp(page);
  const [account] = (await (await page.request.get('/api/wallet')).json()).accounts;
  const at = new Date();
  const record = await (
    await page.request.post('/api/transactions', {
      data: { kind: 'expense', amount: 1200, account_id: account.id, to_account_id: null, category_id: null, occurred_at: at.toISOString(), payee: 'Corner bakery', note: '' },
    })
  ).json();
  const from = new Date(at.getFullYear(), at.getMonth(), at.getDate()).toISOString();
  const to = new Date(at.getFullYear(), at.getMonth(), at.getDate() + 1).toISOString();
  await nativeShow(page, `record?id=${record.id}&from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}`);
  const form = page.getByRole('dialog', { name: 'Edit record' });
  await expect(form).toBeVisible();
  await expect(form.getByLabel('Payee')).toHaveValue('Corner bakery');
  await page.keyboard.press('Escape');
  await expect.poll(() => nativeAsked(page)).toContainEqual(['back', {}]);
});

test('Appearance and Language follow the app, and choices made here go to it', async ({ page }) => {
  await asAndroidApp(page, { build: 9999, nativeNav: { ...app, appearance: 'dark' } });
  await signUp(page);
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark');

  await nativeShow(page, 'settings?section=appearance');
  await page.getByRole('radio', { name: 'Light', exact: true }).check();
  await expect.poll(() => nativeAsked(page)).toContainEqual(['setAppearance', { appearance: 'light' }]);

  await nativeShow(page, 'settings?section=language');
  await page.getByRole('radio', { name: '日本語' }).check();
  await expect.poll(() => nativeAsked(page)).toContainEqual(['setLanguage', { language: 'ja' }]);
});

test('an app build without native navigation runs the web app as before', async ({ page }) => {
  await asAndroidApp(page, { build: 9999 });
  await signUp(page);
  await expect(page.getByRole('navigation', { name: 'Main' })).toBeVisible();
  await page.getByRole('link', { name: 'All records' }).click();
  await expect(page).toHaveURL(/#\/records$/);
});
