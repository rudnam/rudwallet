// The introduction page at /, and the app at /app/.
const { test, expect } = require('./fixtures');

test('the introduction page leads to the app', async ({ page }) => {
  await page.goto('/');
  await expect(page.getByRole('heading', { name: 'Your money, quietly kept in order.' })).toBeVisible();
  await expect(page.getByRole('list', { name: 'Where records come from' })).toContainText('Transit IC cards');
  await page.getByRole('link', { name: 'Get started' }).first().click();
  await expect(page).toHaveURL(/\/app\/$/);
  await expect(page.getByRole('heading', { name: 'Sign in' })).toBeVisible();
});

test('the path of a payment shows stop by stop, and everything shows once scrolled to', async ({ page }) => {
  for (const [name, size] of [['phone', { width: 390, height: 844 }], ['desktop', { width: 1280, height: 800 }]]) {
    await page.setViewportSize(size);
    await page.goto('/');
    await page.screenshot({ path: test.info().outputPath(`${name}-hero.png`) });
    const flow = page.getByRole('heading', { name: 'From a payment to a record.' });
    await flow.scrollIntoViewIfNeeded();
    const stops = page.locator('.flow-stop');
    await expect(stops).toHaveCount(3);
    await expect(stops.nth(0)).toContainText('支払いを受け付けました');
    await stops.nth(2).scrollIntoViewIfNeeded();
    await expect(stops.nth(2)).toHaveClass(/\bin\b/);
    await page.waitForTimeout(900); // let the stops settle for the picture
    await page.screenshot({ path: test.info().outputPath(`${name}-flow.png`) });
    // Each section fades in as it arrives.
    const close = page.getByRole('heading', { name: 'Start with one card.' });
    await close.scrollIntoViewIfNeeded();
    await expect(page.locator('.landing-close')).toHaveClass(/\bin\b/);
    await expect(close).toBeVisible();
  }
});

test('with reduced motion nothing waits to be revealed', async ({ page }) => {
  await page.emulateMedia({ reducedMotion: 'reduce' });
  await page.goto('/');
  await expect(page.locator('html')).not.toHaveClass(/motion/);
  await expect(page.locator('.landing-close')).toHaveCSS('opacity', '1');
});

test('the Android app can be downloaded from the introduction', async ({ page }) => {
  await page.goto('/');
  await page.getByRole('link', { name: 'Get the Android app' }).click();
  const link = page.getByRole('link', { name: 'Download for Android' });
  await expect(link).toBeInViewport();
  await expect(page.getByText(/^APK · [\d.]+ [KM]B · updated /)).toBeVisible();
  const [download] = await Promise.all([page.waitForEvent('download'), link.click()]);
  expect(download.suggestedFilename()).toBe('wallet.apk');
});

test('links from before the app moved to /app/ still open it', async ({ page }) => {
  // A page of the app, as bookmarked at /.
  await page.goto('/#/records');
  await expect(page).toHaveURL(/\/app\/#\/records$/);
  await expect(page.getByRole('heading', { name: 'Sign in' })).toBeVisible();
  // A notice an older server sent the browser back with.
  await page.goto('/?email=expired');
  await expect(page.getByText('That confirmation link has expired or was already used.')).toBeVisible();
  await expect(page).toHaveURL(/\/app\/$/);
  // /app without the slash.
  await page.goto('/app');
  await expect(page).toHaveURL(/\/app\/$/);
});

test('the installed app opens the app, and so does an install from before the move', async ({ page, request }) => {
  const manifest = await (await request.get('/manifest.webmanifest')).json();
  expect(manifest.start_url).toBe('/app/');
  expect(manifest.id).toBe('/'); // unchanged, so it's still the same installed app
  await page.addInitScript(() => {
    const real = window.matchMedia.bind(window);
    window.matchMedia = (q) => (q === '(display-mode: standalone)' ? { ...real(q), matches: true } : real(q));
  });
  await page.goto('/');
  await expect(page).toHaveURL(/\/app\/$/);
});
