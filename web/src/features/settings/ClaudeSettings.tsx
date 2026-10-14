// Claude: the user's own Anthropic API key, which turns on sorting records
// and the AI email reader, and access tokens that let an MCP client (Claude
// Desktop, Claude Code) connect to the wallet.
import { useEffect, useState, type FormEvent } from 'react';
import { api, errorMessage, type AIStatus, type MCPToken } from '../../api';
import { Icon, icons } from '../../components/Icon';
import { formatDay } from '../../money';
import { server } from '../../native';
import { href } from '../../router';
import { useToast } from '../../toast';

export function ClaudeSettings() {
  return (
    <>
      <ApiKey />
      <Connections />
    </>
  );
}

function ApiKey() {
  const toast = useToast();
  const [status, setStatus] = useState<AIStatus | null>(null);
  const [editing, setEditing] = useState(false);
  const [key, setKey] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  useEffect(() => {
    api.ai().then(setStatus, (err) => setError(errorMessage(err)));
  }, []);

  async function save(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError('');
    try {
      setStatus(await api.setAIKey(key));
      setKey('');
      setEditing(false);
      toast('Claude is connected');
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setBusy(false);
    }
  }

  async function remove() {
    if (!confirm('Remove your Claude API key? Claude stops sorting records and reading emails for you.')) return;
    try {
      await api.deleteAIKey();
      setStatus(await api.ai());
      toast('Key removed');
    } catch (err) {
      setError(errorMessage(err));
    }
  }

  const form = (
    <form className="stack tight" onSubmit={save}>
      <label className="field">
        Anthropic API key
        <input
          type="password"
          autoComplete="off"
          spellCheck={false}
          placeholder="sk-ant-…"
          required
          value={key}
          onChange={(e) => setKey(e.target.value)}
        />
        <span className="muted small">
          Make one in the Claude Console at console.anthropic.com, under API keys. It's checked with Claude, then stored encrypted.
        </span>
      </label>
      <div className="row end">
        {editing && (
          <button type="button" className="link" onClick={() => setEditing(false)}>
            Cancel
          </button>
        )}
        <button type="submit" disabled={busy || !key.trim()}>
          {busy ? 'Checking…' : 'Save key'}
        </button>
      </div>
    </form>
  );

  return (
    <div className="card stack">
      <h2>Your API key</h2>
      <p className="small">
        With your own key, Claude sorts records that have no category and reads payment emails from any shop. What Claude does is billed to
        your Anthropic account.
      </p>
      {status === null && !error && <p className="muted">Loading…</p>}
      {status && !status.can_save && !status.has_key && (
        <p className="notice warn small">
          This server can't store API keys yet. Whoever runs it needs to set <code>SECRETS_KEY</code>, which keeps saved keys
          encrypted.
        </p>
      )}
      {status?.can_save && !status.has_key && form}
      {status?.key_unreadable && (
        <>
          <p className="notice warn small" role="status">
            Your saved key can't be read any more, because the server's encryption secret changed. Add it again.
          </p>
          {status.can_save && form}
        </>
      )}
      {status?.has_key && !status.key_unreadable && (
        <>
          <div className="row wrap key-row">
            <span className="row">
              <span className="badge status-payment">
                <Icon d={icons.check} className="inline-icon" /> Connected
              </span>
              <span className="muted small nowrap">Key ending in {status.key_hint}</span>
            </span>
            {!editing && (
              <span className="row">
                <button type="button" className="link" onClick={() => setEditing(true)}>
                  Replace
                </button>
                <button type="button" className="link danger" onClick={remove}>
                  Remove
                </button>
              </span>
            )}
          </div>
          {editing && form}
          <ul className="list rows" aria-label="What Claude does for you">
            <li className="row">
              <span className="grow">
                <span className="block">Sort records</span>
                <span className="muted small">
                  {status.uncategorized === 0
                    ? 'Every record has a category.'
                    : `${status.uncategorized} ${status.uncategorized === 1 ? 'record has' : 'records have'} no category.`}
                </span>
              </span>
              <a className="button secondary" href={href('organize')}>
                Open
              </a>
            </li>
            <li className="row">
              <span className="grow">
                <span className="block">Read payment emails</span>
                <span className="muted small">Receipts from shops without a built-in rule are read by Claude.</span>
              </span>
            </li>
          </ul>
        </>
      )}
      {error && (
        <p role="alert" className="error">
          {error}
        </p>
      )}
    </div>
  );
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
    if (!confirm(`Disconnect "${t.name}"? It can no longer reach your wallet.`)) return;
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
      <h2>Connect Claude to your wallet</h2>
      <p className="small">
        Let Claude look through your records and tidy them: sort records, fix categories, and answer questions about your spending. It
        doesn't need your API key.
      </p>
      <p className="small">
        <strong>In the Claude app</strong> (claude.ai, desktop or mobile): open Settings → Connectors, add a custom connector with the
        address below, then click Connect and allow it here. Leave the OAuth fields empty.
      </p>
      <div className="address">
        <span className="muted small">MCP server address</span>
        <code data-testid="mcp-url">{mcpURL}</code>
      </div>

      {fresh?.token && (
        <div className="notice stack tight" role="status">
          <strong>Copy this token now. It won't be shown again.</strong>
          <button type="button" className="code-button" onClick={() => copy(fresh.token!, 'token')}>
            <code data-testid="mcp-token">{fresh.token}</code> <Icon d={copied === 'token' ? icons.check : icons.copy} className="inline-icon" />
          </button>
        </div>
      )}

      <form className="row wrap" onSubmit={create}>
        <label className="field grow">
          New connection
          <input maxLength={60} placeholder="Claude Desktop" value={name} onChange={(e) => setName(e.target.value)} />
        </label>
        <button type="submit" className="align-end">
          Make a token
        </button>
      </form>

      <details className="stack tight">
        <summary>How to connect with a token</summary>
        <p className="small">
          <strong>Claude Code:</strong> run (tap to copy)
        </p>
        <button type="button" className="code-button" onClick={() => copy(claudeCode, 'code')}>
          <code>{claudeCode}</code> <Icon d={copied === 'code' ? icons.check : icons.copy} className="inline-icon" />
        </button>
        <p className="small">
          <strong>Claude Desktop:</strong> open Settings → Developer → Edit Config, and add this to <code>claude_desktop_config.json</code> (it
          needs Node.js). Then restart Claude Desktop.
        </p>
        <button type="button" className="code-button" onClick={() => copy(desktop, 'desktop')}>
          <pre className="small">{desktop}</pre>
          <Icon d={copied === 'desktop' ? icons.check : icons.copy} className="inline-icon" />
        </button>
        <p className="small muted">Then ask, for example: "Sort my records that have no category."</p>
      </details>

      {tokens && tokens.length > 0 && (
        <ul className="list rows" aria-label="Connections">
          {tokens.map((t) => (
            <li key={t.id} className="row">
              <span className="grow">
                <span className="block">{t.name}</span>
                <span className="muted small">
                  {t.signed_in ? 'Connected' : 'Made'} {formatDay(new Date(t.created_at))}
                  {t.last_used_at ? ` · last used ${formatDay(new Date(t.last_used_at))}` : ' · not used yet'}
                </span>
              </span>
              <button type="button" className="link danger" onClick={() => remove(t)}>
                Disconnect
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
