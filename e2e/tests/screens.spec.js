// Screenshots of the main screens at phone and desktop width. They land in
// e2e/test-results/ (and in CI's artifacts), so you can look at a change
// without running anything. Add your own screens here.
const { test, expect, signUp, payPayCard, sendNotifications } = require('./fixtures');

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
    { kind: 'expense', amount: 1820, account_id: card.id, category_id: cat('Food & Drinks'), occurred_at: day(6, 19), payee: '大戸屋 知立店', note: 'Tap to pay', tags: ['Date night'] },
    { kind: 'expense', amount: 429, account_id: card.id, category_id: cat('Food & Drinks'), occurred_at: day(7, 7), payee: 'セブン－イレブン' },
    { kind: 'expense', amount: 1430, account_id: cash, category_id: cat('Food & Drinks'), occurred_at: day(8, 20), payee: 'コメダ珈琲店', tags: ['Work', 'Date night'] },
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
  // Payments from PayPay's notifications: one confirmed earlier, which taught
  // its shop and card; two at shops with a rule, and one at a new shop,
  // waiting for review.
  const paid = async (merchant, yen, ago = 0) =>
    (await sendNotifications(api, payPayCard({ merchant, yen, when: new Date(Date.now() - ago) })))[0];
  const amazon = await paid('Amazon.co.jp', 2980, 86400_000);
  const r = (await (await api.get('/api/transactions?status=review')).json()).find((t) => t.id === amazon.transaction_id);
  const confirmed = await api.put(`/api/transactions/${r.id}`, {
    data: { kind: r.kind, amount: r.amount, account_id: card.id, to_account_id: null, category_id: cat('Shopping'), occurred_at: r.occurred_at, payee: r.payee, note: r.note, status: 'confirmed' },
  });
  expect(confirmed.status(), await confirmed.text()).toBe(200);
  for (const [payee, category] of [['セブン－イレブン', 'Food & Drinks'], ['スギ薬局', 'Health']]) {
    expect((await api.put('/api/rules/payees', { data: { payee, category_id: cat(category) } })).status()).toBe(200);
  }
  await paid('セブン－イレブン', 612, 7200_000);
  await paid('スギ薬局 大曽根店', 1280, 5400_000);
  await paid('IKEAオンライン', 4994);
  // A Google Wallet notification that isn't a payment.
  await sendNotifications(api, { package: 'com.google.android.apps.walletnfcrel', when: Date.now() - 3600_000, title: 'Google Wallet', text: 'Visa •••• 1234 is ready to use' });
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
      // Animations off, so a dialog isn't caught halfway through opening.
      await page.screenshot({ path: test.info().outputPath(`${name}-${label}.png`), fullPage: true, animations: 'disabled' });
    };

    await page.goto('/');
    await expect(page.getByRole('list', { name: 'Features' })).toBeVisible();
    // Sections fade in as they're scrolled to: scroll through, so the
    // picture of the whole page has them all.
    for (let y = 0; y < (await page.evaluate(() => document.body.scrollHeight)); y += 400) {
      await page.evaluate((y) => window.scrollTo(0, y), y);
      await page.waitForTimeout(60);
    }
    await page.waitForTimeout(1500);
    await page.evaluate(() => window.scrollTo(0, 0));
    await shot('0-landing');

    // Starting up, while the server takes its time.
    let answer = () => {};
    const held = new Promise((r) => (answer = r));
    await page.route('**/api/me', async (route) => {
      await held;
      await route.continue();
    });
    await page.goto('/app/');
    await expect(page.getByRole('status')).toContainText('Loading…');
    await shot('1-loading');
    answer();

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
    await expect(page.getByRole('link', { name: /3 payments to review/ })).toBeVisible();
    await expect(page.getByRole('list', { name: 'Spending by category' })).toBeVisible();
    await shot('2-overview');

    await page.goto('/app/#/stats');
    await expect(page.getByLabel('Trends')).toBeVisible();
    await shot('2-stats');
    await page.getByText('Year', { exact: true }).click();
    await expect(page.getByRole('img', { name: 'Spending per month' })).toBeVisible();
    await shot('2-stats-year');

    await page.goto('/app/#/review');
    await expect(page.getByRole('listitem', { name: 'IKEAオンライン' })).toBeVisible();
    await expect(page.getByRole('button', { name: 'Confirm 2 with a category' })).toBeVisible();
    // A category other than the shop's rule asks whether to keep it. (The
    // notification's full-width dash is read as a plain one.)
    await page.getByLabel('Category for セブン-イレブン').selectOption({ label: 'Groceries' });
    await expect(page.getByLabel(/Use Groceries for セブン-イレブン from now on/)).toBeVisible();
    await shot('3-review');

    await page.goto('/app/#/records');
    await expect(page.getByRole('button', { name: /Rent/ })).toBeVisible();
    await shot('4-records');
    await page.getByRole('button', { name: 'Select' }).click();
    await page.getByRole('checkbox', { name: /Rent/ }).check();
    await page.getByRole('checkbox', { name: /Chubu Electric/ }).check();
    await shot('4-records-select');
    await page.getByRole('button', { name: 'Done' }).click();
    // Editing a record shows what changes before saving, then offers an undo.
    await page.getByRole('button', { name: /コメダ珈琲店/ }).click();
    const edit = page.getByRole('dialog', { name: 'Edit record' });
    await edit.getByLabel('Amount (JPY)').fill('1680');
    await edit.getByLabel('Note').fill('With Aya');
    await edit.getByRole('button', { name: 'Save' }).click();
    await expect(page.getByRole('list', { name: 'Changes' })).toBeVisible();
    await shot('4-records-edit-confirm');
    await page.getByRole('button', { name: 'Save changes' }).click();
    await expect(page.getByRole('button', { name: 'Undo' })).toBeVisible();
    await shot('4-records-edit-undo');
    await page.getByRole('button', { name: 'Undo' }).click();
    await expect(page.getByRole('button', { name: /コメダ珈琲店, -¥1,430/ })).toBeVisible();

    await page.getByRole('list', { name: 'Filter by tag' }).getByRole('button', { name: /Date night/ }).click();
    await expect(page.getByText('#Date night in every month')).toBeVisible();
    await shot('4-records-tag');

    await page.getByRole('button', { name: 'Add record' }).click();
    const add = page.getByRole('dialog', { name: 'New record' });
    await expect(add).toBeVisible();
    await shot('5-new-record');
    await add.getByRole('button', { name: 'More' }).click();
    await add.getByRole('radio', { name: 'Travel' }).check();
    await shot('5-new-record-all-categories');
    await add.getByRole('button', { name: 'Fewer' }).click();
    // The chosen category stays in view when the rest fold away.
    await expect(add.getByRole('radio', { name: 'Travel' })).toBeChecked();
    await add.getByRole('combobox', { name: 'Tags' }).fill('Date night,');
    await add.getByRole('combobox', { name: 'Tags' }).fill('Birthday');
    await add.getByRole('combobox', { name: 'Tags' }).press('Enter');
    await add.getByLabel('Note').scrollIntoViewIfNeeded();
    await shot('5-new-record-tags');
    await page.keyboard.press('Escape');

    await page.goto('/app/#/budgets');
    await expect(page.getByRole('list', { name: 'Budgets' })).toBeVisible();
    await shot('6-budgets');

    await page.goto('/app/#/settings');
    await expect(page.getByRole('navigation', { name: 'Settings sections' })).toBeVisible();
    await shot('7-settings');

    await page.goto('/app/#/settings?section=rules');
    await expect(page.getByRole('list', { name: 'Shops' }).getByRole('listitem', { name: 'Amazon.co.jp' })).toBeVisible();
    await shot('8-settings-rules');

    await page.goto('/app/#/settings?section=accounts');
    await page.getByRole('button', { name: 'Edit account PayPay Card' }).click();
    await expect(page.getByRole('dialog', { name: 'Edit account' })).toBeVisible();
    await shot('9-edit-account');
    await page.keyboard.press('Escape');

    await page.goto('/app/#/settings?section=import');
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
    await page.getByRole('button', { name: 'Cancel' }).click();

    const bbDay = `${now.getFullYear()}-${p(now.getMonth() + 1)}-02 08:00:00`;
    const bb = [
      'account;category;currency;amount;ref_currency_amount;type;payment_type;note;date;transfer;payee;labels',
      ...[
        ['Restaurant, fast-food', 1200], ['Bar, cafe', 480], ['Groceries', 2480], ['Phone, cell phone', 5200], ['Public transport', 980],
        ['Coffee beans', 1500], ['Fuel', 4200], ['Charges, Fees', 220],
      ].map(([cat, n]) => `Wallet;${cat};JPY;${n};${n};Expense;Cash;;${bbDay};false;;`),
      `Bank;Wage, invoices;JPY;300000;300000;Income;Transfer;;${bbDay};false;;`,
    ].join('\n');
    await page.getByLabel('CSV file', { exact: true }).setInputFiles({ name: 'records.csv', mimeType: 'text/csv', buffer: Buffer.from(bb) });
    await expect(page.getByRole('button', { name: 'Import 9 records' })).toBeVisible();
    await page.getByLabel('Choose Bar, cafe').check();
    await page.getByLabel('Choose Coffee beans').check();
    await page.getByLabel('Category for Bar, cafe').scrollIntoViewIfNeeded();
    await shot('10-import-budgetbakers');

    await page.goto('/app/#/settings?section=widgets');
    await expect(page.getByRole('list', { name: 'Widgets' })).toBeVisible();
    await shot('10-settings-widgets');

    await page.goto('/app/#/settings?section=notifications');
    await expect(page.getByRole('list', { name: 'Recent notifications' })).toBeVisible();
    await shot('10-settings-notifications');
    // Claude: connect an app.
    await page.goto('/app/#/settings?section=claude');
    await expect(page.getByTestId('mcp-url')).toBeVisible();
    await shot('12-settings-claude');
    await page.getByLabel('New connection').fill('Claude Desktop');
    await page.getByRole('button', { name: 'Make a token' }).click();
    await expect(page.getByTestId('mcp-token')).toBeVisible();
    await page.getByText('How to connect').click();
    await shot('13-settings-claude-connected');
    // A Claude connector asks to connect.
    const reg = await (await page.request.post('/oauth/register', { data: { client_name: 'Claude', redirect_uris: ['https://claude.ai/api/mcp/auth_callback'] } })).json();
    const authorize = new URLSearchParams({ response_type: 'code', client_id: reg.client_id, redirect_uri: 'https://claude.ai/api/mcp/auth_callback', code_challenge: 'E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM', code_challenge_method: 'S256' });
    await page.goto(`/oauth/authorize?${authorize}`);
    await expect(page.getByRole('heading', { name: 'Connect Claude' })).toBeVisible();
    await shot('13-connect-claude');

    await page.goto('/app/#/settings');
    await page.getByRole('button', { name: 'Delete account' }).click();
    await expect(page.getByRole('dialog', { name: 'Delete account' })).toBeVisible();
    await shot('11-delete-account');
  });
}
