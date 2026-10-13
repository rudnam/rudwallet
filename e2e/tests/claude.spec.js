const { test, expect, signUp } = require('./fixtures');

const GOOD_KEY = 'sk-ant-e2e-good-key-0000'; // matches e2e/fake-claude.js

/** Records without a category, as an import leaves them. */
async function uncategorized(page) {
  const api = page.request;
  const overview = await (await api.get('/api/wallet')).json();
  const cash = overview.accounts[0].id;
  const now = new Date().toISOString();
  for (const [payee, amount] of [
    ['セブン－イレブン 知立店', 429],
    ['セブン－イレブン 知立店', 618],
    ['スギ薬局 大曽根店', 1025],
    ['Mystery Ltd', 3000],
  ]) {
    const res = await api.post('/api/transactions', { data: { kind: 'expense', amount, account_id: cash, occurred_at: now, payee } });
    expect(res.status()).toBe(201);
  }
}

test('Claude sorts uncategorised records with the user’s key', async ({ page }) => {
  await signUp(page);
  await uncategorized(page);

  // Without a key, sorting points to Settings.
  await page.goto('/#/organize');
  await expect(page.getByRole('heading', { name: 'Add your Claude key first.' })).toBeVisible();
  await page.getByRole('link', { name: 'Add it in Settings' }).click();

  // A key Claude refuses isn't saved; a good one is, and only its end is shown.
  await expect(page.getByRole('heading', { name: 'Claude', exact: true })).toBeVisible();
  await page.getByLabel('Anthropic API key').fill('sk-ant-wrong-key');
  await page.getByRole('button', { name: 'Save key' }).click();
  await expect(page.getByRole('alert')).toContainText("didn't accept");
  await page.getByLabel('Anthropic API key').fill(GOOD_KEY);
  await page.getByRole('button', { name: 'Save key' }).click();
  await expect(page.getByText('Key ending in 0000')).toBeVisible();
  await expect(page.getByText('4 records have no category.')).toBeVisible();

  // Claude suggests; nothing changes until the user files them.
  await page.getByRole('link', { name: 'Open' }).click();
  await page.getByRole('button', { name: 'Suggest categories' }).click();
  const list = page.getByRole('list', { name: 'Suggested categories' });
  await expect(list.getByRole('listitem')).toHaveCount(3);
  const seven = page.getByLabel('Category for セブン－イレブン 知立店');
  await expect(seven).toHaveValue(String(await categoryId(page, 'Food & Drinks')));
  await expect(list.getByRole('listitem', { name: 'セブン－イレブン 知立店' })).toContainText('2 records');
  await expect(page.getByLabel('Category for Mystery Ltd')).toHaveValue('0');
  await expect(list.getByRole('listitem', { name: 'Mystery Ltd' })).toContainText('no suggestion');

  // The user overrides one suggestion and files the rest.
  await page.getByLabel('Category for スギ薬局 大曽根店').selectOption({ label: 'Shopping' });
  await page.getByRole('button', { name: 'File 3 records' }).click();
  await expect(page.getByText('Sorted 3 records')).toBeVisible();
  await expect(page.getByText('You left 1 payee as they were.')).toBeVisible();

  // Only Mystery Ltd is left without a category.
  await page.goto('/#/records?category=none');
  await expect(page.getByRole('button', { name: /Mystery Ltd/ })).toBeVisible();
  await expect(page.getByRole('button', { name: /セブン/ })).toHaveCount(0);
  await page.goto(`/#/records?category=${await categoryId(page, 'Shopping')}`);
  await expect(page.getByRole('button', { name: /スギ薬局/ })).toBeVisible();
});

test('an MCP client connects with a token made in Settings', async ({ page }) => {
  await signUp(page);
  await uncategorized(page);
  await page.goto('/#/settings?section=claude');
  await expect(page.getByTestId('mcp-url')).toHaveText(/\/mcp$/);

  await page.getByLabel('New connection').fill('Claude Desktop');
  await page.getByRole('button', { name: 'Make a token' }).click();
  const token = (await page.getByTestId('mcp-token').textContent()).trim();
  expect(token).toMatch(/^wmcp_/);
  await expect(page.getByRole('list', { name: 'Connections' })).toContainText('not used yet');
  await page.getByText('How to connect').click();
  await expect(page.getByText(`claude mcp add --transport http wallet`)).toContainText(token);

  // The client, which is not the browser: no cookies, a bearer token.
  const call = (method, params) =>
    page.request.fetch('/mcp', {
      method: 'POST',
      headers: { Authorization: `Bearer ${token}`, Cookie: '' },
      data: { jsonrpc: '2.0', id: 1, method, params },
    });
  const init = await (await call('initialize', { protocolVersion: '2025-06-18', capabilities: {}, clientInfo: { name: 'e2e', version: '1' } })).json();
  expect(init.result.serverInfo.name).toBe('wallet');
  const res = await (await call('tools/call', { name: 'list_uncategorized_payees', arguments: {} })).json();
  expect(res.result.structuredContent.payees.map((p) => p.payee)).toContain('スギ薬局 大曽根店');

  // Disconnecting stops the token working.
  await page.reload();
  await expect(page.getByRole('list', { name: 'Connections' })).toContainText('last used');
  page.once('dialog', (d) => d.accept());
  await page.getByRole('button', { name: 'Disconnect' }).click();
  await expect(page.getByRole('list', { name: 'Connections' })).toHaveCount(0);
  expect((await call('ping', {})).status()).toBe(401);
});

async function categoryId(page, name) {
  const overview = await (await page.request.get('/api/wallet')).json();
  return overview.categories.find((c) => c.name === name).id;
}
