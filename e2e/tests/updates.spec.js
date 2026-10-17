// The Android app offers a newer build when the server has one
// (web/src/updates.ts). The page acts as the app (asAndroidApp), and the
// server's answer is set here, since the stand-in APK has no version.
const { test, expect, signUp, asAndroidApp } = require('./fixtures');

/** Has /api/downloads offer an APK with this versionCode; returns how often it was asked. */
async function offer(page, version, version_name = '2.1') {
  const asked = { n: 0 };
  await page.unroute('**/api/downloads');
  await page.route('**/api/downloads', (route) => {
    asked.n++;
    return route.fulfill({ json: { android: { url: '/download/wallet.apk', size: 1, updated: new Date().toISOString(), version, version_name } } });
  });
  return asked;
}

const banner = (page) => page.getByRole('note').filter({ hasText: 'is available' });

test('a newer build is offered, and Later hides it until the next one', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await asAndroidApp(page, { build: 3 });
  await offer(page, 4);
  await signUp(page);
  await expect(banner(page)).toContainText('RudWallet 2.1 is available.');
  await page.screenshot({ path: test.info().outputPath('phone-update.png') });
  await page.setViewportSize({ width: 1280, height: 800 });
  await page.screenshot({ path: test.info().outputPath('desktop-update.png') });

  // Opens the download in the phone's browser (the server's address in a real build).
  await banner(page).getByRole('button', { name: 'Update' }).click();
  await expect.poll(() => page.evaluate(() => window.opened)).toEqual(['/download/wallet.apk']);

  await banner(page).getByRole('button', { name: 'Later' }).click();
  await expect(banner(page)).toHaveCount(0);
  let asked = await offer(page, 4);
  await page.reload();
  await expect.poll(() => asked.n).toBe(1);
  await expect(banner(page)).toHaveCount(0);

  // A build after the one put off is offered again.
  await offer(page, 5, '2.2');
  await page.reload();
  await expect(banner(page)).toContainText('RudWallet 2.2 is available.');
});

test('nothing is offered when the app is up to date', async ({ page }) => {
  await asAndroidApp(page, { build: 4 });
  const asked = await offer(page, 4);
  await signUp(page);
  await expect.poll(() => asked.n).toBeGreaterThan(0);
  await expect(banner(page)).toHaveCount(0);
});

test('a copy from the Play Store is left to the Play Store', async ({ page }) => {
  await asAndroidApp(page, { build: 3, installer: 'com.android.vending' });
  const asked = await offer(page, 4);
  await signUp(page);
  await expect.poll(() => page.evaluate(() => window.calls)).toContain('Updates.installer');
  await expect(banner(page)).toHaveCount(0);
  expect(asked.n).toBe(0);
});

test('the web app never offers an update', async ({ page }) => {
  const asked = await offer(page, 99);
  await signUp(page);
  await expect(page.getByText('Total balance')).toBeVisible();
  await expect(banner(page)).toHaveCount(0);
  expect(asked.n).toBe(0);
});
