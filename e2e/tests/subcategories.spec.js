const { test, expect, signUp } = require('./fixtures');

// A category can go under a main one: the add form offers it once its main
// category is picked, and the main category's budget and records take it in.
test('a subcategory is made in Settings, picked under its main category, and counted in it', async ({ page }) => {
  await signUp(page);
  await page.goto('/app/#/settings?section=categories');
  await page.getByRole('button', { name: 'Add category' }).click();
  const dialog = page.getByRole('dialog', { name: 'New category' });
  await dialog.getByLabel('Name').fill('Cafés');
  await dialog.getByLabel('Under').selectOption({ label: 'Food & Drinks' });
  await dialog.getByRole('radio', { name: 'Coffee' }).check();
  await dialog.getByRole('button', { name: 'Save' }).click();
  await expect(page.getByRole('button', { name: 'Edit category Food & Drinks › Cafés' })).toBeVisible();
  await page.screenshot({ path: test.info().outputPath('settings-subcategories.png'), fullPage: true });

  // Food & Drinks has a subcategory now, so it stays a main category.
  await page.getByRole('button', { name: 'Edit category Food & Drinks', exact: true }).click();
  const food = page.getByRole('dialog', { name: 'Edit category' });
  await expect(food.getByText('It has subcategories, so it stays a main category.')).toBeVisible();
  await expect(food.getByLabel('Under')).toHaveCount(0);
  await food.getByRole('button', { name: 'Close' }).click();

  const { categories } = await (await page.request.get('/api/wallet')).json();
  const foodId = categories.find((c) => c.name === 'Food & Drinks').id;
  const cafes = categories.find((c) => c.name === 'Cafés');
  expect(cafes.parent_id).toBe(foodId);
  expect((await page.request.post('/api/budgets', { data: { name: 'Eating out', category_id: foodId, amount: 1000 } })).status()).toBe(201);

  // The grid shows main categories; picking Food & Drinks offers Cafés.
  await page.goto('/app/#/');
  await page.getByRole('button', { name: 'Add record' }).click();
  const rec = page.getByRole('dialog', { name: 'New record' });
  await rec.getByLabel('Amount (JPY)').fill('600');
  await expect(rec.getByRole('radio', { name: 'Cafés' })).toHaveCount(0);
  const subs = rec.getByRole('group', { name: 'Subcategories of Food & Drinks' });
  await expect(subs).toHaveCount(0);
  await rec.getByRole('radio', { name: 'Food & Drinks' }).check();
  await subs.getByRole('button', { name: 'Cafés' }).click();
  await expect(subs.getByRole('button', { name: 'Cafés' })).toHaveAttribute('aria-pressed', 'true');
  await expect(rec.getByRole('radio', { name: 'Food & Drinks' })).toBeChecked();
  await rec.getByLabel('Payee').fill('Doutor');
  await page.screenshot({ path: test.info().outputPath('add-subcategory.png'), fullPage: true });
  await rec.getByRole('button', { name: 'Save' }).click();
  await expect(rec).toBeHidden();
  const list = await (await page.request.get('/api/transactions')).json();
  expect(list.find((t) => t.payee === 'Doutor').category_id).toBe(cafes.id);

  // The Food & Drinks budget counts it.
  await page.goto('/app/#/budgets');
  await expect(page.getByRole('list', { name: 'Budgets' })).toContainText('¥400 left');

  // Records filtered by Food & Drinks list it.
  await page.goto(`/app/#/records?category=${foodId}`);
  await expect(page.getByRole('button', { name: /Doutor, −¥600/ })).toBeVisible();
});
