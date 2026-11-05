// The web app inside an Android app build with native navigation
// (web/src/native.ts, "embedded"): the app draws the tabs and the plus and
// runs navigation; this shows the pages that aren't native yet, as the app
// says. The app's side is tested natively (android/, ./gradlew testDebugUnitTest).
const { test, expect, signUp, asAndroidApp, nativeShow, nativeBack, nativeAsked, nativeEmit } = require('./fixtures');

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

test('a dialog tells the app it’s open, and the app’s back gesture closes it', async ({ page }) => {
  await asAndroidApp(page, { build: 9999, nativeNav: app });
  await signUp(page);
  await nativeShow(page, 'settings?section=accounts');
  await page.getByRole('button', { name: 'Add account' }).click();
  const dialog = page.getByRole('dialog');
  await expect(dialog).toBeVisible();
  await expect.poll(() => nativeAsked(page)).toContainEqual(['overlay', { open: true }]);

  await nativeBack(page);
  await expect(dialog).toBeHidden();
  await expect.poll(() => nativeAsked(page)).toContainEqual(['overlay', { open: false }]);
});

test('adding a record asks the app, which has its own sheet', async ({ page }) => {
  await asAndroidApp(page, { build: 9999, nativeNav: app });
  await signUp(page);
  await page.evaluate(() => (location.hash = '#/add'));
  await expect.poll(() => nativeAsked(page)).toContainEqual(['go', { path: 'add', replace: false }]);
  await expect(page.getByRole('dialog', { name: 'New record' })).toBeHidden();
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

test('signing out and Appearance in the app’s own Settings: this follows', async ({ page }) => {
  await asAndroidApp(page, { build: 9999, nativeNav: app });
  await signUp(page);
  await nativeEmit(page, 'appearance', { appearance: 'dark' });
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark');
  // Told by the app, it isn't told back.
  expect(await nativeAsked(page)).not.toContainEqual(['setAppearance', { appearance: 'dark' }]);

  await nativeEmit(page, 'signedOut', { deleted: false });
  await expect(page.getByRole('heading', { name: 'Sign in' })).toBeVisible();
});

test('an account deleted in the app’s Settings says so on the sign-in page', async ({ page }) => {
  await asAndroidApp(page, { build: 9999, nativeNav: app });
  await signUp(page);
  await nativeEmit(page, 'signedOut', { deleted: true });
  await expect(page.getByText('Your account and everything in it were deleted.')).toBeVisible();
});
