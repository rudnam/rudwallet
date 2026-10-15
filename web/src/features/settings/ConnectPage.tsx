// Connect: an MCP client such as a Claude connector asks to reach the
// wallet, and the signed-in user allows or denies it. The server sends the
// browser here from /oauth/authorize (see internal/mcp/oauth.go) and, once
// the user answers, back to the client.
import { useEffect, useState } from 'react';
import { api, errorMessage, type MCPClientInfo } from '../../api';
import { href, type Route } from '../../router';

/** Where a sign-in started at #/connect is kept while Google sign-in leaves the page. */
const pendingKey = 'wallet.connect';

/** Remembers a Connect request that is waiting for the user to sign in. */
export function rememberConnect() {
  try {
    if (location.hash.startsWith('#/connect?')) sessionStorage.setItem(pendingKey, location.hash);
  } catch {
    // Without storage, Google sign-in lands on Home; the client can start again.
  }
}

/** After signing in, goes back to a Connect request the user started before. */
export function resumeConnect() {
  try {
    const pending = sessionStorage.getItem(pendingKey);
    sessionStorage.removeItem(pendingKey);
    if (pending && !location.hash.startsWith('#/connect?')) location.replace(location.pathname + pending); // fires hashchange, so the page shows
  } catch {
    // nothing to resume
  }
}

export function ConnectPage({ route, email }: { route: Route; email: string }) {
  const p = route.params;
  const request = {
    client_id: p.get('client_id') ?? '',
    redirect_uri: p.get('redirect_uri') ?? '',
    code_challenge: p.get('code_challenge') ?? '',
    state: p.get('state') ?? '',
  };
  const [client, setClient] = useState<MCPClientInfo | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  useEffect(() => {
    api.mcpClient(request.client_id, request.redirect_uri).then(setClient, (err) => setError(errorMessage(err)));
  }, [request.client_id, request.redirect_uri]);

  async function answer(allow: boolean) {
    setBusy(true);
    setError('');
    try {
      const { redirect_to } = await api.mcpAuthorize({ ...request, allow });
      location.replace(redirect_to);
    } catch (err) {
      setError(errorMessage(err));
      setBusy(false);
    }
  }

  return (
    <div className="card stack connect">
      {!client && !error && <p className="muted">Loading…</p>}
      {client && (
        <>
          <h1>Connect {client.client_name}</h1>
          <p>
            <strong>{client.client_name}</strong> is asking to reach your wallet, signed in as <strong>{email}</strong>. It will be able to:
          </p>
          <ul className="small connect-list">
            <li>see your accounts, records and categories</li>
            <li>change the category or account of records, and add categories</li>
          </ul>
          <p className="muted small">
            It can't add or delete records, or see your password or API key. You can disconnect it at any time in Settings → Claude.
          </p>
          <p className="muted small">
            You'll go back to <strong>{client.redirect_host}</strong>.
          </p>
          <div className="row end">
            <button type="button" className="secondary" disabled={busy} onClick={() => answer(false)}>
              Deny
            </button>
            <button type="button" disabled={busy} onClick={() => answer(true)}>
              Allow
            </button>
          </div>
        </>
      )}
      {error && (
        <>
          <p role="alert" className="error">
            {error}
          </p>
          <a href={href('home')}>Go to your wallet</a>
        </>
      )}
    </div>
  );
}
