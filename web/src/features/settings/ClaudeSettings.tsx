// Claude: access tokens that let an MCP client (the Claude app, Claude
// Desktop, Claude Code) connect to the wallet, to sort records and answer
// questions about spending.
import { useEffect, useState, type FormEvent } from 'react';
import { api, errorMessage, type MCPToken } from '../../api';
import { Icon, icons } from '../../components/Icon';
import { formatDay } from '../../money';
import { server } from '../../native';
import { useWallet } from '../../wallet';
import { t, t as tr, tx } from '../../i18n';

export function ClaudeSettings() {
  const { me } = useWallet();
  if (me.demo) {
    // A demo can't make tokens: it would reach outside the app.
    return (
      <div className="card stack">
        <p className="small">
          {t('Connect the Claude app, Claude Desktop or Claude Code to your wallet, and Claude can sort records that have no category and answer questions about your spending.')}
        </p>
        <p className="notice small">{t('Claude needs an account of your own; it isn’t available in the demo.')}</p>
      </div>
    );
  }
  return <Connections />;
}

/** Where MCP clients connect: this site, or in the Android app, its server. */
const mcpURL = `${server || location.origin}/mcp`;

function Connections() {
  const [tokens, setTokens] = useState<MCPToken[] | null>(null);
  const [name, setName] = useState('');
  const [fresh, setFresh] = useState<MCPToken | null>(null);
  const [copied, setCopied] = useState('');
  const [error, setError] = useState('');

  useEffect(() => {
    api.mcpTokens().then(setTokens, (err) => setError(errorMessage(err)));
  }, []);

  async function create(e: FormEvent) {
    e.preventDefault();
    setError('');
    try {
      const t = await api.createMCPToken(name.trim() || 'Claude');
      setFresh(t);
      setName('');
      setTokens(await api.mcpTokens());
    } catch (err) {
      setError(errorMessage(err));
    }
  }

  async function remove(t: MCPToken) {
    if (!confirm(tr('Disconnect "{name}"? It can no longer reach your wallet.', { name: t.name }))) return;
    try {
      await api.deleteMCPToken(t.id);
      if (fresh?.id === t.id) setFresh(null);
      setTokens(await api.mcpTokens());
    } catch (err) {
      setError(errorMessage(err));
    }
  }

  async function copy(text: string, what: string) {
    try {
      await navigator.clipboard.writeText(text);
      setCopied(what);
      setTimeout(() => setCopied(''), 2000);
    } catch {
      setCopied('');
    }
  }

  const token = fresh?.token ?? '<your token>';
  const claudeCode = `claude mcp add --transport http wallet ${mcpURL} --header "Authorization: Bearer ${token}"`;
  const desktop = JSON.stringify(
    {
      mcpServers: {
        wallet: {
          command: 'npx',
          args: ['-y', 'mcp-remote', mcpURL, '--header', 'Authorization:${WALLET_TOKEN}'],
          env: { WALLET_TOKEN: `Bearer ${token}` },
        },
      },
    },
    null,
    2,
  );

  return (
    <div className="card stack">
      <h2>{t('Connect Claude to your wallet')}</h2>
      <p className="small">
        {t('Let Claude look through your records and tidy them: sort records, fix categories, and answer questions about your spending.')}
      </p>
      <p className="small">
        {tx('{where} (claude.ai, desktop or mobile): open Settings → Connectors, add a custom connector with the address below, then click Connect and allow it here. Leave the OAuth fields empty.', {
          where: <strong>{t('In the Claude app')}</strong>,
        })}
      </p>
      <div className="address">
        <span className="muted small">{t('MCP server address')}</span>
        <code data-testid="mcp-url">{mcpURL}</code>
      </div>

      {fresh?.token && (
        <div className="notice stack tight" role="status">
          <strong>{t("Copy this token now. It won't be shown again.")}</strong>
          <button type="button" className="code-button" onClick={() => copy(fresh.token!, 'token')}>
            <code data-testid="mcp-token">{fresh.token}</code> <Icon d={copied === 'token' ? icons.check : icons.copy} className="inline-icon" />
          </button>
        </div>
      )}

      <form className="row wrap" onSubmit={create}>
        <label className="field grow">
          {t('New connection')}
          <input maxLength={60} placeholder="Claude Desktop" value={name} onChange={(e) => setName(e.target.value)} />
        </label>
        <button type="submit" className="align-end">
          {t('Make a token')}
        </button>
      </form>

      <details className="stack tight">
        <summary>{t('How to connect with a token')}</summary>
        <p className="small">
          <strong>Claude Code:</strong> {t('run (tap to copy)')}
        </p>
        <button type="button" className="code-button" onClick={() => copy(claudeCode, 'code')}>
          <code>{claudeCode}</code> <Icon d={copied === 'code' ? icons.check : icons.copy} className="inline-icon" />
        </button>
        <p className="small">
          <strong>Claude Desktop:</strong>{' '}
          {tx('open Settings → Developer → Edit Config, and add this to {file} (it needs Node.js). Then restart Claude Desktop.', {
            file: <code>claude_desktop_config.json</code>,
          })}
        </p>
        <button type="button" className="code-button" onClick={() => copy(desktop, 'desktop')}>
          <pre className="small">{desktop}</pre>
          <Icon d={copied === 'desktop' ? icons.check : icons.copy} className="inline-icon" />
        </button>
        <p className="small muted">{t('Then ask, for example: "Sort my records that have no category."')}</p>
      </details>

      {tokens && tokens.length > 0 && (
        <ul className="list rows" aria-label={t('Connections')}>
          {tokens.map((t) => (
            <li key={t.id} className="row">
              <span className="grow">
                <span className="block">{t.name}</span>
                <span className="muted small">
                  {t.signed_in ? tr('Connected {day}', { day: formatDay(new Date(t.created_at)) }) : tr('Made {day}', { day: formatDay(new Date(t.created_at)) })}
                  {' · '}
                  {t.last_used_at ? tr('last used {day}', { day: formatDay(new Date(t.last_used_at)) }) : tr('not used yet')}
                </span>
              </span>
              <button type="button" className="link danger" onClick={() => remove(t)}>
                {tr('Disconnect')}
              </button>
            </li>
          ))}
        </ul>
      )}
      {error && (
        <p role="alert" className="error">
          {error}
        </p>
      )}
    </div>
  );
}
