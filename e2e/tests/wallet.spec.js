const { test, expect, signUp } = require('./fixtures');

test('add, edit and delete a record', async ({ page }) => {
  await signUp(page);
  await expect(page.getByText('Total balance')).toBeVisible();
  await expect(page.getByText('No spending recorded this month yet.')).toBeVisible();

  await page.getByRole('button', { name: 'Add record' }).click();
  const dialog = page.getByRole('dialog', { name: 'New record' });
  await dialog.getByLabel('Amount (JPY)').fill('1,200');
  await dialog.getByRole('radio', { name: 'Food & Drinks' }).check();
  await dialog.getByLabel('Payee').fill('Ramen Taro');
  await dialog.getByRole('button', { name: 'Save' }).click();
  await expect(dialog).toBeHidden();

  // The overview updates: balance, spending by category, latest records.
  await expect(page.locator('.hero-figure')).toHaveText(/-¥1,200/);
  await expect(page.getByRole('list', { name: 'Spending by category' })).toContainText('Food & Drinks');
  const latest = page.getByRole('list', { name: 'Latest records' });
  await expect(latest).toContainText('Ramen Taro');

  // Edit it from the Records page.
  await page.getByRole('navigation', { name: 'Main' }).getByRole('link', { name: 'Records' }).click();
  await expect(page.getByRole('heading', { name: 'Records' })).toBeVisible();
  await page.getByRole('button', { name: /Ramen Taro/ }).click();
  const edit = page.getByRole('dialog', { name: 'Edit record' });
  await edit.getByLabel('Amount (JPY)').fill('980');
  await edit.getByLabel('Note').fill('Extra egg');
  await edit.getByRole('button', { name: 'Save' }).click();

  // It asks first, listing only what changes.
  const changes = page.getByRole('list', { name: 'Changes' });
  await expect(changes.getByRole('listitem')).toHaveCount(2);
  await expect(changes).toContainText('Amount¥1,200¥980');
  await expect(changes).toContainText('NoteNoneExtra egg');
  // Back returns to the form with the edits kept.
  await page.getByRole('button', { name: 'Back' }).click();
  await expect(edit.getByLabel('Amount (JPY)')).toHaveValue('980');
  await edit.getByRole('button', { name: 'Save' }).click();
  await page.getByRole('button', { name: 'Save changes' }).click();
  await expect(page.getByRole('button', { name: /Ramen Taro, -¥980/ })).toBeVisible();

  // Undo puts it back as it was.
  await page.getByRole('button', { name: 'Undo' }).click();
  await expect(page.getByText('Change undone')).toBeVisible();
  await expect(page.getByRole('button', { name: /Ramen Taro, -¥1,200/ })).toBeVisible();
  await page.getByRole('button', { name: /Ramen Taro/ }).click();
  await expect(edit.getByLabel('Note')).toHaveValue('');
  // Saving with nothing changed just closes.
  await edit.getByRole('button', { name: 'Save' }).click();
  await expect(edit).toBeHidden();
  await expect(page.getByRole('list', { name: 'Changes' })).toBeHidden();

  // Search, then delete.
  await page.getByLabel('Search records').fill('nothing like this');
  await page.getByLabel('Search records').press('Enter');
  await expect(page.getByText('No records match.')).toBeVisible();
  await page.getByLabel('Search records').fill('ramen');
  await page.getByLabel('Search records').press('Enter');
  page.on('dialog', (d) => d.accept());
  await page.getByRole('button', { name: /Ramen Taro/ }).click();
  await page.getByRole('dialog', { name: 'Edit record' }).getByRole('button', { name: 'Delete' }).click();
  await expect(page.getByText('No records match.')).toBeVisible();
  // Undo brings it back, note and all.
  await page.getByRole('button', { name: 'Undo' }).click();
  await expect(page.getByText('Record restored')).toBeVisible();
  await expect(page.getByRole('button', { name: /Ramen Taro, -¥1,200/ })).toBeVisible();
  await page.getByRole('button', { name: /Ramen Taro/ }).click();
  await expect(page.getByRole('dialog', { name: 'Edit record' }).getByLabel('Payee')).toHaveValue('Ramen Taro');
  await page.getByRole('dialog', { name: 'Edit record' }).getByRole('button', { name: 'Delete' }).click();
  await expect(page.getByText('No records match.')).toBeVisible();
});

test('a new account, a transfer, and a budget that runs over', async ({ page }) => {
  await signUp(page);
  await page.goto('/app/#/settings');
  await page.getByRole('link', { name: /Accounts/ }).click();
  await page.getByRole('button', { name: 'Add account' }).click();
  const acct = page.getByRole('dialog', { name: 'New account' });
  await acct.getByRole('radio', { name: 'Bank account' }).check();
  await acct.getByRole('button', { name: 'MUFG' }).click();
  await acct.getByLabel('Balance today (JPY)').fill('50000');
  await acct.getByRole('button', { name: 'Add account' }).click();
  await expect(page.getByRole('list', { name: 'Accounts' })).toContainText('MUFG');

  // Withdraw cash: a transfer changes both balances but not the total.
  await page.getByRole('button', { name: 'Add record' }).click();
  const rec = page.getByRole('dialog', { name: 'New record' });
  await rec.getByText('Transfer', { exact: true }).click();
  await rec.getByLabel('Amount (JPY)').fill('10000');
  await rec.getByLabel('From').selectOption({ label: 'MUFG' });
  await rec.getByLabel('To').selectOption({ label: 'Cash' });
  await rec.getByRole('button', { name: 'Save' }).click();
  await page.goto('/app/#/');
  const accounts = page.getByRole('list', { name: 'Accounts' });
  await expect(accounts.getByRole('listitem').filter({ hasText: 'MUFG' })).toContainText('¥40,000');
  await expect(accounts.getByRole('listitem').filter({ hasText: 'Cash' })).toContainText('¥10,000');
  await expect(page.locator('.hero-figure')).toHaveText('¥50,000');

  // A food budget of ¥1,000, then a ¥1,200 lunch.
  await page.getByRole('navigation', { name: 'Main' }).getByRole('link', { name: 'Budgets' }).click();
  await page.getByRole('button', { name: 'New budget' }).click();
  const b = page.getByRole('dialog', { name: 'New budget' });
  await b.getByLabel('Category').selectOption({ label: 'Food & Drinks' });
  await b.getByLabel('Monthly limit (JPY)').fill('1000');
  await b.getByRole('button', { name: 'Save' }).click();
  await expect(page.getByRole('list', { name: 'Budgets' })).toContainText('¥1,000 left');

  await page.getByRole('button', { name: 'Add record' }).click();
  const lunch = page.getByRole('dialog', { name: 'New record' });
  await lunch.getByLabel('Amount (JPY)').fill('1200');
  await lunch.getByRole('radio', { name: 'Food & Drinks' }).check();
  await lunch.getByRole('button', { name: 'Save' }).click();
  await expect(page.getByRole('list', { name: 'Budgets' })).toContainText('Over by ¥200');
});

test('Stats says where the month is heading, and what budgets leave per day', async ({ page }) => {
  // The 10th of this month: ¥54,000 spent so far is ¥5,400 a day.
  const now = new Date();
  const y = now.getFullYear();
  const m = now.getMonth();
  await page.clock.setFixedTime(new Date(y, m, 10, 12));
  await signUp(page);
  const api = page.request;
  const overview = await (await api.get('/api/wallet')).json();
  const cat = (name) => overview.categories.find((c) => c.name === name).id;
  const records = [
    [m, 1, 50000, 'Housing'],
    [m, 2, 1500, 'Food & Drinks'],
    [m, 6, 2500, 'Food & Drinks'],
  ];
  for (const [month, d, amount, category] of records) {
    const r = { kind: 'expense', amount, account_id: overview.accounts[0].id, category_id: cat(category), occurred_at: new Date(y, month, d, 12).toISOString() };
    expect((await api.post('/api/transactions', { data: r })).status()).toBe(201);
  }
  expect((await api.post('/api/budgets', { data: { name: 'Eating out', category_id: cat('Food & Drinks'), amount: 10000 } })).status()).toBe(201);
  expect((await api.post('/api/budgets', { data: { name: 'Everything', category_id: null, amount: 50000 } })).status()).toBe(201);

  const yen = (n) => '¥' + n.toLocaleString('en-US');
  const len = new Date(y, m + 1, 0).getDate();
  const left = len - 9;
  await page.goto('/app/#/stats');
  const card = page.locator('.card[aria-label^="Rest of "]');
  await expect(card).toContainText(`Heading for about ${yen(5400 * len)}`);
  await expect(card).toContainText(`To stay within budget, ${left} days left`);
  const perDay = card.getByRole('list', { name: 'Per day to stay within budget' });
  const eating = perDay.getByRole('listitem').filter({ hasText: 'Eating out' });
  // The most a day that keeps within ¥10,000, against ¥400 a day so far.
  await expect(eating).toContainText(`Up to ${yen(Math.floor(6000 / left))} a day`);
  await expect(eating).toContainText('Now ¥400 a day');
  await expect(perDay.getByRole('listitem').filter({ hasText: 'Everything' })).toContainText('Over by ¥4,000');

  // The overview stays as it was.
  await page.goto('/app/#/');
  await expect(page.locator('.hero')).not.toContainText('pace');
});

test('the currency can change until there are records', async ({ page }) => {
  await signUp(page);
  await page.goto('/app/#/settings?section=general');
  await page.getByLabel('Main currency').selectOption('USD');
  await page.goto('/app/#/');
  await expect(page.locator('.hero-figure')).toHaveText('$0.00');
  await page.getByRole('button', { name: 'Add record' }).click();
  await page.getByRole('dialog', { name: 'New record' }).getByLabel('Amount (USD)').fill('12.50');
  await page.getByRole('dialog', { name: 'New record' }).getByRole('button', { name: 'Save' }).click();
  await expect(page.locator('.hero-figure')).toHaveText('-$12.50');
  await page.goto('/app/#/settings?section=general');
  await page.getByLabel('Main currency').selectOption('EUR');
  await expect(page.getByRole('alert')).toHaveText("the currency can't change once you have records");
});

test('import records exported from Wallet by BudgetBakers', async ({ page }) => {
  await signUp(page);
  await page.goto('/app/#/settings?section=import');
  const now = new Date();
  const p = (n) => String(n).padStart(2, '0');
  const today = `${now.getFullYear()}-${p(now.getMonth() + 1)}-${p(now.getDate())} 08:00:00`;
  const csv = [
    'account;category;currency;amount;ref_currency_amount;type;payment_type;payment_type_local;note;date;gps_latitude;gps_longitude;gps_accuracy_in_meters;warranty_in_month;transfer;payee;labels;envelope_id;custom_category',
    `Cash;Groceries;JPY;-2480.00;-2480.00;Expenses;CASH;Cash;;${today};;;;0;false;Aeon;;1000;false`,
    `Rakuten Bank;Salary;JPY;250000.00;250000.00;Income;TRANSFER;Transfer;;${today};;;;0;false;;;5000;false`,
    `Cash;Bar, cafe;JPY;-480.00;-480.00;Expenses;CASH;Cash;;${today};;;;0;false;Doutor;;1000;false`,
    `Cash;Coffee beans;JPY;-1500.00;-1500.00;Expenses;CASH;Cash;;${today};;;;0;false;Roastery;;1000;false`,
  ].join('\n');
  const file = { name: 'records.csv', mimeType: 'text/csv', buffer: Buffer.from(csv) };
  await page.getByLabel('CSV file', { exact: true }).setInputFiles(file);
  await expect(page.getByText(/^4 records/)).toBeVisible();
  await expect(page.getByLabel(/^Rakuten Bank/).locator('option:checked')).toHaveText('New account: Rakuten Bank');
  // BudgetBakers' categories are matched to the closest of the user's.
  await expect(page.getByLabel('Category for Bar, cafe').locator('option:checked')).toHaveText('Food & Drinks');
  await expect(page.getByLabel('Category for Salary').locator('option:checked')).toHaveText('Salary');
  await expect(page.getByLabel('Category for Coffee beans').locator('option:checked')).toHaveText('Coffee beans');

  // Several at once.
  await page.getByLabel('Choose Bar, cafe').check();
  await page.getByLabel('Choose Coffee beans').check();
  await expect(page.getByRole('region', { name: 'Chosen categories' })).toContainText('2 chosen');
  await page.getByLabel('Set category for chosen').selectOption({ label: 'Groceries' });
  await expect(page.getByLabel('Category for Coffee beans').locator('option:checked')).toHaveText('Groceries');
  await expect(page.getByLabel('Category for Bar, cafe').locator('option:checked')).toHaveText('Groceries');
  await expect(page.getByLabel('Choose Bar, cafe')).not.toBeChecked();

  await page.getByRole('button', { name: 'Import 4 records' }).click();
  await expect(page.getByRole('status')).toContainText('Imported 4 records, created 1 account.');

  // The same file again adds nothing.
  await page.getByLabel('CSV file', { exact: true }).setInputFiles(file);
  await page.getByRole('button', { name: 'Import 4 records' }).click();
  await expect(page.getByRole('status')).toContainText('Imported 0 records, skipped 4 already imported');
  await page.getByRole('link', { name: 'Settings', exact: true }).first().click();
  await page.getByRole('link', { name: /Accounts/ }).click();
  await expect(page.getByRole('list', { name: 'Accounts' })).toContainText('Rakuten Bank');

  await page.goto('/app/#/records');
  await expect(page.getByRole('button', { name: /Aeon, -¥2,480/ })).toBeVisible();
  const [roastery] = await (await page.request.get('/api/transactions?q=Roastery')).json();
  const { categories } = await (await page.request.get('/api/wallet')).json();
  expect(categories.find((c) => c.id === roastery.category_id)?.name).toBe('Groceries');
});

test('add a default category the user is missing', async ({ page }) => {
  await signUp(page);
  await page.goto('/app/#/settings?section=categories');
  // Deleting one says what goes with it.
  await page.getByRole('button', { name: 'Edit category Car' }).click();
  const confirm = page.waitForEvent('dialog');
  const deleting = page.getByRole('dialog', { name: 'Edit category' }).getByRole('button', { name: 'Delete' }).click();
  const d = await confirm;
  expect(d.message()).toBe('Delete "Car"? Its records become uncategorised, and its budgets and the shops filed under it are forgotten.');
  await d.accept();
  await deleting;
  await page.getByRole('button', { name: 'Add category Car' }).click();
  await expect(page.getByRole('button', { name: 'Edit category Car' })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Add category Car' })).toHaveCount(0);
  await expect(page.getByText('More categories')).toHaveCount(0);
});

test('a new user is walked through setting up, and daily adding remembers things', async ({ page }) => {
  await signUp(page);
  const setup = page.getByLabel('Get set up');
  await expect(setup).toContainText('0 of 3');
  await expect(setup).toContainText('Amounts are in JPY');

  // Pick the kind, tap a suggested name, and say what's owed on the card.
  await setup.getByRole('button', { name: 'Add account' }).click();
  const acct = page.getByRole('dialog', { name: 'New account' });
  await expect(acct.getByLabel('Name', { exact: true })).toHaveCount(0); // asks the kind first
  await acct.getByRole('radio', { name: 'Credit card' }).check();
  await acct.getByRole('button', { name: 'PayPay Card' }).click();
  await expect(acct.getByLabel('Name', { exact: true })).toHaveValue('PayPay Card');
  await acct.getByLabel('Owed today (JPY)').fill('12000');
  await acct.getByRole('button', { name: 'Add account' }).click();
  await expect(page.getByText('Added PayPay Card')).toBeVisible();
  await expect(setup).toContainText('1 of 3');
  await expect(page.getByRole('list', { name: 'Accounts' })).toContainText('-¥12,000');

  // The first record, on the card.
  await page.getByRole('button', { name: 'Add record' }).click();
  let rec = page.getByRole('dialog', { name: 'New record' });
  await rec.getByLabel('Amount (JPY)').fill('640');
  await rec.getByRole('radio', { name: 'Food & Drinks' }).check();
  await rec.getByLabel('Payee').fill('Matsuya');
  await rec.getByLabel('Account').selectOption({ label: 'PayPay Card' });
  await rec.getByRole('button', { name: 'Save' }).click();
  await expect(page.getByText('Added ¥640')).toBeVisible();

  // Next time: the card is preselected, and the payee brings its category.
  await page.getByRole('button', { name: 'Add record' }).click();
  rec = page.getByRole('dialog', { name: 'New record' });
  await expect(rec.getByLabel('Account').locator('option:checked')).toHaveText('PayPay Card');
  await rec.getByLabel('Amount (JPY)').fill('720');
  await expect(rec.locator('#payees option[value="Matsuya"]')).toHaveCount(1);
  await rec.getByLabel('Payee').fill('Matsuya');
  await expect(rec.getByRole('radio', { name: 'Food & Drinks' })).toBeChecked();

  // Hiding the checklist keeps it hidden.
  await page.keyboard.press('Escape');
  await setup.getByRole('button', { name: 'Hide' }).click();
  await page.reload();
  await expect(page.getByText('Total balance')).toBeVisible();
  await expect(page.getByLabel('Get set up')).toHaveCount(0);
});

test('categories wear line icons, and a new one can pick its own', async ({ page }) => {
  await signUp(page);
  await page.goto('/app/#/settings?section=categories');
  await page.getByRole('button', { name: 'Add category' }).click();
  const dialog = page.getByRole('dialog', { name: 'New category' });
  await dialog.getByLabel('Name').fill('Cafés');
  await dialog.getByRole('radio', { name: 'Coffee' }).check();
  await dialog.getByRole('button', { name: 'Save' }).click();
  const chip = page.getByRole('button', { name: 'Edit category Cafés' });
  await expect(chip.locator('svg')).toHaveCount(1);
  // No emoji anywhere on the page.
  expect(await page.locator('main').innerText()).not.toMatch(/\p{Extended_Pictographic}/u);
});

test('import PayPay transaction history', async ({ page }) => {
  await signUp(page);
  const card = await (await page.request.post('/api/accounts', { data: { name: 'PayPay Card', kind: 'credit_card' } })).json();
  const now = new Date();
  const p = (n) => String(n).padStart(2, '0');
  const day = `${now.getFullYear()}/${p(now.getMonth() + 1)}/${p(now.getDate())}`;
  // This one arrived by email already; the import must skip it.
  const emailed = { kind: 'expense', amount: 1280, account_id: card.id, occurred_at: `${day.replaceAll('/', '-')}T12:05:00+09:00`, payee: 'SEVEN-ELEVEN' };
  expect((await page.request.post('/api/transactions', { data: emailed })).status()).toBe(201);

  await page.goto('/app/#/settings?section=import');
  await page.getByLabel('PayPay CSV file').setInputFiles({ name: 'paypay.csv', mimeType: 'text/csv', buffer: Buffer.from(payPayCsv(day)) });
  await expect(page.getByText(/^3 payments/)).toBeVisible();
  await expect(page.getByLabel(/^PayPayカード/)).toHaveValue(String(card.id));
  await expect(page.getByLabel(/^PayPay残高/).locator('option:checked')).toHaveText('New account: PayPay');
  await expect(page.getByLabel(/^PayPay銀行/).locator('option:checked')).toHaveText('New account: PayPay銀行');
  await expect(page.getByLabel('Category for ローソン').locator('option:checked')).toHaveText('Food & Drinks');
  await page.getByRole('button', { name: 'Import 3 payments' }).click();
  await expect(page.getByRole('status')).toContainText('Imported 2 records, skipped 1 already recorded from notifications or by hand, created 2 accounts');

  await page.goto('/app/#/records');
  await expect(page.getByRole('button', { name: /ローソン, -¥1,200/ })).toBeVisible();
  await expect(page.getByRole('button', { name: /SEVEN-ELEVEN/ })).toHaveCount(1);
});

/** A PayPay 取引履歴 export: a balance payment, a card payment, and a charge from the bank. */
function payPayCsv(day) {
  return [
    '取引日,出金金額（円）,入金金額（円）,海外出金金額,通貨,変換レート（円）,利用国,取引内容,取引先,取引方法,支払い区分,利用者,取引番号',
    `${day} 12:00,"1,200",-,-,-,-,-,支払い,ローソン,PayPay残高,-,本人,e2e-001`,
    `${day} 12:05,"1,280",-,-,-,-,-,支払い,お店,PayPayカード,一回払い,本人,e2e-002`,
    `${day} 09:00,-,"5,000",-,-,-,-,チャージ,PayPay銀行,PayPay銀行,-,本人,e2e-003`,
  ].join('\n');
}

test('choose records to recategorise, move and delete them together', async ({ page }) => {
  await signUp(page);
  const api = page.request;
  const overview = await (await api.get('/api/wallet')).json();
  const cash = overview.accounts[0].id;
  await api.post('/api/accounts', { data: { name: 'Bank', kind: 'bank' } });
  const at = new Date().toISOString();
  for (const [payee, kind] of [['Bakery', 'expense'], ['Cafe', 'expense'], ['Kiosk', 'expense'], ['Refund', 'income']])
    expect((await api.post('/api/transactions', { data: { kind, amount: 500, account_id: cash, occurred_at: at, payee } })).status()).toBe(201);

  await page.goto('/app/#/records');
  await page.getByRole('button', { name: 'Select' }).click();
  const bar = page.getByRole('region', { name: 'Chosen records' });
  await expect(bar).toContainText('Choose records');

  // An expense category fits the expenses; the refund stays as it was.
  await page.getByRole('checkbox', { name: /Bakery/ }).check();
  await page.getByRole('checkbox', { name: /Refund/ }).check();
  await expect(bar).toContainText('2 chosen');
  await bar.getByLabel('Set category').selectOption({ label: 'Food & Drinks' });
  await expect(page.getByText('Recategorised 1 record. 1 didn’t fit and stayed as they were.')).toBeVisible();
  await expect(page.getByRole('checkbox', { name: /Bakery/ })).toBeChecked();

  // Move every record to the bank.
  await bar.getByRole('button', { name: 'Choose all 4' }).click();
  await bar.getByLabel('Move to account').selectOption({ label: 'Bank' });
  await expect(page.getByText('Moved 4 records.')).toBeVisible();
  await page.getByRole('button', { name: 'Done' }).click();
  await expect(page.getByRole('button', { name: /Bakery/ })).toContainText('Food & Drinks · Bank');

  // Delete two.
  await page.getByRole('button', { name: 'Select' }).click();
  await page.getByRole('checkbox', { name: /Cafe/ }).check();
  await page.getByRole('checkbox', { name: /Kiosk/ }).check();
  page.once('dialog', (d) => d.accept());
  await bar.getByRole('button', { name: 'Delete' }).click();
  await expect(page.getByText('Deleted 2 records.')).toBeVisible();
  await expect(page.getByRole('checkbox', { name: /Cafe/ })).toHaveCount(0);
  await expect(page.getByRole('checkbox', { name: /Bakery/ })).toBeVisible();
  // Undo brings both back.
  await page.getByRole('button', { name: 'Undo' }).click();
  await expect(page.getByText('Restored 2 records.')).toBeVisible();
  await expect(page.getByRole('checkbox', { name: /Cafe/ })).toBeVisible();
  await expect(page.getByRole('checkbox', { name: /Kiosk/ })).toBeVisible();
});

test('a page seen before shows at once while it reloads, unless something changed', async ({ page }) => {
  await signUp(page);
  await page.getByRole('button', { name: 'Add record' }).click();
  const dialog = page.getByRole('dialog', { name: 'New record' });
  await dialog.getByLabel('Amount (JPY)').fill('1200');
  await dialog.getByLabel('Payee').fill('Ramen Taro');
  await dialog.getByRole('button', { name: 'Save' }).click();
  const latest = page.getByRole('list', { name: 'Latest records' });
  await expect(latest).toContainText('-¥1,200');

  // Hold back the records until told, to see what shows meanwhile.
  let gate = Promise.resolve();
  let release = () => {};
  const hold = () => (gate = new Promise((r) => (release = r)));
  await page.route('**/api/transactions?*', async (route) => {
    await gate;
    await route.continue();
  });
  const nav = page.getByRole('navigation', { name: 'Main' });

  // Back on Home, the records it showed before are there straight away.
  await nav.getByRole('link', { name: 'Records' }).click();
  await expect(page.getByRole('button', { name: /Ramen Taro/ })).toBeVisible();
  hold();
  await nav.getByRole('link', { name: 'Home' }).click();
  await expect(latest).toContainText('-¥1,200');
  release();

  // A search seen before, then an edit made elsewhere: the search waits for
  // the new amount instead of showing the old one.
  await page.goto('/app/#/records?q=ramen');
  await expect(page.getByRole('button', { name: /Ramen Taro, -¥1,200/ })).toBeVisible();
  await nav.getByRole('link', { name: 'Home' }).click();
  await latest.getByRole('button', { name: /Ramen Taro/ }).click();
  const edit = page.getByRole('dialog', { name: 'Edit record' });
  await edit.getByLabel('Amount (JPY)').fill('980');
  await edit.getByRole('button', { name: 'Save' }).click();
  await page.getByRole('button', { name: 'Save changes' }).click();
  await expect(latest).toContainText('-¥980');
  hold();
  await page.goto('/app/#/records?q=ramen');
  await expect(page.getByText('Loading…')).toBeVisible();
  await expect(page.getByText('-¥1,200')).toHaveCount(0);
  release();
  await expect(page.getByRole('button', { name: /Ramen Taro, -¥980/ })).toBeVisible();
});

/** Holds back requests to url until the returned function is called. */
async function holdBack(page, url) {
  let answer = () => {};
  const held = new Promise((r) => (answer = r));
  await page.route(url, async (route) => {
    await held;
    await route.continue();
  });
  return answer;
}

/** Where the middle of the splash's logo is. */
async function logoCentre(page) {
  const box = await page.locator('.loading .logo').boundingBox();
  return { x: Math.round(box.x + box.width / 2), y: Math.round(box.y + box.height / 2) };
}

test('the splash stays in the middle of the screen while the app starts', async ({ page }) => {
  await signUp(page);
  await page.evaluate(() => localStorage.clear()); // a first launch: nothing kept yet
  const me = await holdBack(page, '**/api/me');
  const wallet = await holdBack(page, '**/api/wallet');
  const asked = page.waitForRequest('**/api/wallet');
  await page.goto('/app/');

  // Waiting for the session: the logo in the middle, and words after a moment.
  await expect(page.getByRole('status')).toContainText('Loading…');
  const viewport = page.viewportSize();
  const first = await logoCentre(page);
  expect(Math.abs(first.x - viewport.width / 2)).toBeLessThanOrEqual(2);
  expect(Math.abs(first.y - viewport.height / 2)).toBeLessThan(80);

  // Waiting for the wallet: the same splash, in the same place.
  me();
  await asked;
  await expect(page.getByRole('status')).toContainText('Loading…');
  expect(await logoCentre(page)).toEqual(first);

  wallet();
  await expect(page.getByText('Total balance')).toBeVisible();
  await expect(page.getByRole('status')).toHaveCount(0);
});

test('the app opens on the wallet it showed last time, before the server answers', async ({ page }) => {
  await signUp(page);
  const { accounts } = await (await page.request.get('/api/wallet')).json();
  const data = { kind: 'expense', amount: 1200, account_id: accounts[0].id, occurred_at: new Date().toISOString(), payee: 'Ramen Taro' };
  expect((await page.request.post('/api/transactions', { data })).status()).toBe(201);
  await page.reload();
  const latest = page.getByRole('list', { name: 'Latest records' });
  await expect(latest).toContainText('Ramen Taro');

  // The server is slow (or the phone offline): Home shows what it saw last time.
  const answer = await holdBack(page, '**/api/**');
  await page.reload();
  await expect(page.locator('.hero-figure')).toHaveText(/-¥1,200/);
  await expect(latest).toContainText('Ramen Taro');
  await expect(page.getByRole('status')).toHaveCount(0);
  answer();

  // Signing out forgets it: the next launch waits for the server, then asks to sign in.
  await page.goto('/app/#/settings');
  await page.getByRole('button', { name: 'Sign out' }).click();
  await expect(page.getByRole('heading', { name: 'Sign in' })).toBeVisible();
  expect(await page.evaluate(() => Object.keys(localStorage).filter((k) => k === 'wallet:me' || k === 'wallet:seen'))).toEqual([]);
});

test('a record added offline is kept on the phone and sent once back online', async ({ page, context }) => {
  await signUp(page);
  const latest = page.getByRole('list', { name: 'Latest records' });
  await context.setOffline(true);

  // Added without a connection: it shows, and counts, straight away.
  await page.getByRole('button', { name: 'Add record' }).click();
  const dialog = page.getByRole('dialog', { name: 'New record' });
  await dialog.getByLabel('Amount (JPY)').fill('1200');
  await dialog.getByLabel('Payee').fill('Ramen Taro');
  await dialog.getByRole('button', { name: 'Save' }).click();
  await expect(dialog).toBeHidden();
  await expect(page.getByText('Saved on this phone. It’s sent when you’re online.')).toBeVisible();
  await expect(latest).toContainText('Ramen Taro');
  await expect(latest).toContainText('Not sent yet');
  await expect(page.locator('.hero-figure')).toHaveText(/-¥1,200/);

  // It can still be changed before it goes.
  await latest.getByRole('button', { name: /Ramen Taro/ }).click();
  const edit = page.getByRole('dialog', { name: 'Edit record' });
  await expect(edit).toContainText('Saved on this phone.');
  await edit.getByLabel('Amount (JPY)').fill('980');
  await edit.getByRole('button', { name: 'Save' }).click();
  await page.getByRole('button', { name: 'Save changes' }).click();
  await expect(page.locator('.hero-figure')).toHaveText(/-¥980/);
  await page.screenshot({ path: test.info().outputPath('offline-home.png'), animations: 'disabled' });

  // Elsewhere in the app too, where the server would list it.
  await page.getByRole('navigation', { name: 'Main' }).getByRole('link', { name: 'Records' }).click();
  await expect(page.getByRole('button', { name: /Ramen Taro, -¥980/ })).toBeVisible();

  // Back online: it's sent, as edited, once.
  await context.setOffline(false);
  await expect(page.getByText('Not sent yet')).toHaveCount(0);
  await expect(page.getByRole('button', { name: /Ramen Taro, -¥980/ })).toBeVisible();
  const saved = await (await page.request.get('/api/transactions')).json();
  expect(saved.map((t) => [t.payee, t.amount])).toEqual([['Ramen Taro', 980]]);
  expect(await page.evaluate(() => localStorage.getItem('wallet:outbox'))).toBeNull();
});

test('searching records looks through every month', async ({ page }) => {
  await signUp(page);
  const { accounts } = await (await page.request.get('/api/wallet')).json();
  const now = new Date();
  const earlier = new Date(now.getFullYear(), now.getMonth() - 3, 10, 12);
  for (const [when, amount] of [[now, 650], [earlier, 720]]) {
    const data = { kind: 'expense', amount, account_id: accounts[0].id, occurred_at: when.toISOString(), payee: 'Blue Bottle' };
    expect((await page.request.post('/api/transactions', { data })).status()).toBe(201);
  }
  await page.request.post('/api/transactions', { data: { kind: 'expense', amount: 300, account_id: accounts[0].id, occurred_at: earlier.toISOString(), payee: 'Lawson' } });

  await page.goto('/app/#/records');
  await expect(page.getByRole('button', { name: /Blue Bottle/ })).toHaveCount(1); // this month only
  await page.getByLabel('Search records').fill('blue bottle');
  await page.getByLabel('Search records').press('Enter');
  await expect(page.getByText('Results from every month')).toBeVisible();
  await expect(page.getByRole('button', { name: /Blue Bottle/ })).toHaveCount(2);
  await expect(page.getByRole('button', { name: /Lawson/ })).toHaveCount(0);
  await expect(page.locator('.summary')).toContainText('¥1,370');
  await page.screenshot({ path: test.info().outputPath('records-search.png'), fullPage: true });

  await page.getByRole('button', { name: /^Back to / }).click();
  await expect(page.getByLabel('Search records')).toHaveValue('');
  await expect(page.getByRole('button', { name: 'Previous month' })).toBeVisible();
  await expect(page.getByRole('button', { name: /Blue Bottle/ })).toHaveCount(1);
});

test('on a phone, the add record sheet drags down to close', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await signUp(page);
  await page.getByRole('button', { name: 'Add record' }).click();
  const dialog = page.getByRole('dialog', { name: 'New record' });
  await expect(dialog).toBeVisible();
  const grab = dialog.locator('.dialog-grab');
  await expect(grab).toBeVisible();
  const box = await grab.boundingBox();
  const x = box.x + box.width / 2;
  const y = box.y + box.height / 2;

  // A short drag springs back.
  await page.mouse.move(x, y);
  await page.mouse.down();
  await page.mouse.move(x, y + 40, { steps: 8 });
  await page.mouse.up();
  await expect(dialog).toBeVisible();
  await page.waitForTimeout(300);
  await dialog.getByLabel('Amount (JPY)').fill('500');

  // A long one closes it.
  await page.mouse.move(x, y);
  await page.mouse.down();
  await page.mouse.move(x, y + 250, { steps: 15 });
  await page.mouse.up();
  await expect(dialog).toBeHidden();
});

test('on a wide screen, the sidebar stays put while the page scrolls', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 420 });
  await signUp(page);
  await expect(page.getByText('Total balance')).toBeVisible();
  await page.mouse.wheel(0, 2000);
  await expect.poll(() => page.evaluate(() => window.scrollY)).toBeGreaterThan(0);
  const nav = page.getByRole('navigation', { name: 'Main' });
  expect((await nav.boundingBox()).y).toBe(0);
  // The signed-in email sits at the bottom of the window, not partway up it.
  const foot = await nav.locator('.nav-foot').boundingBox();
  expect(foot.y + foot.height).toBeGreaterThan(420 - 40);
  expect(foot.y + foot.height).toBeLessThanOrEqual(420);
});
