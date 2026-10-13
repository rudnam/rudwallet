import { useCallback, useEffect, useState } from 'react';
import { api, errorMessage, type Transaction, type TransactionFilter } from './api';

/**
 * Loads records for a filter; reload() after changes. Records loaded for the
 * same filter before show at once, so going back to a page doesn't flash
 * "Loading…" and empty totals; the fresh list replaces them.
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

  return { records, error, reload };
}

// JSON turns Dates into strings; turn from/to back into Dates.
function revive(k: string, v: unknown) {
  return (k === 'from' || k === 'to') && typeof v === 'string' ? new Date(v) : v;
}
