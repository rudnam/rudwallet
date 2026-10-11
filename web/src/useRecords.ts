import { useCallback, useEffect, useState } from 'react';
import { api, errorMessage, type Transaction, type TransactionFilter } from './api';

/** Loads records for a filter; reload() after changes. */
export function useRecords(filter: TransactionFilter) {
  const [records, setRecords] = useState<Transaction[] | null>(null);
  const [error, setError] = useState('');
  const key = JSON.stringify(filter);

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
