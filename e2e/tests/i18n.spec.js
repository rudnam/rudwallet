// Japanese: a phone set to Japanese gets the app in Japanese, anyone can
// switch on the sign-in screen or in Settings › Language, and the choice is
// kept for the introduction page too. Also saves screenshots of the main
// screens in Japanese at phone and desktop width.
const { test, expect, uniqueEmail, PASSWORD, confirmationLink, payPayEmail, deliverEmail, inboxAddress } = require('./fixtures');

test.describe('on a phone set to Japanese', () => {
  test.use({ locale: 'ja-JP' });

  test('signing up, the app and a new wallet are in Japanese', async ({ page }) => {
    await page.goto('/app/');
    await expect(page.getByRole('heading', { name: 'ログイン' })).toBeVisible();
    await expect(page.locator('html')).toHaveAttribute('lang', 'ja');
    await page.getByRole('button', { name: 'はじめての方はこちら: アカウントを作成' }).click();
    await page.getByLabel('メールアドレス').fill(uniqueEmail());
    await page.getByLabel('パスワード').fill(PASSWORD);
    await page.getByRole('button', { name: 'アカウントを作成', exact: true }).click();
    await expect(page.getByText('総残高')).toBeVisible();

    // The wallet starts with Japanese categories and a 現金 account.
    await page.goto('/app/#/settings?section=categories');
    await expect(page.getByRole('button', { name: 'カテゴリ「食費」を編集' })).toBeVisible();
    await page.goto('/app/#/settings?section=accounts');
    await expect(page.getByRole('button', { name: '口座「現金」を編集' })).toBeVisible();

    // A record, with the amount typed full-width as a Japanese keyboard does.
    await page.getByRole('navigation', { name: 'メイン' }).getByRole('button', { name: '記録を追加' }).click();
    const dialog = page.getByRole('dialog', { name: '新しい記録' });
    await dialog.getByLabel('金額（JPY）').fill('１，２００');
    await dialog.getByLabel('食費').check();
    await dialog.getByLabel('支払先').fill('コメダ珈琲店');
    await dialog.getByRole('button', { name: '保存' }).click();
    await expect(page.getByText('¥1,200を追加しました')).toBeVisible();
    await page.goto('/app/#/records');
    await expect(page.getByRole('button', { name: /^コメダ珈琲店, -¥1,200$/ })).toBeVisible();

    // English in Settings › Language: the app, not the names the wallet has.
    await page.goto('/app/#/settings?section=language');
    // Picking a language draws the app afresh, so click (check() would wait on the old radio).
    await page.getByLabel('English').click();
    await expect(page.getByRole('heading', { name: 'Language' })).toBeVisible();
    await page.goto('/app/#/settings?section=categories');
    await expect(page.getByRole('button', { name: 'Edit category 食費' })).toBeVisible();

    // Automatic follows the phone again.
    await page.goto('/app/#/settings?section=language');
    await page.getByLabel('Automatic (日本語)').click();
    await expect(page.getByRole('heading', { name: '言語' })).toBeVisible();
    await expect(page.getByLabel('自動（日本語）')).toBeChecked();
  });

  test("the server's messages are shown in Japanese", async ({ page }) => {
    await page.goto('/app/');
    await page.getByLabel('メールアドレス').fill(uniqueEmail());
    await page.getByLabel('パスワード').fill('not the password');
    await page.getByRole('button', { name: 'ログイン', exact: true }).click();
    await expect(page.getByRole('alert')).toHaveText('メールアドレスかパスワードが違います');
  });
});

test('Japanese can be picked on the sign-in screen, and the introduction page follows', async ({ page }) => {
  await page.goto('/app/');
  await expect(page.getByRole('heading', { name: 'Sign in' })).toBeVisible();
  await page.getByRole('navigation', { name: 'Language' }).getByRole('button', { name: '日本語' }).click();
  await expect(page.getByRole('heading', { name: 'ログイン' })).toBeVisible();
  await page.reload();
  await expect(page.getByRole('heading', { name: 'ログイン' })).toBeVisible();

  await page.goto('/');
  await expect(page.getByRole('heading', { name: 'お金のことを、静かに整えて。' })).toBeVisible();
  await page.getByRole('button', { name: 'English' }).click();
  await expect(page.getByRole('heading', { name: 'Your money, quietly kept in order.' })).toBeVisible();
  await page.goto('/app/');
  await expect(page.getByRole('heading', { name: 'Sign in' })).toBeVisible();
});

/** A Japanese wallet with a believable month and one payment to review, through the API. */
async function seed(page) {
  const api = page.request;
  const ja = { headers: { 'Accept-Language': 'ja' } };
  const overview = await (await api.get('/api/wallet', ja)).json();
  const cat = (name) => overview.categories.find((c) => c.name === name).id;
  const cash = overview.accounts[0].id;
  const card = await (await api.post('/api/accounts', { data: { name: 'PayPayカード', kind: 'credit_card', color: '#c8463d' } })).json();
  const bank = await (await api.post('/api/accounts', { data: { name: '三菱UFJ銀行', kind: 'bank', initial_balance: 182000, color: '#3b6ea8' } })).json();
  const now = new Date();
  const at = (ago, d, h) => new Date(now.getFullYear(), now.getMonth() - ago, Math.min(d, now.getDate()), h).toISOString();
  const records = [0, 1, 2, 3].flatMap((ago) => [
    { kind: 'income', amount: 280000, account_id: bank.id, category_id: cat('給与'), occurred_at: at(ago, 1, 9), payee: '株式会社サンプル' },
    { kind: 'expense', amount: 68000, account_id: bank.id, category_id: cat('住居費'), occurred_at: at(ago, 1, 10), payee: '家賃' },
    { kind: 'expense', amount: 4373 - ago * 300, account_id: card.id, category_id: cat('食料品'), occurred_at: at(ago, 3, 17), payee: 'アピタ知立店' },
    { kind: 'expense', amount: 1820 + ago * 400, account_id: card.id, category_id: cat('食費'), occurred_at: at(ago, 6, 19), payee: '大戸屋 知立店' },
    { kind: 'expense', amount: 1430, account_id: cash, category_id: cat('食費'), occurred_at: at(ago, 8, 20), payee: 'コメダ珈琲店' },
    { kind: 'expense', amount: 6800, account_id: card.id, category_id: cat('水道光熱費'), occurred_at: at(ago, 9, 9), payee: '中部電力' },
  ]);
  for (const res of await Promise.all(records.map((r) => api.post('/api/transactions', { data: r })))) expect(res.status()).toBe(201);
  await api.post('/api/budgets', { data: { name: '外食', category_id: cat('食費'), amount: 5000 } });
  await api.post('/api/budgets', { data: { name: '全体', category_id: null, amount: 120000 } });
  await deliverEmail(api, await inboxAddress(page), payPayEmail({ merchant: 'IKEAオンライン', yen: 4994, when: new Date(), id: `ja-${Date.now()}-${Math.random()}` }));
}

for (const [name, viewport] of [
  ['phone', { width: 390, height: 844 }],
  ['desktop', { width: 1280, height: 800 }],
]) {
  test.describe(`Japanese screens at ${name} width`, () => {
    test.use({ locale: 'ja-JP', viewport });

    test('screens', async ({ page }) => {
      test.slow();
      const shot = async (label) => {
        const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
        expect(overflow, `${label} overflows sideways`).toBeLessThanOrEqual(0);
        await page.screenshot({ path: test.info().outputPath(`ja-${name}-${label}.png`), fullPage: true });
      };

      await page.goto('/');
      await expect(page.getByRole('heading', { name: 'お金のことを、静かに整えて。' })).toBeVisible();
      await page.evaluate(() => document.documentElement.classList.remove('motion')); // show every section at once
      await shot('0-landing');

      await page.goto('/app/');
      await expect(page.getByRole('heading', { name: 'ログイン' })).toBeVisible();
      await shot('1-sign-in');

      const email = uniqueEmail();
      expect((await page.request.post('/api/signup', { data: { email, password: PASSWORD } })).status()).toBe(201);
      await page.request.get(await confirmationLink(page.request, email));
      await seed(page);
      await page.goto('/app/');
      await expect(page.getByRole('link', { name: /確認待ちの支払いが1件/ })).toBeVisible();
      await expect(page.getByRole('list', { name: 'カテゴリ別の支出' })).toBeVisible();
      await shot('2-overview');

      await page.goto('/app/#/stats');
      await expect(page.getByLabel('傾向')).toBeVisible();
      await shot('3-stats');

      await page.goto('/app/#/review');
      await expect(page.getByRole('listitem', { name: 'IKEAオンライン' })).toBeVisible();
      await shot('4-review');

      await page.goto('/app/#/records');
      await expect(page.getByRole('button', { name: /家賃/ }).first()).toBeVisible();
      await shot('5-records');

      await page.getByRole('button', { name: '記録を追加' }).last().click();
      await expect(page.getByRole('dialog', { name: '新しい記録' })).toBeVisible();
      await shot('6-new-record');
      await page.keyboard.press('Escape');

      await page.goto('/app/#/budgets');
      await expect(page.getByRole('list', { name: '予算' })).toBeVisible();
      await shot('7-budgets');

      await page.goto('/app/#/settings');
      await expect(page.getByRole('navigation', { name: '設定の項目' })).toBeVisible();
      await shot('8-settings');

      await page.goto('/app/#/settings?section=language');
      await expect(page.getByRole('radiogroup', { name: '言語' })).toBeVisible();
      await shot('9-settings-language');

      await page.goto('/app/#/settings?section=email');
      await expect(page.getByTestId('inbox-address')).toBeVisible();
      await shot('10-settings-email');
    });
  });
}
