// Choosing the account for a group of an import's records (a BudgetBakers
// account, a PayPay way of paying, an MUFG or IC card role): one of the
// user's accounts, or a new one under a name the user can change.
import type { ReactNode } from 'react';
import { skipAccount, type Account, type AccountChoice } from '../../api';
import { t } from '../../i18n';

/** Each group's new account name, used when its account is 0 (a new one). */
export type NewNames = Record<string, string>;

export function ImportAccount({
  label,
  accounts,
  value,
  onChange,
  newName,
  onNewName,
  canSkip = false,
}: {
  label: ReactNode;
  /** The user's accounts to offer. */
  accounts: Account[];
  /** An account ID, 0 for a new account, or skipAccount. */
  value: number;
  onChange: (id: number) => void;
  newName: string;
  onNewName: (name: string) => void;
  canSkip?: boolean;
}) {
  const name = newName.trim();
  const existing = name && accounts.find((a) => a.name.toLowerCase() === name.toLowerCase());
  return (
    <div className="field">
      <label className="field">
        <span>{label}</span>
        <select value={value} onChange={(e) => onChange(Number(e.target.value))}>
          {accounts.map((a) => (
            <option key={a.id} value={a.id}>
              {a.name}
            </option>
          ))}
          <option value={0}>{name ? t('New account: {name}', { name }) : t('New account')}</option>
          {canSkip && <option value={skipAccount}>{t("Don't import")}</option>}
        </select>
      </label>
      {value === 0 && (
        <label className="field import-new-account">
          <span className="muted small">{t('New account name')}</span>
          <input value={newName} required maxLength={60} onChange={(e) => onNewName(e.target.value)} />
          {existing && <span className="muted small">{t('You already have an account named {name}; its records go there.', { name: existing.name })}</span>}
        </label>
      )}
    </div>
  );
}

/** The account choices as the API takes them: a new_name with each new account. */
export function accountChoices(choice: Record<string, number>, names: NewNames): AccountChoice[] {
  return Object.entries(choice).map(([name, account_id]) =>
    account_id === 0 && names[name]?.trim() ? { name, account_id, new_name: names[name].trim() } : { name, account_id },
  );
}
