const { test, expect, signUp } = require('./fixtures');

// A new wallet's categories come as main ones with subcategories, and more
// can be made: the add form offers them once their main category is picked,
// and the main category's budget and records take them in.
test('a subcategory is made in Settings, picked under its main category, and counted in it', async ({ page }) => {
  await signUp(page);
  await page.goto('/app/#/settings?section=categories');
  await page.getByRole('button', { name: 'Add category' }).click();
  const dialog = page.getByRole('dialog', { name: 'New category' });
  await dialog.getByLabel('Name').fill('Bakeries');
  await dialog.getByLabel('Under').selectOption({ label: 'Food & Drinks' });
  await dialog.getByRole('radio', { name: 'Groceries' }).check();
  await dialog.getByRole('button', { name: 'Save' }).click();
  await expect(page.getByRole('button', { name: 'Edit category Food & Drinks › Bakeries' })).toBeVisible();
  await page.screenshot({ path: test.info().outputPath('settings-subcategories.png'), fullPage: true });

  // Food & Drinks has a subcategory now, so it stays a main category.
  await page.getByRole('button', { name: 'Edit category Food & Drinks', exact: true }).click();
  const food = page.getByRole('dialog', { name: 'Edit category' });
  await expect(food.getByText('It has subcategories, so it stays a main category.')).toBeVisible();
  await expect(food.getByLabel('Under')).toHaveCount(0);
  await food.getByRole('button', { name: 'Close' }).click();

  const { categories } = await (await page.request.get('/api/wallet')).json();
  const foodId = categories.find((c) => c.name === 'Food & Drinks').id;
  const cafes = categories.find((c) => c.name === 'Bakeries');
  expect(cafes.parent_id).toBe(foodId);
  expect((await page.request.post('/api/budgets', { data: { name: 'Eating out', category_id: foodId, amount: 1000 } })).status()).toBe(201);

  // The grid shows main categories. Tapping Food & Drinks chooses it and swaps
  // the boxes for its own: Food & Drinks first, then its subcategories, then Back.
  await page.goto('/app/#/');
  await page.getByRole('button', { name: 'Add record' }).click();
  const rec = page.getByRole('dialog', { name: 'New record' });
  await rec.getByLabel('Amount (JPY)').fill('600');
  await expect(rec.getByRole('radio', { name: 'Bakeries' })).toHaveCount(0);
  await expect(rec.getByRole('radio', { name: 'Groceries' })).toHaveCount(0);
  await rec.getByRole('radio', { name: 'Food & Drinks' }).click();
  const boxes = rec.getByRole('group', { name: 'Subcategories of Food & Drinks' });
  await expect(boxes.locator('.category-name')).toHaveText(['Food & Drinks', 'Groceries', 'Restaurants', 'Cafés', 'Delivery', 'Bakeries', 'Back']);
  await expect(boxes.getByRole('radio', { name: 'Food & Drinks' })).toBeChecked();
  await expect(rec.getByRole('radio', { name: 'Shopping' })).toHaveCount(0);
  await boxes.getByRole('radio', { name: 'Bakeries' }).click();
  await expect(boxes.getByRole('radio', { name: 'Bakeries' })).toBeChecked();
  await page.screenshot({ path: test.info().outputPath('add-subcategory.png'), fullPage: true });
  // Back: all the main categories again, Food & Drinks marked as holding the choice.
  await boxes.getByRole('button', { name: 'Back' }).click();
  await expect(rec.getByRole('radio', { name: 'Shopping' })).toBeVisible();
  await expect(rec.getByRole('radio', { name: 'Food & Drinks' })).toBeChecked();
  await rec.getByLabel('Payee').fill('Doutor');
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

// A wallet from before the defaults had subcategories is offered to group
// them like a new one's.
test('an old wallet groups its categories like a new one', async ({ page }) => {
  await signUp(page);
  // As an old wallet has them: Groceries and Bills & Utilities as main categories.
  const { categories } = await (await page.request.get('/api/wallet')).json();
  for (const name of ['Groceries', 'Bills & Utilities']) {
    const c = categories.find((x) => x.name === name);
    expect((await page.request.put(`/api/categories/${c.id}`, { data: { name: c.name, kind: c.kind, icon: c.icon, color: c.color, parent_id: null } })).status()).toBe(200);
  }
  await page.goto('/app/#/settings?section=categories');
  const offer = page.getByRole('region', { name: 'Group your categories' });
  await expect(offer.getByRole('listitem')).toHaveText(['Groceries under Food & Drinks', 'Bills & Utilities under Housing']);
  await page.screenshot({ path: test.info().outputPath('regroup-offer.png'), fullPage: true });
  await offer.getByRole('button', { name: 'Group them' }).click();
  await expect(offer).toBeHidden();
  await expect(page.getByRole('button', { name: 'Edit category Food & Drinks › Groceries' })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Edit category Housing › Bills & Utilities' })).toBeVisible();
});
