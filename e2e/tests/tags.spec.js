const { test, expect, signUp } = require('./fixtures');

test('tag records, filter by a tag, and tag many at once', async ({ page }) => {
  await signUp(page);
  const api = page.request;
  const overview = await (await api.get('/api/wallet')).json();
  const cash = overview.accounts[0].id;
  // One record last month, so the tag filter has to look past this one.
  const now = new Date();
  const lastMonth = new Date(now.getFullYear(), now.getMonth() - 1, 15, 12).toISOString();
  expect(
    (await api.post('/api/transactions', { data: { kind: 'expense', amount: 3000, account_id: cash, occurred_at: lastMonth, payee: 'Hotel', tags: ['Osaka trip'] } })).status(),
  ).toBe(201);

  // A new record: Enter adds a tag, a comma too, and "#" is dropped.
  await page.getByRole('button', { name: 'Add record' }).click();
  const dialog = page.getByRole('dialog', { name: 'New record' });
  await dialog.getByLabel('Amount (JPY)').fill('1200');
  await dialog.getByLabel('Payee').fill('Takoyaki');
  const tags = dialog.getByRole('combobox', { name: 'Tags' });
  await tags.fill('osaka TRIP');
  await tags.press('Enter');
  await tags.fill('#Food,');
  await expect(dialog.getByRole('list', { name: 'Tags' }).getByRole('listitem')).toHaveText(['#osaka TRIP', '#Food']);
  // Taking one off, and typing one without pressing Enter, which saving adds.
  await dialog.getByRole('button', { name: 'Remove tag Food' }).click();
  await tags.fill('Street food');
  await dialog.getByRole('button', { name: 'Save' }).click();
  await expect(dialog).toBeHidden();

  await page.getByRole('navigation', { name: 'Main' }).getByRole('link', { name: 'Records' }).click();
  // The tag was spelled as before; the record shows its tags.
  const row = page.getByRole('button', { name: /Takoyaki/ });
  await expect(row.getByLabel('Tags')).toHaveText('#Osaka trip#Street food');

  // Filtering by a tag looks through every month.
  const filter = page.getByRole('list', { name: 'Filter by tag' });
  await expect(filter).toContainText('#Osaka trip 2');
  await filter.getByRole('button', { name: /Osaka trip/ }).click();
  await expect(page.getByText('#Osaka trip in every month')).toBeVisible();
  await expect(page.getByRole('button', { name: /Hotel/ })).toBeVisible();
  await expect(page.getByRole('button', { name: /Takoyaki/ })).toBeVisible();
  await expect(page.locator('.summary')).toContainText('¥4,200');
  await page.getByRole('button', { name: /Back to/ }).click();
  await expect(page.getByRole('button', { name: /Hotel/ })).toHaveCount(0);

  // Editing lists the tag change, and undo puts it back.
  await row.click();
  const edit = page.getByRole('dialog', { name: 'Edit record' });
  await edit.getByRole('button', { name: 'Remove tag Street food' }).click();
  await edit.getByRole('button', { name: 'Save' }).click();
  await expect(page.getByRole('list', { name: 'Changes' })).toContainText('Tags#Osaka trip #Street food#Osaka trip');
  await page.getByRole('button', { name: 'Save changes' }).click();
  await expect(row.getByLabel('Tags')).toHaveText('#Osaka trip');
  await page.getByRole('button', { name: 'Undo' }).click();
  await expect(row.getByLabel('Tags')).toHaveText('#Osaka trip#Street food');

  // Search finds tags.
  await page.getByLabel('Search records').fill('street');
  await page.getByLabel('Search records').press('Enter');
  await expect(page.getByRole('button', { name: /Takoyaki/ })).toBeVisible();
  await page.getByRole('button', { name: /Back to/ }).click();

  // Tag many at once: a new tag, then take an old one off.
  expect((await api.post('/api/transactions', { data: { kind: 'expense', amount: 500, account_id: cash, occurred_at: now.toISOString(), payee: 'Kiosk' } })).status()).toBe(201);
  await page.reload();
  await page.getByRole('button', { name: 'Select' }).click();
  const bar = page.getByRole('region', { name: 'Chosen records' });
  await bar.getByRole('button', { name: 'Choose all 2' }).click();
  page.once('dialog', (d) => d.accept('#Expenses'));
  await bar.getByLabel('Tag records').selectOption({ label: 'New tag…' });
  await expect(page.getByText('Tagged 2 records #Expenses.')).toBeVisible();
  await expect(page.getByRole('checkbox', { name: /Kiosk/ }).locator('..')).toContainText('#Expenses');
  await bar.getByLabel('Tag records').selectOption({ value: 'remove:Street food' });
  await expect(page.getByText('Took #Street food off 1 record.')).toBeVisible();
  await page.getByRole('button', { name: 'Done' }).click();
  await expect(row.getByLabel('Tags')).toHaveText('#Osaka trip#Expenses');
});
