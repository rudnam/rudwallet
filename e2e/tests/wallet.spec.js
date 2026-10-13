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
  await edit.getByRole('button', { name: 'Save' }).click();
  await expect(page.getByRole('button', { name: /Ramen Taro, -¥980/ })).toBeVisible();

  // Search, then delete.
  await page.getByLabel('Search records').fill('nothing like this');
  await page.getByLabel('Search records').press('Enter');
  await expect(page.getByText('No records match.')).toBeVisible();
  await page.getByLabel('Search records').fill('ramen');
  await page.getByLabel('Search records').press('Enter');
  page.once('dialog', (d) => d.accept());
  await page.getByRole('button', { name: /Ramen Taro/ }).click();
  await page.getByRole('dialog', { name: 'Edit record' }).getByRole('button', { name: 'Delete' }).click();
  await expect(page.getByText('No records match.')).toBeVisible();
});

test('a new account, a transfer, and a budget that runs over', async ({ page }) => {
  await signUp(page);
  await page.goto('/#/settings');
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
  await page.goto('/#/');
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

test('the currency can change until there are records', async ({ page }) => {
  await signUp(page);
  await page.goto('/#/settings?section=general');
  await page.getByLabel('Main currency').selectOption('USD');
  await page.goto('/#/');
  await expect(page.locator('.hero-figure')).toHaveText('$0.00');
  await page.getByRole('button', { name: 'Add record' }).click();
  await page.getByRole('dialog', { name: 'New record' }).getByLabel('Amount (USD)').fill('12.50');
  await page.getByRole('dialog', { name: 'New record' }).getByRole('button', { name: 'Save' }).click();
  await expect(page.locator('.hero-figure')).toHaveText('-$12.50');
  await page.goto('/#/settings?section=general');
  await page.getByLabel('Main currency').selectOption('EUR');
  await expect(page.getByRole('alert')).toHaveText("the currency can't change once you have records");
});

test('import records exported from Wallet by BudgetBakers', async ({ page }) => {
  await signUp(page);
  await page.goto('/#/settings?section=import');
  const now = new Date();
  const p = (n) => String(n).padStart(2, '0');
  const today = `${now.getFullYear()}-${p(now.getMonth() + 1)}-${p(now.getDate())} 08:00:00`;
  const csv = [
    'account;category;currency;amount;ref_currency_amount;type;payment_type;payment_type_local;note;date;gps_latitude;gps_longitude;gps_accuracy_in_meters;warranty_in_month;transfer;payee;labels;envelope_id;custom_category',
    `Cash;Groceries;JPY;-2480.00;-2480.00;Expenses;CASH;Cash;;${today};;;;0;false;Aeon;;1000;false`,
    `Rakuten Bank;Salary;JPY;250000.00;250000.00;Income;TRANSFER;Transfer;;${today};;;;0;false;;;5000;false`,
  ].join('\n');
  await page.getByLabel('CSV file', { exact: true }).setInputFiles({ name: 'records.csv', mimeType: 'text/csv', buffer: Buffer.from(csv) });
  await expect(page.getByRole('status')).toContainText('Imported 2 records, created 1 accounts');

  // The same file again adds nothing.
  await page.getByLabel('CSV file', { exact: true }).setInputFiles({ name: 'records.csv', mimeType: 'text/csv', buffer: Buffer.from(csv) });
  await expect(page.getByRole('status')).toContainText('Imported 0 records, skipped 2 already imported');
  await page.getByRole('link', { name: 'Settings', exact: true }).first().click();
  await page.getByRole('link', { name: /Accounts/ }).click();
  await expect(page.getByRole('list', { name: 'Accounts' })).toContainText('Rakuten Bank');

  await page.goto('/#/records');
  await expect(page.getByRole('button', { name: /Aeon, -¥2,480/ })).toBeVisible();
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
  await page.goto('/#/settings?section=categories');
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

  await page.goto('/#/settings?section=import');
  await page.getByLabel('PayPay CSV file').setInputFiles({ name: 'paypay.csv', mimeType: 'text/csv', buffer: Buffer.from(payPayCsv(day)) });
  await expect(page.getByText(/^3 payments/)).toBeVisible();
  await expect(page.getByLabel(/^PayPayカード/)).toHaveValue(String(card.id));
  await expect(page.getByLabel(/^PayPay残高/).locator('option:checked')).toHaveText('New account: PayPay');
  await expect(page.getByLabel(/^PayPay銀行/).locator('option:checked')).toHaveText('New account: PayPay銀行');
  await page.getByRole('button', { name: 'Import 3 payments' }).click();
  await expect(page.getByRole('status')).toContainText('Imported 2 records, skipped 1 already recorded from emails or by hand, created 2 accounts');

  await page.goto('/#/records');
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
  await page.goto('/#/records?q=ramen');
  await expect(page.getByRole('button', { name: /Ramen Taro, -¥1,200/ })).toBeVisible();
  await nav.getByRole('link', { name: 'Home' }).click();
  await latest.getByRole('button', { name: /Ramen Taro/ }).click();
  const edit = page.getByRole('dialog', { name: 'Edit record' });
  await edit.getByLabel('Amount (JPY)').fill('980');
  await edit.getByRole('button', { name: 'Save' }).click();
  await expect(latest).toContainText('-¥980');
  hold();
  await page.goto('/#/records?q=ramen');
  await expect(page.getByText('Loading…')).toBeVisible();
  await expect(page.getByText('-¥1,200')).toHaveCount(0);
  release();
  await expect(page.getByRole('button', { name: /Ramen Taro, -¥980/ })).toBeVisible();
});
