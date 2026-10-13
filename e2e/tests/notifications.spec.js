// Payment notifications from the Android app. The listener itself is native
// (android/…/notify); here the phone's requests are sent through the API,
// and the browser checks what the user sees.
const { test, expect, signUp, payPayEmail, deliverEmail, inboxAddress } = require('./fixtures');

const googleWallet = 'com.google.android.apps.walletnfcrel';

test('a Google Wallet payment waits for review, once', async ({ page }) => {
  await signUp(page);
  const api = page.request;
  const when = Date.now() - 60_000;
  const tap = { package: googleWallet, when, title: 'Lawson', text: '¥550 with Visa •••• 1234' };
  const send = async (...notifications) => {
    const res = await api.post('/api/notifications', { data: { notifications } });
    expect(res.status()).toBe(200);
    return (await res.json()).notifications;
  };
  const [first, other] = await send(tap, { package: googleWallet, when: when + 1, title: 'Card added', text: 'Visa •••• 1234 is ready' });
  expect(first.status).toBe('payment');
  expect(other.status).toBe('ignored');
  // Sent again (the phone retrying): nothing new.
  expect((await send(tap))[0].duplicate).toBe(true);

  // The card's email for the same payment arrives a minute later: matched, not added.
  const id = `notify-${Date.now()}-${Math.random()}`;
  await deliverEmail(api, await inboxAddress(page), payPayEmail({ merchant: 'ローソン', yen: 550, when: new Date(when + 60_000), id }));

  await page.goto('/app/');
  await expect(page.getByRole('link', { name: /1 payment to review/ })).toBeVisible();
  await page.goto('/app/#/review');
  await expect(page.getByRole('listitem', { name: 'Lawson' })).toContainText('Google Wallet · Visa ••1234');
  await expect(page.getByRole('listitem', { name: 'ローソン' })).toHaveCount(0);

  await page.goto('/app/#/settings');
  await page.getByRole('link', { name: /Payment notifications/ }).click();
  await expect(page.getByText('This works in the Wallet app for Android')).toBeVisible();
  const list = page.getByRole('list', { name: 'Recent notifications' });
  await expect(list.getByRole('listitem')).toHaveCount(2);
  await expect(list.getByRole('listitem').filter({ hasText: 'Lawson' })).toContainText('Recorded');
  await expect(list.getByRole('listitem').filter({ hasText: 'Card added' })).toContainText('Not a payment');
});
