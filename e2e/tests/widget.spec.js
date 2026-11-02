// The Android app's home screen widgets, from the web app's side. The app
// opens #/add for the New record widget; the rest is in internal/server.
const { test, expect, signUp } = require('./fixtures');

test('the widget’s New record button opens the form', async ({ page }) => {
  await signUp(page);
  await page.goto('/app/#/add');
  await expect(page.getByRole('dialog', { name: 'New record' })).toBeVisible();
  expect(new URL(page.url()).hash).toBe('#/home'); // going back doesn't reopen it
});

test('Settings explains the widgets', async ({ page }) => {
  await signUp(page);
  await page.goto('/app/#/settings');
  await expect(page.getByRole('link', { name: /Language/ })).toBeVisible();
  // Only the Android app lists it; on the web it has nothing to set.
  await expect(page.getByRole('link', { name: /Home screen widgets/ })).toHaveCount(0);
  await page.goto('/app/#/settings?section=widgets');
  const list = page.getByRole('list', { name: 'Widgets' });
  for (const name of ['This month', 'Budgets', 'New record']) await expect(list).toContainText(name);
  await expect(page.getByText('The RudWallet app for Android')).toBeVisible();
});
