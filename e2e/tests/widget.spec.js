// The Android app's home screen widgets, from the web app's side. The app
// opens #/add for the New record widget; the rest is in internal/server.
const { test, expect, signUp } = require('./fixtures');

test('the widget’s New record button opens the form', async ({ page }) => {
  await signUp(page);
  await page.goto('/#/add');
  await expect(page.getByRole('dialog', { name: 'New record' })).toBeVisible();
  expect(new URL(page.url()).hash).toBe('#/home'); // going back doesn't reopen it
});

test('Settings explains the widgets', async ({ page }) => {
  await signUp(page);
  await page.goto('/#/settings');
  await page.getByRole('link', { name: /Home screen widgets/ }).click();
  const list = page.getByRole('list', { name: 'Widgets' });
  for (const name of ['This month', 'Budgets', 'New record']) await expect(list).toContainText(name);
  await expect(page.getByText('The Wallet app for Android')).toBeVisible();
});
