// The demo: anyone can try the app with months of sample data, in English or
// Japanese, without an account. Also saves screenshots of it in both
// languages at phone and desktop width.
const { test, expect } = require('./fixtures');

test('the introduction page opens a demo full of sample data', async ({ page }) => {
  await page.goto('/');
  await page.getByRole('link', { name: 'Try the demo' }).first().click();
  await expect(page.getByRole('note')).toContainText('This is a demo with sample data.');
  await expect(page.getByText('Total balance')).toBeVisible();
  await expect(page.getByRole('navigation', { name: 'Main' }).getByLabel('2 to review')).toBeVisible();
  await expect(page.getByRole('list', { name: 'Accounts' })).toBeVisible();
  await expect(page).toHaveURL(/\/app\/#\/home$/); // ?demo is gone, so a reload doesn't start another
  await expect(page.getByRole('navigation', { name: 'Main' })).toContainText('Demo');

  // It's a real wallet: records can be confirmed and added.
  await page.goto('/app/#/review');
  await page.getByRole('listitem', { name: 'IKEAオンライン' }).getByRole('button', { name: 'Confirm' }).click();
  await expect(page.getByText('Confirmed IKEAオンライン')).toBeVisible();
  // Last month is complete whatever today's date, rent (the 27th) included.
  await page.goto('/app/#/records');
  await page.getByRole('button', { name: 'Previous month' }).click();
  await expect(page.getByRole('button', { name: /^Rent, −¥72,000$/ })).toBeVisible();

  // Nothing that reaches outside the app.
  await page.goto('/app/#/settings?section=claude');
  await expect(page.getByText('it isn’t available in the demo', { exact: false })).toBeVisible();
  await expect(page.getByLabel('New connection')).toHaveCount(0);

  // From the demo to an account of one's own.
  await page.goto('/app/#/settings');
  await expect(page.getByRole('button', { name: 'Delete account' })).toHaveCount(0);
  await page.getByRole('button', { name: 'Create your account' }).click();
  await expect(page.getByRole('heading', { name: 'Create an account' })).toBeVisible();
});

test('the sign-in screen offers the demo, and ending it signs out', async ({ page }) => {
  await page.goto('/app/');
  await page.getByRole('button', { name: 'Try the demo' }).click();
  await expect(page.getByText('Total balance')).toBeVisible();
  await page.goto('/app/#/settings');
  await page.getByRole('button', { name: 'End demo' }).click();
  await expect(page.getByRole('heading', { name: 'Sign in' })).toBeVisible();
  await page.reload();
  await expect(page.getByRole('heading', { name: 'Sign in' })).toBeVisible();
});

test.describe('in Japanese', () => {
  test.use({ locale: 'ja-JP' });

  test('the demo is in Japanese', async ({ page }) => {
    await page.goto('/app/');
    await page.getByRole('button', { name: 'デモを試す' }).click();
    await expect(page.getByRole('note')).toContainText('サンプルデータのデモです。');
    await expect(page.getByRole('navigation', { name: 'メイン' }).getByLabel('確認待ち2件')).toBeVisible();
    await expect(page.getByText('三菱UFJ銀行').first()).toBeVisible();
    await page.goto('/app/#/budgets');
    await expect(page.getByRole('list', { name: '予算' })).toContainText('外食');
  });
});

for (const [lang, locale, tryIt] of [
  ['en', 'en-US', 'Try the demo'],
  ['ja', 'ja-JP', 'デモを試す'],
]) {
  for (const [name, viewport] of [
    ['phone', { width: 390, height: 844 }],
    ['desktop', { width: 1280, height: 800 }],
  ]) {
    test.describe(`demo screens in ${lang} at ${name} width`, () => {
      test.use({ locale, viewport });

      test('screens', async ({ page }) => {
        const shot = async (label) => {
          const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
          expect(overflow, `${label} overflows sideways`).toBeLessThanOrEqual(0);
          await page.screenshot({ path: test.info().outputPath(`demo-${lang}-${name}-${label}.png`), fullPage: true });
        };
        await page.goto('/app/');
        await expect(page.getByRole('button', { name: tryIt })).toBeVisible();
        await shot('1-sign-in');
        await page.getByRole('button', { name: tryIt }).click();
        await expect(page.getByRole('note')).toBeVisible();
        await expect(page.locator('.card-stack')).toBeVisible();
        await shot('2-home');
        await page.goto('/app/#/stats');
        await expect(page.locator('.trends')).toBeVisible();
        await shot('3-stats');
      });
    });
  }
}
