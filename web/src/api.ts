// Typed client for the Go API. Shapes mirror the Go handlers' JSON.
// Amounts are whole minor units of the user's currency (see Settings).
import { serverText, t as tr } from './i18n';
import { language } from './language';
import { appHeaders, saveSession, server } from './native';
import { asRecord, newClientID, outbox } from './outbox';

export interface Me {
  email: string;
  /** False for accounts that only sign in with Google. */
  has_password: boolean;
  /** The server emailed a link to confirm the address, and it hasn't been opened yet. */
  confirm_email: boolean;
  /** A throwaway demo account full of sample data, deleted within a day. Absent from older servers. */
  demo?: boolean;
  /** A new session's token, given to the Android app only (see native.ts). */
  token?: string;
}

/** The ways this server offers to sign in. */
export interface AuthOptions {
  google: boolean;
  /** Visitors can try a demo without an account. Absent from older servers. */
  demo?: boolean;
  /** A forgotten password can be reset by email. Absent from older servers. */
  reset?: boolean;
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
  /** The user's own labels, in the order they were added. */
  tags: string[];
  /** Only on this phone so far, waiting to be sent (outbox.ts); error says why the server refused it. Never from the server. */
  unsent?: { error?: string };
}

export type TransactionInput = Pick<
  Transaction,
  'kind' | 'amount' | 'account_id' | 'to_account_id' | 'category_id' | 'occurred_at' | 'payee' | 'note'
> & {
  status?: Transaction['status'];
  /** false: confirm without changing the category learned for the shop. Left out, it is learned. */
  remember?: boolean;
  /** Left out, an update keeps the record's tags. */
  tags?: string[];
};

/** A tag in use, and on how many records. */
export interface Tag {
  name: string;
  count: number;
}

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
  /** Records with this tag, ignoring case. */
  tag?: string;
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
  /** Adds a tag to records without it, or takes it off. */
  | { action: 'tag' | 'untag'; tag: string }
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

export interface RestoreResult {
  restored: number;
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

/** A payment app's notification the Android app sent, and what became of it. */
export interface PaymentNotification {
  id: number;
  package: string;
  /** The app's name, e.g. 'Google Wallet'. */
  app: string;
  title: string;
  text: string;
  posted_at: string;
  /** 'matched': the payment was already recorded (e.g. by hand), so nothing was added. */
  status: 'payment' | 'matched' | 'ignored' | 'unparsed';
  detail: string;
  transaction_id: number | null;
}

export interface ImportResult {
  imported: number;
  duplicates: number;
  /** Rows already recorded another way, e.g. from their notification (PayPay only). */
  matched: number;
  /** Rows paid in a way the user chose not to import (PayPay only). */
  skipped: number;
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
  /** The user chose last time not to import this way of paying. */
  skip: boolean;
}

/** As the account for a way of paying in an import: leave its records out. */
export const skipAccount = -1;

export interface PayPayPreview {
  records: number;
  from: string | null;
  to: string | null;
  methods: PayPayMethod[];
  /** The payments by shop, each with a suggested category. */
  categories: ImportCategory[];
  /** Default categories the user doesn't have, which can be created on import. */
  new_categories: Category[];
  problems: string[];
}

/** A role in an MUFG statement: the bank itself, or what money moves to and from. */
export type MUFGRole = 'bank' | 'cash' | 'paypay-card' | 'smbc-card' | 'paypay';

/** One role in an MUFG statement, and the account suggested for it (null: a new one). */
export interface MUFGAccount {
  role: MUFGRole;
  count: number;
  account_id: number | null;
  new_name: string;
  new_kind: AccountKind;
  /** Other accounts that also look like this one, e.g. two bank accounts named after MUFG. */
  candidates: number[];
}

/** How one statement row would be (or was) recorded. */
export interface MUFGRow {
  line: number;
  date: string;
  summary: string;
  detail: string;
  out: number;
  in: number;
  balance: number;
  kind: 'expense' | 'income' | 'transfer';
  from_role: MUFGRole;
  to_role?: MUFGRole;
  group?: string;
  /** False when no rule knew the row: it's recorded without a category unless one is chosen. */
  rule: boolean;
  /** new: to record; imported: by an earlier import; matched: already recorded another way. */
  status: 'new' | 'imported' | 'matched';
  matched_id?: number;
  matched_account_id?: number;
}

/** The bank account's balance in the app, after the import, against the statement's. */
export interface MUFGBalance {
  account_id: number | null;
  from: string;
  to: string;
  opening: number;
  closing: number;
  app: number;
  /** closing - app. */
  difference: number;
  /** What rows already recorded in another account add up to; moving them here changes difference by -misplaced. */
  misplaced: number;
  initial_balance: number;
  /** The starting balance that leaves only misplaced apart. */
  suggested_initial_balance: number;
  /** How much the import changed the starting balance by (match_balance). */
  adjusted: number;
}

export interface MUFGPreview {
  records: number;
  new: number;
  accounts: MUFGAccount[];
  categories: ImportCategory[];
  new_categories: Category[];
  rows: MUFGRow[];
  balance: MUFGBalance;
}

export interface MUFGResult extends ImportResult {
  rows: MUFGRow[];
  balance: MUFGBalance;
}

/** What the phone read from a transit IC card: its IDm, and its history blocks in hex, newest first. */
export interface ICCardRead {
  idm: string;
  history: string[];
}

/** A role in a card's history: the card itself, or where money put on it comes from. */
export type ICRole = 'card' | 'top-up';

/** One role in a card's history, and the account suggested for it (null: a new one). */
export interface ICAccount {
  role: ICRole;
  count: number;
  account_id: number | null;
  new_name: string;
  new_kind: AccountKind;
}

/** How one entry on the card would be (or was) recorded. */
export interface ICRow {
  date: string;
  /** A shop payment's time, HH:MM. */
  time?: string;
  seq: number;
  balance: number;
  /** The balance's change: negative for a payment. */
  amount: number;
  kind: 'expense' | 'income' | 'transfer';
  group?: string;
  /** start: the oldest entry read, whose amount isn't known; none: no money moved. */
  status: 'new' | 'imported' | 'matched' | 'start' | 'none';
  matched_id?: number;
}

/** The card's account in the app, after the import, against the card's balance. */
export interface ICBalance {
  account_id: number | null;
  card: number;
  app: number;
  /** card - app. */
  difference: number;
  initial_balance: number;
  suggested_initial_balance: number;
  /** How much the import changed the starting balance by (match_balance). */
  adjusted: number;
}

export interface ICPreview {
  /** The last four hex digits of the card's IDm. */
  card: string;
  from: string;
  to: string;
  records: number;
  new: number;
  accounts: ICAccount[];
  categories: ImportCategory[];
  new_categories: Category[];
  rows: ICRow[];
  balance: ICBalance;
  /** The card was read before, but none of its entries now were: those in between are lost. */
  gap: boolean;
}

export interface ICResult extends ImportResult {
  rows: ICRow[];
  balance: ICBalance;
}

/**
 * Records in an import file that share a category (BudgetBakers) or a shop
 * (PayPay), and where they're suggested to go: category_id, else a new
 * category named new_name, else ('') none.
 */
export interface ImportCategory {
  name: string;
  kind: 'expense' | 'income';
  count: number;
  total: number;
  category_id: number | null;
  new_name: string;
  /** The suggestion is what the user chose for this shop before. */
  learned: boolean;
}

/** Where a group's records go: a category, a new one by name, or neither for none. */
export interface CategoryChoice {
  name: string;
  kind: 'expense' | 'income';
  category_id: number;
  new_name: string;
}

/** One account in a BudgetBakers export, and the account suggested for it (null: a new one). */
export interface BudgetBakersAccount {
  name: string;
  count: number;
  account_id: number | null;
  new_kind: AccountKind;
}

export interface BudgetBakersPreview {
  records: number;
  from: string | null;
  to: string | null;
  accounts: BudgetBakersAccount[];
  categories: ImportCategory[];
  new_categories: Category[];
  problems: string[];
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

const seenKey = 'wallet:seen';
const meKey = 'wallet:me';

/**
 * The last answer to each GET, so a page seen before shows at once while it
 * reloads (see api.seen). Any change (POST, PUT, DELETE, signing in or out
 * included) empties it, so it never shows what the change made out of date,
 * or another person's data.
 */
const lastSeen = new Map<string, unknown>(Object.entries(keptAnswers()));

/**
 * The answers Home opens on are also kept on this device, until signing out
 * (see savedMe): the app starts on them while it asks the server again, and
 * shows them when the server can't be reached.
 */
function kept(path: string): boolean {
  return path === '/api/wallet' || path === '/api/budgets' || path.startsWith('/api/transactions?from=');
}

/** Months of records kept at most, the latest seen. */
const keptMonths = 12;

function keptAnswers(): Record<string, unknown> {
  try {
    if (!localStorage.getItem(meKey)) return {};
    return JSON.parse(localStorage.getItem(seenKey) ?? '{}');
  } catch {
    return {}; // storage blocked, or not ours to read
  }
}

function keep(path: string, data: unknown) {
  try {
    const answers = keptAnswers();
    delete answers[path]; // to the end, as the latest seen
    answers[path] = data;
    const months = Object.keys(answers).filter((p) => p.startsWith('/api/transactions'));
    for (const old of months.slice(0, -keptMonths)) delete answers[old];
    localStorage.setItem(seenKey, JSON.stringify(answers));
  } catch {
    // storage full or blocked: the app just waits for the network
  }
}

/**
 * Who was signed in when the app last ran here, so it can open on their
 * wallet at once and check with the server meanwhile. set(null) on signing
 * out forgets their kept answers too.
 */
export const savedMe = {
  get(): Me | null {
    try {
      return JSON.parse(localStorage.getItem(meKey) ?? 'null');
    } catch {
      return null;
    }
  },
  set(me: Me | null) {
    try {
      if (me) {
        const { token: _, ...rest } = me; // the session token lives in native.ts, never here
        localStorage.setItem(meKey, JSON.stringify(rest));
      } else {
        lastSeen.clear();
        localStorage.removeItem(meKey);
        localStorage.removeItem(seenKey);
      }
    } catch {
      // storage blocked: every launch waits for the server, as before
    }
  },
};

/** The request never reached the server, or its answer never came back. */
export function unreachable(err: unknown): boolean {
  return err instanceof TypeError || (err instanceof DOMException && (err.name === 'TimeoutError' || err.name === 'AbortError'));
}

/**
 * GETs under way, so asking again for what's already coming (say, a page
 * opening on what was fetched ahead for it) waits for that answer.
 */
const underWay = new Map<string, Promise<unknown>>();

function request<T>(method: string, path: string, body?: unknown, contentType?: string, signal?: AbortSignal): Promise<T> {
  if (method !== 'GET' || signal) return send<T>(method, path, body, contentType, signal);
  let p = underWay.get(path) as Promise<T> | undefined;
  if (!p) {
    p = send<T>(method, path);
    const asked = p;
    underWay.set(path, asked);
    const done = () => underWay.get(path) === asked && underWay.delete(path);
    asked.then(done, done);
  }
  return p;
}

/** Asks for path ahead of time, unless its answer was seen since the last change; nothing shows a failure. */
function prefetch(path: string) {
  if (!lastSeen.has(path)) request('GET', path).catch(() => {});
}

async function send<T>(method: string, path: string, body?: unknown, contentType?: string, signal?: AbortSignal): Promise<T> {
  const changes = method !== 'GET';
  if (changes) {
    lastSeen.clear();
    underWay.clear(); // a GET sent before the change may answer from before it
  }
  try {
    const raw = contentType !== undefined;
    const res = await fetch(server + path, {
      method,
      headers: {
        ...(body === undefined ? {} : { 'Content-Type': contentType ?? 'application/json' }),
        // The language the app shows, which a new account's categories are named in.
        'Accept-Language': language(),
        ...appHeaders(),
      },
      body: body === undefined ? undefined : raw ? (body as BodyInit) : JSON.stringify(body),
      signal,
    });
    if (res.status === 204) return undefined as T;
    const data = await res.json().catch(() => null);
    if (!res.ok) throw new ApiError(data?.error ?? res.statusText, res.status);
    if (!changes) {
      lastSeen.set(path, data);
      if (kept(path)) keep(path, data);
    }
    return data as T;
  } catch (err) {
    // Offline: what the app saw last time, if it kept it.
    const answer = !changes && unreachable(err) && kept(path) ? keptAnswers()[path] : undefined;
    if (answer !== undefined) return answer as T;
    throw err;
  } finally {
    // A GET that was under way during the change may have stored an old answer.
    if (changes) {
      lastSeen.clear();
      underWay.clear();
    }
  }
}

function statsPath(from: Date, to: Date, accountId?: number): string {
  const p = new URLSearchParams({ from: from.toISOString(), to: to.toISOString(), tz: Intl.DateTimeFormat().resolvedOptions().timeZone });
  if (accountId) p.set('account_id', String(accountId));
  return `/api/stats?${p}`;
}

/** Who is signed in, for the outbox. */
const owner = () => savedMe.get()?.email ?? '';

let sending: Promise<number> | null = null;

/** Sends the records added while offline, in order; resolves to how many went. */
function sendUnsent(): Promise<number> {
  sending ??= sendEach().finally(() => (sending = null));
  return sending;
}

async function sendEach(): Promise<number> {
  const sent: string[] = [];
  try {
    for (const u of outbox.all().filter((u) => u.email === owner() && !u.error)) {
      let saved: Transaction;
      try {
        saved = await request<Transaction>('POST', '/api/transactions', { ...u.input, client_id: u.client_id }, undefined, AbortSignal.timeout(15_000));
      } catch (err) {
        // Refused (e.g. its account is gone): it waits for the user. Anything else: try later.
        if (err instanceof ApiError && err.status >= 400 && err.status < 500 && err.status !== 401) outbox.refused(u.client_id, errorMessage(err));
        if (unreachable(err) || (err instanceof ApiError && (err.status === 401 || err.status >= 500))) break;
        continue;
      }
      // Edited or deleted on the phone while it was on its way.
      const now = outbox.all().find((x) => x.client_id === u.client_id);
      if (!now) await request('DELETE', `/api/transactions/${saved.id}`).catch(() => {});
      else if (now.input !== u.input) await request('PUT', `/api/transactions/${saved.id}`, now.input).catch(() => {});
      sent.push(u.client_id);
    }
  } finally {
    if (sent.length) await outbox.sent(sent);
  }
  return sent.length;
}

function query(f: TransactionFilter): string {
  const p = new URLSearchParams();
  if (f.from) p.set('from', f.from.toISOString());
  if (f.to) p.set('to', f.to.toISOString());
  if (f.account_id) p.set('account_id', String(f.account_id));
  if (f.category_id) p.set('category_id', String(f.category_id));
  if (f.q) p.set('q', f.q);
  if (f.tag) p.set('tag', f.tag);
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
    const email = owner();
    await sendUnsent().catch(() => 0);
    await request<void>('POST', '/api/logout');
    outbox.forget(email);
    await saveSession('');
  },
  authOptions: () => request<AuthOptions>('GET', '/api/auth/options'),
  /** Signs in to a new demo account with sample data, named in the app's language and dated in this time zone. */
  startDemo: () => signedIn(request<Me>('POST', '/api/demo', { tz: Intl.DateTimeFormat().resolvedOptions().timeZone })),
  resendConfirmation: () => request<void>('POST', '/api/verify-email'),
  /** Emails a link to reset the password, if an account uses this address. Answers the same either way. */
  forgotPassword: (email: string) => request<void>('POST', '/api/password/forgot', { email }),
  /** Sets a new password with the token from the emailed link, and signs in. */
  resetPassword: (token: string, password: string) => signedIn(request<Me>('POST', '/api/password/reset', { token, password })),
  /** Deletes the account and everything in it. Confirm with the password, or for a Google-only account, the email. */
  deleteMe: async (confirm: { password: string } | { email: string }) => {
    const email = owner();
    await request<void>('DELETE', '/api/me', confirm);
    outbox.forget(email);
    await saveSession('');
  },

  overview: () => request<Overview>('GET', '/api/wallet'),
  updateSettings: ({ currency, auto_confirm, default_account_id }: Omit<Settings, 'currency_exponent'>) =>
    request<Settings>('PUT', '/api/settings', { currency, auto_confirm, default_account_id }),

  createAccount: (a: Omit<Account, 'id' | 'balance'>) => request<Account>('POST', '/api/accounts', a),
  updateAccount: (id: number, a: Omit<Account, 'id' | 'balance'>) => request<Account>('PUT', `/api/accounts/${id}`, a),
  deleteAccount: (id: number) => request<void>('DELETE', `/api/accounts/${id}`),

  /** Default categories the user doesn't have yet. */
  missingCategories: () => request<Category[]>('GET', '/api/categories/missing'),
  createCategory: (c: Omit<Category, 'id'>) => request<Category>('POST', '/api/categories', c),
  updateCategory: (id: number, c: Omit<Category, 'id'>) => request<Category>('PUT', `/api/categories/${id}`, c),
  deleteCategory: (id: number) => request<void>('DELETE', `/api/categories/${id}`),

  transactions: (f: TransactionFilter = {}) => request<Transaction[]>('GET', `/api/transactions${query(f)}`),
  /** Without a connection, the record is kept on this phone and sent later: the answer has unsent set. */
  createTransaction: async (t: TransactionInput): Promise<Transaction> => {
    const clientID = newClientID();
    try {
      if (!navigator.onLine) throw new TypeError('offline');
      const saved = await request<Transaction>('POST', '/api/transactions', { ...t, client_id: clientID }, undefined, AbortSignal.timeout(10_000));
      void sendUnsent(); // the server answers again: anything waiting goes too
      return saved;
    } catch (err) {
      if (!unreachable(err)) throw err;
      return asRecord(outbox.add(owner(), clientID, t));
    }
  },
  /** A record not sent yet (unsent, id below zero) changes on the phone, and is sent as changed. */
  updateTransaction: async (id: number, t: TransactionInput): Promise<Transaction> => {
    if (id >= 0) return request<Transaction>('PUT', `/api/transactions/${id}`, t);
    const u = outbox.update(id, t);
    if (!u) throw new ApiError(tr('This record was just sent. Open it again to change it.'), 404);
    void sendUnsent();
    return asRecord(u);
  },
  deleteTransaction: async (id: number): Promise<void> => {
    if (id >= 0) return request<void>('DELETE', `/api/transactions/${id}`);
    const u = outbox.get(id);
    if (u) outbox.remove([u.client_id]);
  },
  sendUnsent,
  bulkTransactions: (change: BulkChange) => request<BulkResult>('POST', '/api/transactions/bulk', change),
  /** The tags on the user's records, the most used first. */
  tags: () => request<Tag[]>('GET', '/api/tags'),
  /** Brings back records deleted in the last day. */
  restoreTransactions: (ids: number[]) => request<RestoreResult>('POST', '/api/transactions/restore', { ids }),

  stats: (from: Date, to: Date, accountId?: number) => request<Stats>('GET', statsPath(from, to, accountId)),

  budgets: () => request<Budget[]>('GET', '/api/budgets'),
  createBudget: (b: Omit<Budget, 'id'>) => request<Budget>('POST', '/api/budgets', b),
  updateBudget: (id: number, b: Omit<Budget, 'id'>) => request<Budget>('PUT', `/api/budgets/${id}`, b),
  deleteBudget: (id: number) => request<void>('DELETE', `/api/budgets/${id}`),

  notifications: () => request<{ notifications: PaymentNotification[] }>('GET', '/api/notifications'),

  rules: () => request<Rules>('GET', '/api/rules'),
  setPayeeRule: (payee: string, category_id: number) => request<PayeeRule>('PUT', '/api/rules/payees', { payee, category_id }),
  deletePayeeRule: (payee: string) => request<void>('DELETE', `/api/rules/payees?payee=${encodeURIComponent(payee)}`),
  setCardRule: (source: string, account_id: number) => request<void>('PUT', '/api/rules/cards', { source, account_id }),
  deleteCardRule: (source: string) => request<void>('DELETE', `/api/rules/cards?source=${encodeURIComponent(source)}`),

  /** What the same call last returned, if nothing has changed since; show it while the call reloads. */
  seen: {
    overview: () => lastSeen.get('/api/wallet') as Overview | undefined,
    transactions: (f: TransactionFilter = {}) => lastSeen.get(`/api/transactions${query(f)}`) as Transaction[] | undefined,
    budgets: () => lastSeen.get('/api/budgets') as Budget[] | undefined,
    tags: () => lastSeen.get('/api/tags') as Tag[] | undefined,
    stats: (from: Date, to: Date, accountId?: number) => lastSeen.get(statsPath(from, to, accountId)) as Stats | undefined,
    rules: () => lastSeen.get('/api/rules') as Rules | undefined,
    notifications: () => lastSeen.get('/api/notifications') as { notifications: PaymentNotification[] } | undefined,
    mcpTokens: () => lastSeen.get('/api/mcp/tokens') as MCPToken[] | undefined,
  },

  /** Loads what a page will ask for before it's opened, so it opens at once (see seen). */
  prefetch: {
    transactions: (f: TransactionFilter = {}) => prefetch(`/api/transactions${query(f)}`),
    stats: (from: Date, to: Date, accountId?: number) => prefetch(statsPath(from, to, accountId)),
    budgets: () => prefetch('/api/budgets'),
    tags: () => prefetch('/api/tags'),
  },

  mcpTokens: () => request<MCPToken[]>('GET', '/api/mcp/tokens'),
  createMCPToken: (name: string) => request<MCPToken>('POST', '/api/mcp/tokens', { name }),
  deleteMCPToken: (id: number) => request<void>('DELETE', `/api/mcp/tokens/${id}`),
  mcpClient: (clientId: string, redirectUri: string) =>
    request<MCPClientInfo>('GET', `/api/mcp/authorize?${new URLSearchParams({ client_id: clientId, redirect_uri: redirectUri })}`),
  /** Allows or denies a client; the answer says where to send the browser. */
  mcpAuthorize: (r: { client_id: string; redirect_uri: string; code_challenge: string; state: string; allow: boolean }) =>
    request<{ redirect_to: string }>('POST', '/api/mcp/authorize', r),

  previewBudgetBakers: (csv: string) =>
    request<BudgetBakersPreview>('POST', `/api/import/budgetbakers?preview=1&tz_offset=${new Date().getTimezoneOffset()}`, csv, 'text/csv'),
  /** accounts maps each account in the file to an account ID, or 0 for a new one. */
  importBudgetBakers: (csv: string, accounts: Record<string, number>, categories: CategoryChoice[]) =>
    request<ImportResult>('POST', `/api/import/budgetbakers?tz_offset=${new Date().getTimezoneOffset()}`, {
      csv,
      accounts: Object.entries(accounts).map(([name, account_id]) => ({ name, account_id })),
      categories,
    }),
  previewMUFG: (csv: string) => request<MUFGPreview>('POST', '/api/import/mufg?preview=1', csv, 'text/csv'),
  /** accounts maps each role to an account ID, or 0 for a new account. */
  /** match_balance: also set the bank account's starting balance so it matches the statement. */
  importMUFG: (csv: string, accounts: Record<string, number>, categories: CategoryChoice[], match_balance: boolean) =>
    request<MUFGResult>('POST', '/api/import/mufg', {
      csv,
      accounts: Object.entries(accounts).map(([name, account_id]) => ({ name, account_id })),
      categories,
      match_balance,
    }),
  previewICCard: (read: ICCardRead) => request<ICPreview>('POST', '/api/import/ic-card?preview=1', read),
  /** accounts maps each role to an account ID, or 0 for a new account. */
  importICCard: (read: ICCardRead, accounts: Record<string, number>, categories: CategoryChoice[], match_balance: boolean) =>
    request<ICResult>('POST', '/api/import/ic-card', {
      ...read,
      accounts: Object.entries(accounts).map(([name, account_id]) => ({ name, account_id })),
      categories,
      match_balance,
    }),
  /** Records the difference between the account's balance and balance (minor units); null if none. */
  reconcileAccount: (id: number, balance: number) =>
    request<{ transaction: Transaction | null }>('POST', `/api/accounts/${id}/reconcile`, { balance }),
  previewPayPay: (csv: string) => request<PayPayPreview>('POST', '/api/import/paypay?preview=1', csv, 'text/csv'),
  /** accounts maps each payment method to an account ID, 0 for a new account, or skipAccount. */
  importPayPay: (csv: string, accounts: Record<string, number>, categories: CategoryChoice[]) =>
    request<ImportResult>('POST', '/api/import/paypay', {
      csv,
      accounts: Object.entries(accounts).map(([name, account_id]) => ({ name, account_id })),
      categories,
    }),
};

/** A message to show for any thrown value, in the language in use when it's one the app knows. */
export function errorMessage(err: unknown): string {
  if (unreachable(err)) return tr('No connection. Try again when you’re online.');
  return serverText(err instanceof Error ? err.message : String(err));
}
