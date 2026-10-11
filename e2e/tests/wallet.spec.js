const { test, expect, signUp } = require('./fixtures');

test('add, edit and delete a record', async ({ page }) => {
  await signUp(page);
  await expect(page.getByRole('heading', { name: 'Overview' })).toBeVisible();
  await expect(page.getByText('No spending recorded this month yet.')).toBeVisible();

  await page.getByRole('button', { name: 'Add record' }).click();
  const dialog = page.getByRole('dialog', { name: 'New record' });
  await dialog.getByLabel('Amount (JPY)').fill('1,200');
  await dialog.getByLabel('Category').selectOption({ label: '🍜 Food & Drinks' });
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
  await page.getByRole('button', { name: 'Add account' }).click();
  const acct = page.getByRole('dialog', { name: 'New account' });
  await acct.getByLabel('Name').fill('MUFG');
  await acct.getByLabel('Starting balance (JPY)').fill('50000');
  await acct.getByRole('button', { name: 'Save' }).click();
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
  await b.getByLabel('Category').selectOption({ label: '🍜 Food & Drinks' });
  await b.getByLabel('Monthly limit (JPY)').fill('1000');
  await b.getByRole('button', { name: 'Save' }).click();
  await expect(page.getByRole('list', { name: 'Budgets' })).toContainText('¥1,000 left');

  await page.getByRole('button', { name: 'Add record' }).click();
  const lunch = page.getByRole('dialog', { name: 'New record' });
  await lunch.getByLabel('Amount (JPY)').fill('1200');
  await lunch.getByLabel('Category').selectOption({ label: '🍜 Food & Drinks' });
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
  await page.getByLabel('CSV file').setInputFiles({ name: 'records.csv', mimeType: 'text/csv', buffer: Buffer.from(csv) });
  await expect(page.getByRole('status')).toContainText('Imported 2 records, created 1 accounts');
  await expect(page.getByRole('list', { name: 'Accounts' })).toContainText('Rakuten Bank');

  // The same file again adds nothing.
  await page.getByLabel('CSV file').setInputFiles({ name: 'records.csv', mimeType: 'text/csv', buffer: Buffer.from(csv) });
  await expect(page.getByRole('status')).toContainText('Imported 0 records, skipped 2 already imported');

  await page.goto('/#/records');
  await expect(page.getByRole('button', { name: /Aeon, -¥2,480/ })).toBeVisible();
});
