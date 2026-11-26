const { test, expect, signUp } = require('./fixtures');

test('recurring payments: suggested from the records, added, and counted in the month ahead', async ({ page }) => {
  await signUp(page);
  const api = page.request;
  const overview = await (await api.get('/api/wallet')).json();
  const cat = (name) => overview.categories.find((c) => c.name === name).id;
  const cash = overview.accounts[0].id;
  const now = new Date();
  // The same day of each of the last four months (the 28th at most, so every month has it).
  const monthsAgo = (n) => new Date(now.getFullYear(), now.getMonth() - n, Math.min(now.getDate(), 28), 9).toISOString();
  for (let n = 1; n <= 4; n++) {
    for (const [payee, amount] of [
      ['Netflix - ネットフリックス', 1590],
      ['Spotify', 980],
    ]) {
      const r = { kind: 'expense', amount, account_id: cash, category_id: cat('Entertainment'), occurred_at: monthsAgo(n), payee };
      expect((await api.post('/api/transactions', { data: r })).status()).toBe(201);
    }
  }

  // Both are found in the records.
  await page.goto('/app/#/recurring');
  const found = page.getByRole('list', { name: 'Suggestions' });
  await expect(found.getByRole('listitem')).toHaveCount(2);
  await expect(found).toContainText('Netflix');
  await expect(found).toContainText('Subscription · Every month · 4 payments');
  await expect(found).toContainText('¥1,590');

  // One isn't recurring after all; the other is added with a tap.
  await found.getByRole('listitem').filter({ hasText: 'Spotify' }).getByRole('button', { name: 'Not recurring' }).click();
  await expect(found.getByRole('listitem')).toHaveCount(1);
  await page.getByRole('button', { name: 'Add Netflix' }).click();
  await expect(page.getByRole('list', { name: 'Suggestions' })).toBeHidden();
  const subscriptions = page.getByRole('list', { name: 'Subscriptions' });
  await expect(subscriptions).toContainText('Netflix');
  await expect(subscriptions).toContainText(/Paid .* · Next /);
  // Due a month after the last payment: this month, still to come.
  await expect(page.getByLabel('This month')).toContainText('Still to come¥1,590');

  // Its records are listed when it's opened; stopped, it's no longer expected.
  await page.getByRole('button', { name: 'Edit Netflix' }).click();
  const edit = page.getByRole('dialog', { name: 'Edit recurring payment' });
  await expect(edit.getByRole('list', { name: 'Its records' }).getByRole('listitem')).toHaveCount(4);
  await edit.getByText('Stopped (cancelled, or paid off)').click();
  await edit.getByRole('button', { name: 'Save' }).click();
  await expect(edit).toBeHidden();
  await expect(page.getByRole('list', { name: 'Stopped' })).toContainText('Netflix');

  // A bill added by hand, due today.
  await page.getByRole('button', { name: 'Add', exact: true }).click();
  const add = page.getByRole('dialog', { name: 'New recurring payment' });
  await add.getByText('Bill', { exact: true }).click();
  await add.getByLabel('Name', { exact: true }).fill('Water');
  await add.getByLabel('About how much (JPY)').fill('2000');
  await add.getByText('Every 2 months', { exact: true }).click();
  await add.getByRole('button', { name: 'Add' }).click();
  await expect(add).toBeHidden();
  await expect(page.getByRole('list', { name: 'Bills and utilities' })).toContainText('Water');
  await expect(page.getByLabel('This month')).toContainText('Still to come¥2,000');

  // Insights counts it in where the month is heading. (On a month's last
  // day the month isn't still going, so there's nothing ahead to show.)
  const last = new Date(now.getFullYear(), now.getMonth() + 1, 0).getDate();
  if (now.getDate() < last) {
    await page.goto('/app/#/stats');
    const ahead = page.locator('.month-ahead');
    await expect(ahead).toContainText('Heading for about ¥2,000');
    await expect(ahead).toContainText('With ¥2,000 of bills and subscriptions still to come.');
    await ahead.getByRole('link', { name: 'See them' }).click();
    await expect(page.getByRole('heading', { name: 'Recurring payments' })).toBeVisible();
  }
});
