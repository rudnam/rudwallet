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

  // The boxes are the most used categories (none yet: main ones first), then All.
  await page.goto('/app/#/');
  await page.getByRole('button', { name: 'Add record' }).click();
  const rec = page.getByRole('dialog', { name: 'New record' });
  await rec.getByLabel('Amount (JPY)').fill('600');
  await expect(rec.getByRole('radio', { name: 'Bakeries' })).toHaveCount(0);
  // All opens the picker: Bakeries is under Food & Drinks, found by searching too.
  await rec.getByRole('button', { name: 'All', exact: true }).click();
  const picker = page.getByRole('dialog', { name: 'Choose a category' });
  await picker.getByRole('searchbox', { name: 'Find a category' }).fill('bake');
  await expect(picker.getByRole('listitem')).toHaveCount(1);
  await picker.getByRole('button', { name: /Bakeries/ }).click();
  await expect(picker).toBeHidden();
  // It's chosen, and takes the last box.
  await expect(rec.getByRole('radio', { name: 'Bakeries' })).toBeChecked();
  await page.screenshot({ path: test.info().outputPath('add-subcategory.png'), fullPage: true });
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
  await page.screenshot({ path: test.info().outputPath('categories-list.png'), fullPage: true });
  await page.getByRole('button', { name: /Match the new default categories/ }).click();
  const offer = page.getByRole('dialog', { name: 'Match the new default categories' });
  await expect(offer.getByRole('checkbox', { name: 'Groceries under Food & Drinks' })).toBeChecked();
  await expect(offer.getByRole('checkbox', { name: 'Bills & Utilities under Housing' })).toBeChecked();
  // Each with its icon.
  await expect(offer.locator('label.check .cat-icon')).toHaveCount(await offer.locator('label.check').count());
  await page.screenshot({ path: test.info().outputPath('regroup-offer.png'), fullPage: true });
  await offer.getByRole('button', { name: 'Apply' }).click();
  await expect(offer).toBeHidden();
  await expect(page.getByRole('button', { name: /Match the new default categories/ })).toHaveCount(0);
  await expect(page.getByRole('button', { name: 'Edit category Food & Drinks › Groceries' })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Edit category Housing › Bills & Utilities' })).toBeVisible();
});

// The boxes open in the order of use and stay in it: no jump once the
// counts come in, on the first form since the page loaded or after.
test('the add form opens with the most used categories first, and they stay put', async ({ page }) => {
  await signUp(page);
  const { accounts, categories } = await (await page.request.get('/api/wallet')).json();
  const taxes = categories.find((c) => c.name === 'Taxes & Fees');
  for (let i = 0; i < 3; i++) {
    const body = { kind: 'expense', amount: 100, account_id: accounts[0].id, category_id: taxes.id, occurred_at: new Date().toISOString() };
    expect((await page.request.post('/api/transactions', { data: body })).status()).toBe(201);
  }
  await page.goto('/app/#/');
  for (const time of ['first', 'again']) {
    await page.getByRole('button', { name: 'Add record' }).click();
    const rec = page.getByRole('dialog', { name: 'New record' });
    const boxes = rec.getByRole('group', { name: 'Category' });
    await expect(boxes).toBeVisible();
    // The moment they show, the most used is first...
    expect((await boxes.locator('.category-name').first().textContent()), time).toBe('Taxes & Fees');
    // ...and a moment later it still is.
    await page.waitForTimeout(1500);
    expect((await boxes.locator('.category-name').first().textContent()), time).toBe('Taxes & Fees');
    await rec.getByRole('button', { name: 'Close' }).click();
    await expect(rec).toBeHidden();
  }
});
