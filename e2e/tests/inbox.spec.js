const { test, expect, signUp, payPayEmail, deliverEmail, inboxAddress } = require('./fixtures');

test('a forwarded PayPay email becomes a record to review, and teaches its category', async ({ page }) => {
  await signUp(page);

  // Settings shows the private forwarding address and the Gmail steps.
  await page.goto('/app/#/settings?section=email');
  const address = page.getByTestId('inbox-address');
  await expect(address).toHaveText(/^pay-[a-z0-9]+@in\.example\.test$/);
  await expect(page.getByText('Add a forwarding address')).toBeVisible();
  const to = await inboxAddress(page);

  // The mail provider delivers a usage alert for an online purchase.
  const run = Date.now();
  await deliverEmail(page.request, to, payPayEmail({ merchant: 'IKEAオンライン', yen: 4994, when: new Date(), id: `ikea-${run}` }));

  await page.goto('/app/#/');
  const banner = page.getByRole('link', { name: /1 payment to review/ });
  await expect(banner).toBeVisible();
  await expect(page.getByRole('navigation', { name: 'Main' }).getByLabel('1 to review')).toBeVisible();
  await banner.click();

  // Review: pick a category and confirm in place.
  await expect(page.getByRole('heading', { name: 'To review' })).toBeVisible();
  const item = page.getByRole('listitem', { name: 'IKEAオンライン' });
  await expect(item).toContainText('-¥4,994');
  await expect(item).toContainText('PayPayカード ゴールド（Visa）');
  await item.getByLabel('Category for IKEAオンライン').selectOption({ label: 'Shopping' });
  await item.getByRole('button', { name: 'Confirm' }).click();
  await expect(page.getByText('All caught up.')).toBeVisible();
  await expect(page.getByRole('navigation', { name: 'Main' }).getByLabel(/to review/)).toHaveCount(0);

  // It now counts, and is marked as recorded from an email.
  await page.goto('/app/#/records');
  const row = page.getByRole('button', { name: /IKEAオンライン, -¥4,994/ });
  await expect(row).toContainText('Email');
  await expect(row).toContainText('Shopping');

  // The next purchase there arrives with the category already filled in.
  await deliverEmail(page.request, to, payPayEmail({ merchant: 'IKEAオンライン', yen: 1290, when: new Date(), id: `ikea2-${run}` }));
  await page.goto('/app/#/review');
  await expect(page.getByLabel('Category for IKEAオンライン')).toHaveValue(/\d+/);
  await expect(page.getByLabel('Category for IKEAオンライン').locator('option:checked')).toHaveText('Shopping');

  // With auto-confirm on, a known shop goes straight in.
  await page.goto('/app/#/settings?section=email');
  const saved = page.waitForResponse((r) => r.url().endsWith('/api/settings') && r.request().method() === 'PUT');
  await page.getByLabel('Confirm payments automatically when the shop\'s category is already known').check();
  expect((await saved).status()).toBe(200);
  await deliverEmail(page.request, to, payPayEmail({ merchant: 'IKEAオンライン', yen: 799, when: new Date(), id: `ikea3-${run}` }));
  await page.goto('/app/#/records');
  await expect(page.getByRole('button', { name: /IKEAオンライン, -¥799/ })).not.toContainText('To review');
});

test('test the automation by pasting an email', async ({ page }) => {
  await signUp(page);
  await page.goto('/app/#/settings?section=email');
  await page.getByText('Test with an email').click();
  await page.getByLabel('Email source').fill(
    payPayEmail({ merchant: 'セブン－イレブン', yen: 429, when: new Date(), id: `paste-${Date.now()}` }),
  );
  await page.getByRole('button', { name: 'Read email' }).click();
  await expect(page.getByRole('status')).toHaveText('Found a payment. It is waiting under To review.');
  await expect(page.getByRole('list', { name: 'Recent emails' })).toContainText('Recorded');

  // Pasting something that isn't an email explains what to copy.
  await page.getByLabel('Email source').fill('hello');
  await page.getByRole('button', { name: 'Read email' }).click();
  await expect(page.getByRole('alert')).toContainText('Show original');
});

test('emails without a rule wait for the user', async ({ page }) => {
  await signUp(page);
  const to = await inboxAddress(page);
  const raw = [
    'From: Example Shop <orders@shop.example>',
    'Subject: Your order receipt',
    'Content-Type: text/plain; charset=UTF-8',
    `Message-ID: <shop-${Date.now()}@shop.example>`,
    '',
    'Thanks for your order! Total charged: 2,500 JPY.',
  ].join('\r\n');
  await deliverEmail(page.request, to, raw);
  await page.goto('/app/#/review');
  const email = page.getByRole('list', { name: 'Unread emails' }).getByRole('listitem');
  await expect(email).toContainText('Your order receipt');
  await email.getByRole('button', { name: 'Add as record' }).click();
  const dialog = page.getByRole('dialog', { name: 'New record' });
  await expect(dialog.getByLabel('Note')).toHaveValue('Your order receipt');
  await dialog.getByLabel('Amount (JPY)').fill('2500');
  await dialog.getByLabel('Payee').fill('Example Shop');
  await dialog.getByRole('button', { name: 'Save' }).click();
  await expect(page.getByText('All caught up.')).toBeVisible();
});

test('a one-off category leaves the shop alone, Confirm all clears the rest, and rules can be changed', async ({ page }) => {
  await signUp(page);
  const to = await inboxAddress(page);
  const run = Date.now();
  const email = (merchant, yen, id) => deliverEmail(page.request, to, payPayEmail({ merchant, yen, when: new Date(), id: `${id}-${run}` }));

  // A shop added by hand fills in its payments' category.
  await page.goto('/app/#/settings?section=rules');
  await expect(page.getByText('No shops yet.')).toBeVisible();
  await page.getByLabel('Shop name').fill('ローソン');
  await page.getByLabel('Category for the new shop').selectOption({ label: 'Food & Drinks' });
  await page.getByRole('button', { name: 'Add' }).click();
  await expect(page.getByRole('list', { name: 'Shops' }).getByRole('listitem', { name: 'ローソン' })).toBeVisible();

  await email('ローソン', 550, 'l1');
  await email('ローソン', 1200, 'l2');
  await email('無印良品', 1990, 'm1');
  await page.goto('/app/#/review');
  const items = page.getByRole('list', { name: 'Payments to review' }).getByRole('listitem');
  await expect(items).toHaveCount(3);

  // One Lawson payment was a gift: confirm it as such without remembering.
  const gift = items.filter({ hasText: '-¥1,200' });
  await gift.getByLabel('Category for ローソン').selectOption({ label: 'Shopping' });
  await expect(gift.getByLabel(/Use Shopping for ローソン from now on, instead of Food & Drinks/)).not.toBeChecked();
  await gift.getByRole('button', { name: 'Confirm' }).click();
  await expect(items).toHaveCount(2);

  // Pick the new shop's category, then confirm everything at once.
  await page.getByLabel('Category for 無印良品').selectOption({ label: 'Shopping' });
  await page.getByRole('button', { name: 'Confirm 2 with a category' }).click();
  await expect(page.getByText('All caught up.')).toBeVisible();

  // Lawson kept its category; the new shop and the card were learned.
  await page.goto('/app/#/settings?section=rules');
  const lawson = page.getByRole('list', { name: 'Shops' }).getByRole('listitem', { name: 'ローソン' });
  await expect(lawson.getByLabel('Category for ローソン').locator('option:checked')).toHaveText('Food & Drinks');
  await expect(lawson).toContainText('2 records');
  await expect(page.getByLabel('Category for 無印良品').locator('option:checked')).toHaveText('Shopping');
  await expect(page.getByRole('list', { name: 'Cards' })).toContainText('PayPayカード ゴールド（Visa）');

  // Forgetting a shop means its next payment waits without a category.
  await lawson.getByRole('button', { name: 'Forget' }).click();
  await expect(lawson).toHaveCount(0);
  await email('ローソン', 300, 'l3');
  await page.goto('/app/#/review');
  await expect(page.getByLabel('Category for ローソン')).toHaveValue('');
});
