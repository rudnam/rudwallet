// New records the server couldn't be reached for (no signal, say): kept on
// this device, shown in the lists and balances as if saved, and sent when
// the server answers again (api.sendUnsent). Each is named with a client_id,
// so sending one again after an answer was lost finds the record already
// saved instead of adding it twice (internal/db/migrations/016_client_id.sql).
import { useMemo, useSyncExternalStore } from 'react';
import type { Transaction, TransactionFilter, TransactionInput } from './api';

export interface Unsent {
  client_id: string;
  /** Stands in for the record's id until the server gives it one: below zero. */
  local_id: number;
  /** Whose record it is: only they see it, and it's sent only while they're signed in. */
  email: string;
  input: TransactionInput;
  /** Why the server refused it (e.g. its account was deleted); it waits for the user to fix it. */
  error?: string;
}

const storageKey = 'wallet:outbox';
let entries: Unsent[] = read();
const listeners = new Set<() => void>();
const sentListeners = new Set<() => Promise<unknown>>();

function read(): Unsent[] {
  try {
    const list = JSON.parse(localStorage.getItem(storageKey) ?? '[]');
    return Array.isArray(list) ? list : [];
  } catch {
    return [];
  }
}

function write(next: Unsent[]) {
  entries = next;
  try {
    if (next.length) localStorage.setItem(storageKey, JSON.stringify(next));
    else localStorage.removeItem(storageKey);
  } catch {
    // storage full or blocked: kept until the app closes
  }
  for (const l of listeners) l();
}

/** A name for a new record that no other phone will pick. */
export function newClientID(): string {
  if (typeof crypto.randomUUID === 'function') return crypto.randomUUID();
  // Not a secure context (e.g. a preview opened by IP address).
  return Array.from(crypto.getRandomValues(new Uint8Array(16)), (b) => b.toString(16).padStart(2, '0')).join('');
}

const strip = ({ remember: _, ...input }: TransactionInput): TransactionInput => input;

export const outbox = {
  all: (): Unsent[] => entries,
  get: (localID: number) => entries.find((u) => u.local_id === localID),
  add(email: string, clientID: string, input: TransactionInput): Unsent {
    const lowest = Math.min(0, ...entries.map((u) => u.local_id));
    const u: Unsent = { client_id: clientID, local_id: lowest - 1, email, input: strip(input) };
    write([...entries, u]);
    return u;
  },
  /** An edit to a record not sent yet; it's sent again, as edited. */
  update(localID: number, input: TransactionInput): Unsent | undefined {
    write(entries.map((u) => (u.local_id === localID ? { ...u, input: strip(input), error: undefined } : u)));
    return outbox.get(localID);
  },
  refused(clientID: string, error: string) {
    write(entries.map((u) => (u.client_id === clientID ? { ...u, error } : u)));
  },
  remove(clientIDs: string[]) {
    if (entries.some((u) => clientIDs.includes(u.client_id))) write(entries.filter((u) => !clientIDs.includes(u.client_id)));
  },
  /** On signing out: nothing of theirs stays on the phone. */
  forget(email: string) {
    write(entries.filter((u) => u.email !== email));
  },
  subscribe(l: () => void): () => void {
    listeners.add(l);
    return () => {
      listeners.delete(l);
    };
  },
  /** f runs after records were sent, and they leave the outbox once it's done: reload what shows them, so they don't blink out meanwhile. */
  onSent(f: () => Promise<unknown>): () => void {
    sentListeners.add(f);
    return () => {
      sentListeners.delete(f);
    };
  },
  async sent(clientIDs: string[]) {
    await Promise.allSettled([...sentListeners].map((f) => f()));
    outbox.remove(clientIDs);
  },
};

/** The records email added that aren't sent yet; re-renders when that changes. */
export function useUnsent(email: string): Unsent[] {
  const all = useSyncExternalStore(outbox.subscribe, outbox.all);
  return useMemo(() => all.filter((u) => u.email === email), [all, email]);
}

/** An unsent record as the lists show it. */
export function asRecord(u: Unsent): Transaction {
  const i = u.input;
  return {
    id: u.local_id,
    kind: i.kind,
    amount: i.amount,
    account_id: i.account_id,
    to_account_id: i.to_account_id,
    category_id: i.category_id,
    occurred_at: i.occurred_at,
    payee: i.payee,
    note: i.note,
    status: 'confirmed',
    source: 'manual',
    source_label: '',
    duplicate_of_id: null,
    created_at: i.occurred_at,
    tags: i.tags ?? [],
    unsent: { error: u.error },
  };
}

/** Whether a record belongs in a list asked for with f, as the server would decide. */
export function matches(t: Transaction, f: TransactionFilter): boolean {
  const at = new Date(t.occurred_at);
  if (f.from && at < f.from) return false;
  if (f.to && at >= f.to) return false;
  if (f.account_id && t.account_id !== f.account_id && t.to_account_id !== f.account_id) return false;
  if (f.category_id === 'none' ? t.category_id !== null || t.kind === 'transfer' : f.category_id && t.category_id !== f.category_id) return false;
  if (f.status && t.status !== f.status) return false;
  const has = (s: string, q: string) => s.toLowerCase().includes(q.toLowerCase());
  const q = f.q?.trim();
  if (q && !has(t.payee, q) && !has(t.note, q) && !t.tags.some((g) => has(g, q))) return false;
  const tag = f.tag?.trim().toLowerCase();
  if (tag && !t.tags.some((g) => g.toLowerCase() === tag)) return false;
  return true;
}

/** What an unsent record does to an account's balance. */
export function balanceChange(u: Unsent, accountID: number): number {
  const { kind, amount, account_id, to_account_id } = u.input;
  if (kind === 'transfer') return (to_account_id === accountID ? amount : 0) - (account_id === accountID ? amount : 0);
  if (account_id !== accountID) return 0;
  return kind === 'income' ? amount : -amount;
}
