// The introduction page at /, and the app at /app/.
const { test, expect } = require('./fixtures');

test('the introduction page leads to the app', async ({ page }) => {
  await page.goto('/');
  await expect(page.getByRole('heading', { name: 'Your money, quietly kept in order.' })).toBeVisible();
  await expect(page.getByRole('list', { name: 'Features' })).toBeVisible();
  await page.getByRole('link', { name: 'Get started' }).first().click();
  await expect(page).toHaveURL(/\/app\/$/);
  await expect(page.getByRole('heading', { name: 'Sign in' })).toBeVisible();
});

test('how it works follows the scroll, and everything shows once scrolled to', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto('/');
  const stage = page.locator('.how-stage');
  await expect(stage).toHaveAttribute('data-step', '1');
  for (const step of ['1', '2', '3']) {
    // Bring the step to two thirds of the way down the screen, below the stage.
    await page.locator(`.how-step[data-step="${step}"]`).evaluate((el) =>
      window.scrollBy(0, el.getBoundingClientRect().top - innerHeight * 0.66),
    );
    await expect(stage).toHaveAttribute('data-step', step);
    await expect(page.locator(`.how-step[data-step="${step}"]`)).toHaveClass(/\bon\b/);
    await page.waitForTimeout(1200); // let the stage settle for the picture
    await page.screenshot({ path: test.info().outputPath(`phone-how-${step}.png`) });
  }
  await expect(page.locator('.how-learned')).toBeVisible();
  // Each section fades in as it arrives.
  const close = page.getByRole('heading', { name: 'Start with one card.' });
  await close.scrollIntoViewIfNeeded();
  await expect(page.locator('.landing-close')).toHaveClass(/\bin\b/);
  await expect(close).toBeVisible();
});

test('how it works at desktop width', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 800 });
  await page.goto('/');
  for (const step of ['1', '2', '3']) {
    await page.locator(`.how-step[data-step="${step}"]`).evaluate((el) =>
      window.scrollBy(0, el.getBoundingClientRect().top + el.offsetHeight / 2 - innerHeight / 2),
    );
    await expect(page.locator('.how-stage')).toHaveAttribute('data-step', step);
    await page.waitForTimeout(1200);
    await page.screenshot({ path: test.info().outputPath(`desktop-how-${step}.png`) });
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
