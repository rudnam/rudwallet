import { useCallback, useEffect, useMemo, useState } from 'react';
import { api, errorMessage, type Transaction, type TransactionFilter } from './api';
import { asRecord, matches, outbox, useUnsent } from './outbox';
import { useWallet } from './wallet';

/**
 * Loads records for a filter; reload() after changes. Records loaded for the
 * same filter before show at once, so going back to a page doesn't flash
 * "Loading…" and empty totals; the fresh list replaces them. Records added
 * offline and not sent yet are in the list too, where the server would put them.
 */
export function useRecords(filter: TransactionFilter) {
  const key = JSON.stringify(filter);
  const [records, setRecords] = useState<Transaction[] | null>(() => api.seen.transactions(filter) ?? null);
  const [error, setError] = useState('');
  const [shown, setShown] = useState(key);
  if (shown !== key) {
    setShown(key);
    const seen = api.seen.transactions(filter);
    if (seen) setRecords(seen);
  }

  const reload = useCallback(async () => {
    try {
      setRecords(await api.transactions(JSON.parse(key, revive)));
      setError('');
    } catch (err) {
      setError(errorMessage(err));
    }
  }, [key]);

  useEffect(() => {
    reload();
  }, [reload]);
  useEffect(() => outbox.onSent(reload), [reload]);

  const unsent = useUnsent(useWallet().me.email);
  const withUnsent = useMemo(() => {
    if (!records || unsent.length === 0) return records;
    const filter = JSON.parse(key, revive) as TransactionFilter;
    const extra = unsent.map(asRecord).filter((t) => matches(t, filter));
    if (extra.length === 0) return records;
    // Newest first, as the server sorts them.
    return [...records, ...extra].sort((a, b) => Date.parse(b.occurred_at) - Date.parse(a.occurred_at));
  }, [records, unsent, key]);

  return { records: withUnsent, error, reload };
}

// JSON turns Dates into strings; turn from/to back into Dates.
function revive(k: string, v: unknown) {
  return (k === 'from' || k === 'to') && typeof v === 'string' ? new Date(v) : v;
}
