const { test, expect, signUp } = require('./fixtures');

test('stats over other periods, and how spending compares with usual', async ({ page }) => {
  await signUp(page);
  const api = page.request;
  const overview = await (await api.get('/api/wallet')).json();
  const food = overview.categories.find((c) => c.name === 'Food & Drinks').id;
  const cash = overview.accounts[0].id;
  const now = new Date();
  // The first of each month, early, so it is "by this point" whatever today is.
  const first = (monthsAgo) => new Date(now.getFullYear(), now.getMonth() - monthsAgo, 1, 0, 5).toISOString();
  for (const [ago, amount] of [[3, 2000], [2, 2000], [1, 2000], [0, 5000]]) {
    const r = { kind: 'expense', amount, account_id: cash, category_id: food, occurred_at: first(ago), payee: `Lunch ${ago}` };
    expect((await api.post('/api/transactions', { data: r })).status()).toBe(201);
  }
  await api.post('/api/transactions', {
    data: { kind: 'income', amount: 10000, account_id: cash, occurred_at: first(0), payee: 'Pay' },
  });

  // From Wallet, Insights opens this month.
  await page.reload();
  await page.getByRole('navigation', { name: 'Main' }).getByRole('link', { name: 'Insights' }).click();
  const month = now.toLocaleDateString(undefined, { year: 'numeric', month: 'long' });
  await expect(page.locator('.month-picker strong').filter({ hasText: month })).toBeVisible();
  await expect(page.locator('.hero-figure')).toHaveText('¥5,000');

  const trends = page.getByLabel('Trends');
  await expect(trends).toContainText(/150% more than usual/);
  await expect(trends).toContainText('¥5,000 against ¥2,000');
  await expect(trends).toContainText('You spent 50% of what came in');
  await expect(trends.getByRole('list', { name: 'Higher than usual' })).toContainText('Food & Drinks');

  // The month before: about usual, compared with the months before it.
  // It was loaded ahead, so it shows at once even when the server is slow.
  await page.waitForLoadState('networkidle');
  await page.route('**/api/stats?*', async (route) => {
    await new Promise((r) => setTimeout(r, 5000));
    await route.continue().catch(() => {});
  });
  await page.getByRole('button', { name: 'Previous month' }).click();
  const last = new Date(now.getFullYear(), now.getMonth() - 1, 1).toLocaleDateString(undefined, { year: 'numeric', month: 'long' });
  await expect(page.locator('.month-picker strong').filter({ hasText: last })).toBeVisible();
  await expect(page.locator('.hero-figure')).toHaveText('¥2,000', { timeout: 1000 });
  await page.unroute('**/api/stats?*');
  await expect(trends).toContainText('About your usual spending');

  // A year, by month.
  await page.getByText('Year', { exact: true }).click();
  await expect(page.locator('.month-picker strong').filter({ hasText: String(new Date(now.getFullYear(), now.getMonth() - 1, 1).getFullYear()) })).toBeVisible();
  await expect(page.getByRole('img', { name: 'Spending per month' })).toBeVisible();
  // The seeded months reach back three months before the one shown.
  if (new Date(now.getFullYear(), now.getMonth() - 4, 1).getFullYear() === new Date(now.getFullYear(), now.getMonth() - 1, 1).getFullYear())
    await expect(page.getByText(/Not enough history yet\. Once you have records from the years before/)).toBeVisible();

  // A week, by day.
  await page.getByText('Week', { exact: true }).click();
  await expect(page.getByRole('img', { name: 'Spending per day' })).toBeVisible();

  // Any range.
  await page.getByText('Range', { exact: true }).click();
  const p = (n) => String(n).padStart(2, '0');
  const day = (d) => `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`;
  await page.getByLabel('From').fill(day(new Date(now.getFullYear(), now.getMonth() - 3, 1)));
  await page.getByLabel('To').fill(day(now));
  await expect(page.locator('.hero-figure')).toHaveText('¥11,000');
  await expect(page.getByRole('list', { name: 'Spending by category' })).toContainText('Food & Drinks');
});
