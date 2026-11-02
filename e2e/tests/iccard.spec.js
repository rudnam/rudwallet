// Importing a transit IC card's history (Settings › Import › IC card). The
// Android app reads the card over NFC; here the page acts as the app
// (asAndroidApp), and the card is what the phone would read.
const { test, expect, signUp, asAndroidApp, icCardRead, icEntry, tapICCard } = require('./fixtures');

test('read an IC card, and import its entries once', async ({ page }) => {
  test.slow(); // two reads, and screenshots at two widths
  await asAndroidApp(page, { icCard: icCardRead });
  await signUp(page);
  const cash = (await (await page.request.get('/api/wallet')).json()).accounts[0];

  await page.goto('/app/#/settings?section=import');
  await page.getByRole('button', { name: 'Read a card' }).click();
  await expect(page.getByText(/^Card ••CD56: 5 entries/)).toBeVisible();
  await expect(page.getByLabel(/^The card/)).toHaveValue('0');
  await expect(page.getByLabel(/^The card/).getByRole('option', { selected: true })).toHaveText('New account: IC card CD56');
  await expect(page.getByLabel(/^Top-ups are paid from/)).toHaveValue(String(cash.id));

  const rows = page.getByRole('table', { name: 'Card entries' });
  await expect(rows.getByRole('row').filter({ hasText: 'Shop payment' })).toContainText(/13:45.*-¥150/);
  await expect(rows.getByRole('row').filter({ hasText: 'Top-up' })).toContainText(`Transfer: ${cash.name} → The card`);
  await expect(rows.getByRole('row').filter({ hasText: 'Start: amount unknown' })).toContainText('¥1,000');
  // Stations and bus companies are named from the server's list.
  await expect(rows.getByRole('row').filter({ hasText: '新宿 → 渋谷' })).toContainText('山手線 → 東横線');
  await expect(rows.getByRole('row').filter({ hasText: '京王バス' })).toContainText('−¥210');
  await expect(page.getByLabel('Category for Train')).toHaveValue(/^c:/);
  // The card held ¥1,000 before its oldest entry: that's the starting balance.
  await expect(page.getByText('On the card: ¥2,480. IC card CD56 in the app: ¥1,480.')).toBeVisible();
  await expect(page.getByText("Importing changes IC card CD56's starting balance by +¥1,000, to ¥1,000.")).toBeVisible();

  await page.getByLabel('Category for Shop payment').selectOption({ label: 'Shopping' });
  for (const [label, viewport] of [
    ['phone', { width: 390, height: 844 }],
    ['desktop', { width: 1280, height: 800 }],
  ]) {
    await page.setViewportSize(viewport);
    expect(await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth), `${label} overflows sideways`).toBeLessThanOrEqual(0);
    await rows.scrollIntoViewIfNeeded();
    await page.screenshot({ path: test.info().outputPath(`${label}-import-ic-card.png`), fullPage: true, animations: 'disabled' });
  }

  await page.getByRole('button', { name: 'Import 5 entries' }).click();
  await expect(page.getByText(/^Imported 5 records, created 1 account/)).toBeVisible();
  await expect(page.getByText("IC card CD56's starting balance was changed by +¥1,000, to ¥1,000, so it matches the card.")).toBeVisible();

  // Read again: nothing new, and the card's account is remembered.
  await page.getByRole('button', { name: 'Read a card' }).click();
  await expect(page.getByLabel(/^The card/).getByRole('option', { selected: true })).toHaveText('IC card CD56');
  await expect(page.getByRole('button', { name: 'Import 0 entries' })).toBeDisabled();
  await expect(rows.getByRole('row').filter({ hasText: 'Already imported' })).toHaveCount(5);
  await expect(page.getByText('On the card: ¥2,480. IC card CD56 in the app: ¥2,480. They match.')).toBeVisible();

  await page.goto('/app/#/records?month=2026-10');
  await expect(page.getByRole('button', { name: /^Shop payment/ })).toBeVisible();
  await expect(page.getByRole('button', { name: /^京王バス/ })).toBeVisible();
  await page.getByRole('button', { name: /^新宿 → 渋谷/ }).click();
  await expect(page.getByLabel('Note')).toHaveValue('IC ••CD56 · 山手線 → 東横線 · 0-25-0A → 0-CE-26');
});

test('a tapped card opens its balance, and imports what is new', async ({ page }) => {
  test.slow(); // three taps, and screenshots at two widths
  await asAndroidApp(page, { icCard: icCardRead });
  await signUp(page);
  await expect.poll(() => page.evaluate(() => window.calls)).toContain('ICCard.addListener');

  // The first tap: the balance, read from the card, and its entries.
  await tapICCard(page, icCardRead);
  await expect(page).toHaveURL(/#\/card$/);
  const hero = page.locator('.hero');
  await expect(hero).toContainText('¥2,480');
  await expect(hero).toContainText('Last used Oct 4, 2026');
  await expect(page.getByText("5 entries aren't imported yet.")).toBeVisible();
  const entries = page.getByRole('list', { name: 'Card entries' });
  await expect(entries.getByRole('listitem').filter({ hasText: '新宿 → 渋谷' })).toContainText('−¥210');
  // Never imported: the full preview opens, to choose the accounts first.
  await expect(page.getByRole('button', { name: 'Review first' })).toHaveCount(0);
  await page.getByRole('button', { name: 'Import 5 entries' }).click();
  await expect(page).toHaveURL(/#\/settings\?section=import/);
  await expect(page.getByText(/^Card ••CD56: 5 entries/)).toBeVisible();
  await page.getByRole('button', { name: 'Import 5 entries' }).click();
  await expect(page.getByText(/^Imported 5 records/)).toBeVisible();

  // Tapped again from Home: nothing new.
  await page.goto('/app/#/home');
  await tapICCard(page, icCardRead);
  await expect(page.getByText("All of this card's entries are imported.")).toBeVisible();

  // Two trips later: imported in one tap, with the choices from last time.
  const later = {
    idm: icCardRead.idm,
    history: [icEntry(0x16, 0x01, 6, 2110, 107, null, [0x25, 0x07, 0x25, 0x0a]), icEntry(0x16, 0x01, 5, 2310, 106, null, [0x25, 0x0a, 0x25, 0x07]), ...icCardRead.history],
  };
  await tapICCard(page, later);
  await expect(page.locator('.hero')).toContainText('¥2,110');
  await expect(page.getByText('2 new entries since you last imported this card.')).toBeVisible();
  await expect(page.getByRole('list', { name: 'Card entries' }).getByRole('listitem').first()).toContainText(/渋谷 → 新宿.*New/s);
  for (const [label, viewport] of [
    ['phone', { width: 390, height: 844 }],
    ['desktop', { width: 1280, height: 800 }],
  ]) {
    await page.setViewportSize(viewport);
    expect(await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth), `${label} overflows sideways`).toBeLessThanOrEqual(0);
    await page.screenshot({ path: test.info().outputPath(`${label}-card.png`), fullPage: true, animations: 'disabled' });
  }
  await page.getByRole('button', { name: 'Import 2 entries' }).click();
  await expect(page.getByText(/^Imported 2 records/)).toBeVisible();
  await expect(page.getByRole('list', { name: 'Card entries' }).getByRole('listitem').first()).toContainText('Imported');
  const account = (await (await page.request.get('/api/wallet')).json()).accounts.find((a) => a.name === 'IC card CD56');
  expect(account.balance).toBe(2110);

  // A card that isn't a transit IC card says so.
  await tapICCard(page, { error: 'not-transit' });
  await expect(page.getByRole('alert')).toHaveText(/^That isn't a transit IC card/);
});

test('on the web, cards are read only in the app', async ({ page }) => {
  await signUp(page);
  await page.goto('/app/#/settings?section=import');
  await expect(page.getByText('This works in the RudWallet app on an Android phone with NFC.')).toBeVisible();
  await expect(page.getByRole('button', { name: 'Read a card' })).toHaveCount(0);
});
