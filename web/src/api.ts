// Typed client for the Go API. Shapes mirror the Go handlers' JSON.
// Amounts are whole minor units of the user's currency (see Settings).
import { appHeaders, saveSession, server } from './native';

export interface Me {
  email: string;
  /** False for accounts that only sign in with Google. */
  has_password: boolean;
  /** The server emailed a link to confirm the address, and it hasn't been opened yet. */
  confirm_email: boolean;
  /** A new session's token, given to the Android app only (see native.ts). */
  token?: string;
}

/** The ways this server offers to sign in. */
export interface AuthOptions {
  google: boolean;
}

export interface Settings {
  currency: string;
  /** Decimal places: 0 for JPY (1250 = ¥1,250), 2 for USD (1250 = $12.50). */
  currency_exponent: number;
  auto_confirm: boolean;
  default_account_id: number | null;
}

export type AccountKind = 'cash' | 'bank' | 'credit_card' | 'e_money' | 'savings' | 'other';

export interface Account {
  id: number;
  name: string;
  kind: AccountKind;
  initial_balance: number;
  /** Initial balance plus confirmed records. */
  balance: number;
  color: string;
  archived: boolean;
}

export type CategoryKind = 'expense' | 'income';

export interface Category {
  id: number;
  name: string;
  kind: CategoryKind;
  icon: string;
  color: string;
}

export type TransactionKind = 'expense' | 'income' | 'transfer';

export interface Transaction {
  id: number;
  kind: TransactionKind;
  amount: number;
  account_id: number;
  to_account_id: number | null;
  category_id: number | null;
  /** ISO 8601 timestamp. */
  occurred_at: string;
  payee: string;
  note: string;
  /** 'review': created from an email or a notification, waiting for the user. */
  status: 'confirmed' | 'review';
  source: 'manual' | 'email' | 'import' | 'notification';
  /** What paid, as the email or notification named it (e.g. a card). */
  source_label: string;
  duplicate_of_id: number | null;
  created_at: string;
}

export type TransactionInput = Pick<
  Transaction,
  'kind' | 'amount' | 'account_id' | 'to_account_id' | 'category_id' | 'occurred_at' | 'payee' | 'note'
> & {
  status?: Transaction['status'];
  /** false: confirm without changing the category learned for the shop. Left out, it is learned. */
  remember?: boolean;
};

export interface Overview {
  settings: Settings;
  accounts: Account[];
  categories: Category[];
  review_count: number;
}

export interface Budget {
  id: number;
  name: string;
  /** null: all expenses. */
  category_id: number | null;
  /** Monthly limit. */
  amount: number;
}

export interface TransactionFilter {
  from?: Date;
  to?: Date;
  account_id?: number;
  /** A category ID, or 'none' for uncategorised records. */
  category_id?: number | 'none';
  q?: string;
  status?: Transaction['status'];
}

/** One change to many records at once. */
export type BulkChange = { ids: number[] } & (
  | { action: 'delete' }
  /** category_id null clears it; only records of the category's kind change, never transfers. */
  | { action: 'categorize'; category_id: number | null }
  /** For a transfer, the account it comes from. */
  | { action: 'move'; account_id: number }
  /** Records to review that are ready: a category, an amount, not a possible duplicate. Teaches new shops and cards only. */
  | { action: 'confirm' }
);

/** What confirmed payments taught the app, so the user can check it. */
export interface Rules {
  /** Shops with the most records first. */
  payees: PayeeRule[];
  cards: CardRule[];
}

export interface PayeeRule {
  /** The shop's name as matched: lower case, half-width. */
  key: string;
  /** The name as the shop's records spell it. */
  payee: string;
  category_id: number;
  records: number;
}

export interface CardRule {
  /** The card as emails and notifications name it. */
  source: string;
  account_id: number;
  records: number;
}

/** How many of the chosen records changed; the rest didn't fit the change. */
export interface BulkResult {
  changed: number;
  skipped: number;
}

/** Confirmed income and expenses over a period; transfers count as neither. */
export interface Stats {
  spent: number;
  income: number;
  /** Totals per category and kind, largest first. */
  categories: { category_id: number | null; kind: 'expense' | 'income'; amount: number; count: number }[];
  /** Totals per day (YYYY-MM-DD, in the time zone asked for), oldest first. Days without records are left out. */
  days: { day: string; spent: number; income: number }[];
}

export interface InboundEmail {
  id: number;
  from: string;
  subject: string;
  received_at: string;
  status: 'payment' | 'ignored' | 'unparsed' | 'forwarding';
  parser: string;
  detail: string;
  body_text: string;
  confirm_url: string;
  transaction_id: number | null;
  dismissed: boolean;
  duplicate?: boolean;
}

/** A payment app's notification the Android app sent, and what became of it. */
export interface PaymentNotification {
  id: number;
  package: string;
  /** The app's name, e.g. 'Google Wallet'. */
  app: string;
  title: string;
  text: string;
  posted_at: string;
  /** 'matched': the payment was already recorded (e.g. from its email), so nothing was added. */
  status: 'payment' | 'matched' | 'ignored' | 'unparsed';
  detail: string;
  transaction_id: number | null;
}

export interface Inbox {
  /** Empty when the server has no inbound mail domain configured. */
  address: string;
  ai_enabled: boolean;
  emails: InboundEmail[];
}

export interface ImportResult {
  imported: number;
  duplicates: number;
  /** Rows already recorded another way, e.g. from their email (PayPay only). */
  matched: number;
  accounts_created: number;
  categories_created: number;
  problems: string[];
}

/** One way of paying in a PayPay export, and the account suggested for it (null: a new one). */
export interface PayPayMethod {
  method: string;
  count: number;
  account_id: number | null;
  new_name: string;
  new_kind: AccountKind;
}

export interface PayPayPreview {
  records: number;
  from: string | null;
  to: string | null;
  methods: PayPayMethod[];
  problems: string[];
}

/** The user's Claude features. The key itself is never sent back. */
export interface AIStatus {
  /** False when the server has no SECRETS_KEY to encrypt keys with. */
  can_save: boolean;
  has_key: boolean;
  /** The saved key can't be decrypted any more (the server's secret changed): add it again. */
  key_unreadable: boolean;
  /** The key's last four characters. */
  key_hint: string;
  /** Confirmed records without a category, which Claude can sort. */
  uncategorized: number;
}

/** A payee's uncategorised records, and the category Claude suggests (null: none fits). */
export interface CategorySuggestion {
  payee: string;
  kind: CategoryKind;
  count: number;
  total: number;
  note: string;
  category_id: number | null;
}

export interface CategorySuggestions {
  suggestions: CategorySuggestion[];
  /** Payees after this batch. */
  remaining: number;
}

/** Files a payee's uncategorised records under a category. */
export interface PayeeCategory {
  payee: string;
  kind: CategoryKind;
  category_id: number;
}

/** An access token for an MCP client such as Claude Desktop. */
export interface MCPToken {
  id: number;
  name: string;
  created_at: string;
  last_used_at: string | null;
  /** The token itself, only in the answer that created it. */
  token?: string;
  /** True for a connection made by signing in from the app, false for a token made here. */
  signed_in?: boolean;
}

/** An MCP client asking to connect, for the Connect page. */
export interface MCPClientInfo {
  client_name: string;
  /** Where the browser goes back to after the user answers. */
  redirect_host: string;
}

/** An error response from the API; message is safe to show the user. */
export class ApiError extends Error {
  constructor(
    message: string,
    readonly status: number,
  ) {
    super(message);
  }
}

/**
 * The last answer to each GET, so a page seen before shows at once while it
 * reloads (see api.seen). Any change (POST, PUT, DELETE, signing in or out
 * included) empties it, so it never shows what the change made out of date,
 * or another person's data.
 */
const lastSeen = new Map<string, unknown>();

async function request<T>(method: string, path: string, body?: unknown, contentType?: string): Promise<T> {
  const changes = method !== 'GET';
  if (changes) lastSeen.clear();
  try {
    const raw = contentType !== undefined;
    const res = await fetch(server + path, {
      method,
      headers: { ...(body === undefined ? {} : { 'Content-Type': contentType ?? 'application/json' }), ...appHeaders() },
      body: body === undefined ? undefined : raw ? (body as BodyInit) : JSON.stringify(body),
    });
    if (res.status === 204) return undefined as T;
    const data = await res.json().catch(() => null);
    if (!res.ok) throw new ApiError(data?.error ?? res.statusText, res.status);
    if (!changes) lastSeen.set(path, data);
    return data as T;
  } finally {
    // A GET that was under way during the change may have stored an old answer.
    if (changes) lastSeen.clear();
  }
}

function query(f: TransactionFilter): string {
  const p = new URLSearchParams();
  if (f.from) p.set('from', f.from.toISOString());
  if (f.to) p.set('to', f.to.toISOString());
  if (f.account_id) p.set('account_id', String(f.account_id));
  if (f.category_id) p.set('category_id', String(f.category_id));
  if (f.q) p.set('q', f.q);
  if (f.status) p.set('status', f.status);
  const s = p.toString();
  return s ? `?${s}` : '';
}

/** In the Android app, keeps the token a sign-in returned. */
async function signedIn(me: Promise<Me>): Promise<Me> {
  const m = await me;
  if (m.token) await saveSession(m.token);
  return m;
}

export const api = {
  me: () => request<Me>('GET', '/api/me'),
  signUp: (email: string, password: string) => signedIn(request<Me>('POST', '/api/signup', { email, password })),
  signIn: (email: string, password: string) => signedIn(request<Me>('POST', '/api/login', { email, password })),
  /** Finishes Google sign-in in the Android app (see native.ts). */
  appToken: (code: string, verifier: string) => signedIn(request<Me>('POST', '/api/auth/app-token', { code, verifier })),
  signOut: async () => {
    await request<void>('POST', '/api/logout');
    await saveSession('');
  },
  authOptions: () => request<AuthOptions>('GET', '/api/auth/options'),
  resendConfirmation: () => request<void>('POST', '/api/verify-email'),
  /** Deletes the account and everything in it. Confirm with the password, or for a Google-only account, the email. */
  deleteMe: async (confirm: { password: string } | { email: string }) => {
    await request<void>('DELETE', '/api/me', confirm);
    await saveSession('');
  },

  overview: () => request<Overview>('GET', '/api/wallet'),
  updateSettings: ({ currency, auto_confirm, default_account_id }: Omit<Settings, 'currency_exponent'>) =>
    request<Settings>('PUT', '/api/settings', { currency, auto_confirm, default_account_id }),

  createAccount: (a: Omit<Account, 'id' | 'balance'>) => request<Account>('POST', '/api/accounts', a),
  updateAccount: (id: number, a: Omit<Account, 'id' | 'balance'>) => request<Account>('PUT', `/api/accounts/${id}`, a),
  deleteAccount: (id: number) => request<void>('DELETE', `/api/accounts/${id}`),

  createCategory: (c: Omit<Category, 'id'>) => request<Category>('POST', '/api/categories', c),
  updateCategory: (id: number, c: Omit<Category, 'id'>) => request<Category>('PUT', `/api/categories/${id}`, c),
  deleteCategory: (id: number) => request<void>('DELETE', `/api/categories/${id}`),

  transactions: (f: TransactionFilter = {}) => request<Transaction[]>('GET', `/api/transactions${query(f)}`),
  createTransaction: (t: TransactionInput) => request<Transaction>('POST', '/api/transactions', t),
  updateTransaction: (id: number, t: TransactionInput) => request<Transaction>('PUT', `/api/transactions/${id}`, t),
  deleteTransaction: (id: number) => request<void>('DELETE', `/api/transactions/${id}`),
  bulkTransactions: (change: BulkChange) => request<BulkResult>('POST', '/api/transactions/bulk', change),

  stats: (from: Date, to: Date, accountId?: number) => {
    const p = new URLSearchParams({ from: from.toISOString(), to: to.toISOString(), tz: Intl.DateTimeFormat().resolvedOptions().timeZone });
    if (accountId) p.set('account_id', String(accountId));
    return request<Stats>('GET', `/api/stats?${p}`);
  },

  budgets: () => request<Budget[]>('GET', '/api/budgets'),
  createBudget: (b: Omit<Budget, 'id'>) => request<Budget>('POST', '/api/budgets', b),
  updateBudget: (id: number, b: Omit<Budget, 'id'>) => request<Budget>('PUT', `/api/budgets/${id}`, b),
  deleteBudget: (id: number) => request<void>('DELETE', `/api/budgets/${id}`),

  inbox: () => request<Inbox>('GET', '/api/inbox'),
  newAddress: () => request<Inbox>('POST', '/api/inbox/address'),
  uploadEmail: (raw: string) => request<InboundEmail>('POST', '/api/inbox/emails', raw, 'message/rfc822'),
  dismissEmail: (id: number) => request<void>('POST', `/api/inbox/emails/${id}/dismiss`),

  notifications: () => request<{ notifications: PaymentNotification[] }>('GET', '/api/notifications'),

  rules: () => request<Rules>('GET', '/api/rules'),
  setPayeeRule: (payee: string, category_id: number) => request<PayeeRule>('PUT', '/api/rules/payees', { payee, category_id }),
  deletePayeeRule: (payee: string) => request<void>('DELETE', `/api/rules/payees?payee=${encodeURIComponent(payee)}`),
  setCardRule: (source: string, account_id: number) => request<void>('PUT', '/api/rules/cards', { source, account_id }),
  deleteCardRule: (source: string) => request<void>('DELETE', `/api/rules/cards?source=${encodeURIComponent(source)}`),

  /** What the same call last returned, if nothing has changed since; show it while the call reloads. */
  seen: {
    transactions: (f: TransactionFilter = {}) => lastSeen.get(`/api/transactions${query(f)}`) as Transaction[] | undefined,
    budgets: () => lastSeen.get('/api/budgets') as Budget[] | undefined,
    inbox: () => lastSeen.get('/api/inbox') as Inbox | undefined,
  },

  ai: () => request<AIStatus>('GET', '/api/ai'),
  /** Saves the user's Anthropic API key, after Claude accepts it. */
  setAIKey: (key: string) => request<AIStatus>('PUT', '/api/ai/key', { key }),
  deleteAIKey: () => request<void>('DELETE', '/api/ai/key'),
  /** Claude's suggestions for the next batch of uncategorised payees, after skipping offset of them. */
  suggestCategories: (offset = 0) => request<CategorySuggestions>('POST', `/api/ai/categorize?offset=${offset}`),
  applyCategories: (items: PayeeCategory[]) => request<{ updated: number }>('POST', '/api/ai/categorize/apply', { items }),

  mcpTokens: () => request<MCPToken[]>('GET', '/api/mcp/tokens'),
  createMCPToken: (name: string) => request<MCPToken>('POST', '/api/mcp/tokens', { name }),
  deleteMCPToken: (id: number) => request<void>('DELETE', `/api/mcp/tokens/${id}`),
  mcpClient: (clientId: string, redirectUri: string) =>
    request<MCPClientInfo>('GET', `/api/mcp/authorize?${new URLSearchParams({ client_id: clientId, redirect_uri: redirectUri })}`),
  /** Allows or denies a client; the answer says where to send the browser. */
  mcpAuthorize: (r: { client_id: string; redirect_uri: string; code_challenge: string; state: string; allow: boolean }) =>
    request<{ redirect_to: string }>('POST', '/api/mcp/authorize', r),

  importBudgetBakers: (csv: string) =>
    request<ImportResult>('POST', `/api/import/budgetbakers?tz_offset=${new Date().getTimezoneOffset()}`, csv, 'text/csv'),
  previewPayPay: (csv: string) => request<PayPayPreview>('POST', '/api/import/paypay?preview=1', csv, 'text/csv'),
  /** accounts maps each payment method to an account ID, or 0 for a new account. */
  importPayPay: (csv: string, accounts: Record<string, number>) => {
    const p = new URLSearchParams();
    for (const [method, id] of Object.entries(accounts)) p.append('account', `${id}:${method}`);
    return request<ImportResult>('POST', `/api/import/paypay?${p}`, csv, 'text/csv');
  },
};

/** A message to show for any thrown value. */
export function errorMessage(err: unknown): string {
  return err instanceof Error ? err.message : String(err);
}
