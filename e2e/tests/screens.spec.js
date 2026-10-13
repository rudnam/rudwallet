// Screenshots of the main screens at phone and desktop width. They land in
// e2e/test-results/ (and in CI's artifacts), so you can look at a change
// without running anything. Add your own screens here.
const { test, expect, signUp, payPayEmail, deliverEmail, inboxAddress } = require('./fixtures');

/** Fills a fresh account with a believable month, through the API. */
async function seed(page) {
  const api = page.request;
  const overview = await (await api.get('/api/wallet')).json();
  const cat = (name) => overview.categories.find((c) => c.name === name).id;
  const cash = overview.accounts[0].id;
  const card = await (await api.post('/api/accounts', { data: { name: 'PayPay Card', kind: 'credit_card', color: '#d9534f' } })).json();
  const bank = await (await api.post('/api/accounts', { data: { name: 'MUFG', kind: 'bank', initial_balance: 182000, color: '#3b82c4' } })).json();
  const now = new Date();
  const day = (d, h = 12) => new Date(now.getFullYear(), now.getMonth(), Math.min(d, now.getDate()), h).toISOString();
  const records = [
    { kind: 'income', amount: 280000, account_id: bank.id, category_id: cat('Salary'), occurred_at: day(1, 9), payee: 'Employer' },
    { kind: 'expense', amount: 68000, account_id: bank.id, category_id: cat('Housing'), occurred_at: day(1, 10), payee: 'Rent' },
    { kind: 'expense', amount: 4373, account_id: card.id, category_id: cat('Groceries'), occurred_at: day(3, 17), payee: 'アピタ知立店' },
    { kind: 'expense', amount: 1820, account_id: card.id, category_id: cat('Food & Drinks'), occurred_at: day(6, 19), payee: '大戸屋 知立店', note: 'Tap to pay' },
    { kind: 'expense', amount: 429, account_id: card.id, category_id: cat('Food & Drinks'), occurred_at: day(7, 7), payee: 'セブン－イレブン' },
    { kind: 'expense', amount: 1430, account_id: cash, category_id: cat('Food & Drinks'), occurred_at: day(8, 20), payee: 'コメダ珈琲店' },
    { kind: 'expense', amount: 6800, account_id: card.id, category_id: cat('Bills & Utilities'), occurred_at: day(9, 9), payee: 'Chubu Electric' },
    { kind: 'expense', amount: 1025, account_id: card.id, category_id: cat('Health'), occurred_at: day(10, 20), payee: 'スギ薬局 大曽根店' },
    { kind: 'transfer', amount: 20000, account_id: bank.id, to_account_id: cash, occurred_at: day(2, 11) },
  ];
  for (const r of records) expect((await api.post('/api/transactions', { data: r })).status()).toBe(201);
  // Earlier months, for the trends on Stats.
  const earlier = [1, 2, 3].flatMap((ago) => {
    const at = (d, h) => new Date(now.getFullYear(), now.getMonth() - ago, Math.min(d, now.getDate()), h).toISOString();
    return [
      { kind: 'income', amount: 280000, account_id: bank.id, category_id: cat('Salary'), occurred_at: at(1, 9), payee: 'Employer' },
      { kind: 'expense', amount: 68000, account_id: bank.id, category_id: cat('Housing'), occurred_at: at(1, 10), payee: 'Rent' },
      { kind: 'expense', amount: 3000 + ago * 900, account_id: card.id, category_id: cat('Groceries'), occurred_at: at(3, 17), payee: 'アピタ知立店' },
      { kind: 'expense', amount: 1200 * ago, account_id: card.id, category_id: cat('Food & Drinks'), occurred_at: at(6, 19), payee: '大戸屋 知立店' },
      { kind: 'expense', amount: 6800, account_id: card.id, category_id: cat('Bills & Utilities'), occurred_at: at(9, 9), payee: 'Chubu Electric' },
    ];
  });
  for (const res of await Promise.all(earlier.map((r) => api.post('/api/transactions', { data: r })))) expect(res.status()).toBe(201);
  await api.post('/api/budgets', { data: { name: 'Eating out', category_id: cat('Food & Drinks'), amount: 5000 } });
  await api.post('/api/budgets', { data: { name: 'Everything', category_id: null, amount: 120000 } });
  // One payment from an email, waiting for review.
  await deliverEmail(api, await inboxAddress(page), payPayEmail({ merchant: 'IKEAオンライン', yen: 4994, when: new Date(), id: `screens-${Date.now()}-${Math.random()}` }));
}

for (const [name, viewport] of [
  ['phone', { width: 390, height: 844 }],
  ['desktop', { width: 1280, height: 800 }],
]) {
  test(`screens at ${name} width`, async ({ page }) => {
    test.slow(); // every main screen, twice over
    await page.setViewportSize(viewport);
    const shot = async (label) => {
      // Nothing may overflow sideways.
      const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
      expect(overflow, `${label} overflows sideways`).toBeLessThanOrEqual(0);
      await page.screenshot({ path: test.info().outputPath(`${name}-${label}.png`), fullPage: true });
    };

    await page.goto('/');
    await expect(page.getByRole('heading', { name: 'Sign in' })).toBeVisible();
    await expect(page.getByRole('link', { name: 'Continue with Google' })).toBeVisible();
    await shot('1-sign-in');

    await signUp(page);
    await expect(page.getByLabel('Get set up')).toBeVisible();
    await shot('2-welcome');
    await page.getByLabel('Get set up').getByRole('button', { name: 'Add account' }).click();
    await page.getByRole('dialog', { name: 'New account' }).getByRole('radio', { name: 'Credit card' }).check();
    await page.getByRole('dialog', { name: 'New account' }).getByRole('button', { name: 'PayPay Card' }).click();
    await shot('2-new-account');
    await page.keyboard.press('Escape');

    await seed(page);
    await page.reload();
    await expect(page.getByRole('link', { name: /1 payment from email/ })).toBeVisible();
    await expect(page.getByRole('list', { name: 'Spending by category' })).toBeVisible();
    await shot('2-overview');

    await page.goto('/#/stats');
    await expect(page.getByLabel('Trends')).toBeVisible();
    await shot('2-stats');
    await page.getByText('Year', { exact: true }).click();
    await expect(page.getByRole('img', { name: 'Spending per month' })).toBeVisible();
    await shot('2-stats-year');

    await page.goto('/#/review');
    await expect(page.getByRole('listitem', { name: 'IKEAオンライン' })).toBeVisible();
    await shot('3-review');

    await page.goto('/#/records');
    await expect(page.getByRole('button', { name: /Rent/ })).toBeVisible();
    await shot('4-records');
    await page.getByRole('button', { name: 'Select' }).click();
    await page.getByRole('checkbox', { name: /Rent/ }).check();
    await page.getByRole('checkbox', { name: /Chubu Electric/ }).check();
    await shot('4-records-select');
    await page.getByRole('button', { name: 'Done' }).click();

    await page.getByRole('button', { name: 'Add record' }).click();
    await expect(page.getByRole('dialog', { name: 'New record' })).toBeVisible();
    await shot('5-new-record');
    await page.keyboard.press('Escape');

    await page.goto('/#/budgets');
    await expect(page.getByRole('list', { name: 'Budgets' })).toBeVisible();
    await shot('6-budgets');

    await page.goto('/#/settings');
    await expect(page.getByRole('navigation', { name: 'Settings sections' })).toBeVisible();
    await shot('7-settings');

    await page.getByRole('link', { name: /Email automation/ }).click();
    await expect(page.getByTestId('inbox-address')).toBeVisible();
    await shot('8-settings-email');

    await page.goto('/#/settings?section=accounts');
    await page.getByRole('button', { name: 'Edit account PayPay Card' }).click();
    await expect(page.getByRole('dialog', { name: 'Edit account' })).toBeVisible();
    await shot('9-edit-account');
    await page.keyboard.press('Escape');

    await page.goto('/#/settings?section=import');
    const p = (n) => String(n).padStart(2, '0');
    const now = new Date();
    const csv = [
      '取引日,出金金額（円）,入金金額（円）,海外出金金額,通貨,変換レート（円）,利用国,取引内容,取引先,取引方法,支払い区分,利用者,取引番号',
      `${now.getFullYear()}/${p(now.getMonth() + 1)}/01 12:00,"1,200",-,-,-,-,-,支払い,ローソン,PayPay残高,-,本人,s-001`,
      `${now.getFullYear()}/${p(now.getMonth() + 1)}/01 12:05,"1,280",-,-,-,-,-,支払い,お店,PayPayカード,一回払い,本人,s-002`,
      `${now.getFullYear()}/${p(now.getMonth() + 1)}/01 09:00,-,"5,000",-,-,-,-,チャージ,MUFG,三菱UFJ銀行,-,本人,s-003`,
    ].join('\n');
    await page.getByLabel('PayPay CSV file').setInputFiles({ name: 'paypay.csv', mimeType: 'text/csv', buffer: Buffer.from(csv) });
    await expect(page.getByRole('button', { name: 'Import 3 payments' })).toBeVisible();
    await shot('10-import-paypay');

    await page.goto('/#/settings?section=widgets');
    await expect(page.getByRole('list', { name: 'Widgets' })).toBeVisible();
    await shot('10-settings-widgets');

    await page.goto('/#/settings');
    await page.getByRole('button', { name: 'Delete account' }).click();
    await expect(page.getByRole('dialog', { name: 'Delete account' })).toBeVisible();
    await shot('11-delete-account');
  });
}
