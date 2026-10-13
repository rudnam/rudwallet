// The introduction page at /, and the app at /app/.
const { test, expect } = require('./fixtures');

test('the introduction page leads to the app', async ({ page }) => {
  await page.goto('/');
  await expect(page.getByRole('heading', { name: 'Your money, quietly kept in order.' })).toBeVisible();
  await expect(page.getByRole('heading', { name: 'What it does' })).toBeVisible();
  await page.getByRole('link', { name: 'Get started' }).click();
  await expect(page).toHaveURL(/\/app\/$/);
  await expect(page.getByRole('heading', { name: 'Sign in' })).toBeVisible();
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
