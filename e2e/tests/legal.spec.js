// The privacy policy (/privacy) and how to delete an account
// (/delete-account), which the Play Store listing links to.
const { test, expect } = require('./fixtures');

test('the introduction links to the privacy policy and to deleting an account', async ({ page }) => {
  await page.goto('/');
  await page.getByRole('link', { name: 'Privacy policy' }).click();
  await expect(page).toHaveURL(/\/privacy$/);
  await expect(page.getByRole('heading', { name: 'Privacy policy', level: 1 })).toBeVisible();
  await expect(page.getByRole('heading', { name: 'What it collects' })).toBeVisible();
  await page.getByRole('link', { name: 'Deleting your account' }).last().click();
  await expect(page).toHaveURL(/\/delete-account$/);
  await expect(page.getByRole('heading', { name: 'Deleting your RudWallet account' })).toBeVisible();
  await expect(page.getByRole('heading', { name: "What's deleted" })).toBeVisible();
  // The data safety form links to deleting data alone.
  await page.goto('/delete-account#data');
  await expect(page.getByRole('heading', { name: 'Deleting data but keeping the account' })).toBeInViewport();
});

test('both pages read in Japanese, at phone and desktop widths', async ({ page }) => {
  for (const [name, size] of [['phone', { width: 390, height: 844 }], ['desktop', { width: 1280, height: 800 }]]) {
    await page.setViewportSize(size);
    for (const path of ['privacy', 'delete-account']) {
      await page.goto('/' + path);
      const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
      expect(overflow, `${path} overflows sideways`).toBeLessThanOrEqual(0);
      await page.screenshot({ path: test.info().outputPath(`${name}-${path}.png`), fullPage: true });
    }
  }
  await page.getByRole('button', { name: '日本語' }).click();
  await expect(page.getByRole('heading', { name: 'RudWalletのアカウントを削除する' })).toBeVisible();
  await expect(page.locator('html')).toHaveAttribute('lang', 'ja');
  await page.screenshot({ path: test.info().outputPath('desktop-delete-account-ja.png'), fullPage: true });
  await page.goto('/privacy');
  await expect(page.getByRole('heading', { name: 'プライバシーポリシー', level: 1 })).toBeVisible();
  await page.getByRole('button', { name: 'English' }).click();
  await expect(page.getByRole('heading', { name: 'Privacy policy', level: 1 })).toBeVisible();
});
