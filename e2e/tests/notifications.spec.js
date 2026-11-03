// Payment notifications from the Android app. The listener itself is native
// (android/…/notify); here the phone's requests are sent through the API,
// and the browser checks what the user sees.
const { test, expect, signUp, PAYPAY, payPayCard, sendNotifications } = require('./fixtures');

const googleWallet = 'com.google.android.apps.walletnfcrel';

test('a Google Wallet payment waits for review, once', async ({ page }) => {
  await signUp(page);
  const api = page.request;
  const when = Date.now() - 60_000;
  const tap = { package: googleWallet, when, title: 'Lawson', text: '¥550 with Visa •••• 1234' };
  const send = (...notifications) => sendNotifications(api, ...notifications);
  const [first, other] = await send(tap, { package: googleWallet, when: when + 1, title: 'Card added', text: 'Visa •••• 1234 is ready' });
  expect(first.status).toBe('payment');
  expect(other.status).toBe('ignored');
  // Sent again (the phone retrying): nothing new.
  expect((await send(tap))[0].duplicate).toBe(true);

  await page.goto('/app/');
  await expect(page.getByRole('navigation', { name: 'Main' }).getByLabel('1 to review')).toBeVisible();
  await page.goto('/app/#/review');
  await expect(page.getByRole('listitem', { name: 'Lawson' })).toContainText('Google Wallet · Visa ••1234');

  await page.goto('/app/#/settings');
  await page.getByRole('link', { name: /Payment notifications/ }).click();
  await expect(page.getByText('This works in the RudWallet app for Android')).toBeVisible();
  const list = page.getByRole('list', { name: 'Recent notifications' });
  await expect(list.getByRole('listitem')).toHaveCount(2);
  await expect(list.getByRole('listitem').filter({ hasText: 'Lawson' })).toContainText('Recorded');
  await expect(list.getByRole('listitem').filter({ hasText: 'Card added' })).toContainText('Not a payment');
});

test('PayPay payments wait for review, and an offer is skipped', async ({ page }) => {
  await signUp(page);
  const api = page.request;
  const when = new Date(Date.now() - 60_000);
  const [card, balance, offer] = await sendNotifications(
    api,
    payPayCard({ merchant: 'ベルマート豊橋店', yen: 1291, when }),
    { package: PAYPAY, when: when.getTime() + 1000, title: 'PayPay', text: '取引が完了しました。\n金額：1,550円\n取引番号：05093837355250737154\n店舗名：GOアプリ' },
    { package: PAYPAY, when: when.getTime() + 2000, title: 'PayPay', text: '今だけ最大10%戻ってくる！' },
  );
  expect([card.status, balance.status, offer.status]).toEqual(['payment', 'payment', 'ignored']);

  await page.goto('/app/#/review');
  await expect(page.getByRole('listitem', { name: 'ベルマート豊橋店' })).toContainText('PayPay Card');
  await expect(page.getByRole('listitem', { name: 'GOアプリ' })).toContainText('PayPay');

  await page.goto('/app/#/settings?section=notifications');
  const list = page.getByRole('list', { name: 'Recent notifications' });
  await expect(list.getByRole('listitem')).toHaveCount(3);
  await expect(list.getByRole('listitem').filter({ hasText: '今だけ' })).toContainText('Not a payment');
  // Where payments go, and auto-confirm, are set here.
  await expect(page.getByLabel('Payments go to')).toBeVisible();
  await expect(page.getByLabel(/Confirm known shops/)).toBeVisible();
});

test('a possible duplicate shows the record it matches, and is deleted in one tap', async ({ page }) => {
  await signUp(page);
  const api = page.request;
  const overview = await (await api.get('/api/wallet')).json();
  const when = new Date(Date.now() - 60_000);
  const added = await api.post('/api/transactions', {
    data: { kind: 'expense', amount: 1820, account_id: overview.accounts[0].id, occurred_at: new Date(when.getTime() - 3600_000).toISOString(), payee: 'Lunch' },
  });
  expect(added.status()).toBe(201);
  await sendNotifications(api, payPayCard({ merchant: '大戸屋', yen: 1820, when }));

  await page.goto('/app/#/review');
  const card = page.getByRole('listitem', { name: '大戸屋' });
  await expect(card.getByRole('status')).toContainText('Possible duplicate of a record you already have:');
  await expect(card.getByRole('status')).toContainText(/Lunch · −¥1,820/);
  await card.getByRole('button', { name: 'Delete duplicate' }).click();
  await expect(page.getByText('Deleted 大戸屋')).toBeVisible();
  await expect(card).toHaveCount(0);
  // Nothing left to check: the section goes, leaving the records.
  await expect(page.getByRole('region', { name: 'Payments to review' })).toHaveCount(0);

  // Undo brings it back, still flagged.
  await page.getByRole('button', { name: 'Undo' }).click();
  await expect(page.getByText('Record restored')).toBeVisible();
  await expect(card.getByRole('button', { name: 'Delete duplicate' })).toBeVisible();
});
