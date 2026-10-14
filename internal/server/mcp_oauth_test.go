package server_test

import (
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"net/http"
	"net/url"
	"strings"
	"testing"
)

// oauthPost posts a form or JSON body the way a client's server does: no
// cookies, no Origin.
func oauthPost(t *testing.T, endpoint string, body any) (int, map[string]any) {
	t.Helper()
	var req *http.Request
	if form, ok := body.(url.Values); ok {
		req, _ = http.NewRequest("POST", endpoint, strings.NewReader(form.Encode()))
		req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	} else {
		b, _ := json.Marshal(body)
		req, _ = http.NewRequest("POST", endpoint, strings.NewReader(string(b)))
		req.Header.Set("Content-Type", "application/json")
	}
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	var out map[string]any
	json.NewDecoder(resp.Body).Decode(&out)
	return resp.StatusCode, out
}

// noRedirects returns the first response instead of following a redirect.
var noRedirects = &http.Client{CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}

func pkce(verifier string) string {
	sum := sha256.Sum256([]byte(verifier))
	return base64.RawURLEncoding.EncodeToString(sum[:])
}

func TestMCPSignIn(t *testing.T) {
	ts := newTestServer(t)
	alice, bob := newClient(t, ts), newClient(t, ts)
	alice.signup("alice@example.com")
	bob.signup("bob@example.com")
	const callback = "https://claude.example/api/mcp/auth_callback"

	// 1. Without a token, /mcp points to its metadata, which points to the
	// sign-in server.
	req, _ := http.NewRequest("POST", ts.URL+"/mcp", strings.NewReader(`{"jsonrpc":"2.0","id":1,"method":"initialize"}`))
	resp, err := http.DefaultClient.Do(req)
	if err != nil || resp.StatusCode != 401 ||
		!strings.Contains(resp.Header.Get("WWW-Authenticate"), `resource_metadata="`+ts.URL+`/.well-known/oauth-protected-resource/mcp"`) {
		t.Fatalf("401: %v %v", resp.Header, err)
	}
	var res, as map[string]any
	for _, p := range []string{"/.well-known/oauth-protected-resource/mcp", "/.well-known/oauth-protected-resource"} {
		r, body := get(t, ts.URL+p)
		json.Unmarshal([]byte(body), &res)
		if r.StatusCode != 200 || res["resource"] != ts.URL+"/mcp" || res["authorization_servers"].([]any)[0] != ts.URL {
			t.Fatalf("%s: %d %s", p, r.StatusCode, body)
		}
	}
	_, body := get(t, ts.URL+"/.well-known/oauth-authorization-server")
	json.Unmarshal([]byte(body), &as)
	if as["issuer"] != ts.URL || as["registration_endpoint"] != ts.URL+"/oauth/register" ||
		as["code_challenge_methods_supported"].([]any)[0] != "S256" {
		t.Fatalf("server metadata %s", body)
	}

	// 2. The client registers itself. Odd redirect URIs are refused.
	for _, bad := range []string{"http://claude.example/cb", "javascript:alert(1)", "https://claude.example/cb#x", ""} {
		if code, out := oauthPost(t, ts.URL+"/oauth/register", map[string]any{"redirect_uris": []string{bad}}); code != 400 || out["error"] != "invalid_redirect_uri" {
			t.Errorf("redirect %q: %d %v", bad, code, out)
		}
	}
	code, reg := oauthPost(t, ts.URL+"/oauth/register", map[string]any{
		"client_name": "Claude", "redirect_uris": []string{callback}, "token_endpoint_auth_method": "none",
		"grant_types": []string{"authorization_code", "refresh_token"}, "response_types": []string{"code"}, "scope": "wallet"})
	clientID, _ := reg["client_id"].(string)
	if code != 201 || !strings.HasPrefix(clientID, "wmcpc_") || reg["token_endpoint_auth_method"] != "none" {
		t.Fatalf("register: %d %v", code, reg)
	}
	_, other := oauthPost(t, ts.URL+"/oauth/register", map[string]any{"client_name": "Other", "redirect_uris": []string{"http://127.0.0.1:3118/cb"}})
	otherID := other["client_id"].(string)

	// 3. The user's browser comes to /oauth/authorize and is passed to the
	// Connect page, unless the request is bad.
	verifier := strings.Repeat("v", 50)
	authorize := func(q url.Values) *http.Response {
		resp, err := noRedirects.Get(ts.URL + "/oauth/authorize?" + q.Encode())
		if err != nil {
			t.Fatal(err)
		}
		resp.Body.Close()
		return resp
	}
	good := url.Values{"response_type": {"code"}, "client_id": {clientID}, "redirect_uri": {callback},
		"code_challenge": {pkce(verifier)}, "code_challenge_method": {"S256"}, "state": {"xyz"}}
	resp = authorize(good)
	loc := resp.Header.Get("Location")
	if resp.StatusCode != 302 || !strings.HasPrefix(loc, "/app/#/connect?") {
		t.Fatalf("authorize: %d %q", resp.StatusCode, loc)
	}
	page, _ := url.ParseQuery(strings.TrimPrefix(loc, "/app/#/connect?"))
	if page.Get("client_id") != clientID || page.Get("state") != "xyz" || page.Get("redirect_uri") != callback {
		t.Errorf("connect page params %v", page)
	}
	// An unknown client or redirect gets a page, not a redirect.
	for _, q := range []url.Values{{"client_id": {"wmcpc_nope"}}, {"client_id": {clientID}, "redirect_uri": {"https://evil.example/cb"}}} {
		if r := authorize(q); r.StatusCode != 400 || r.Header.Get("Location") != "" {
			t.Errorf("bad client %v: %d", q, r.StatusCode)
		}
	}
	// Without PKCE, the client is told so.
	noPKCE := url.Values{"response_type": {"code"}, "client_id": {clientID}, "state": {"s"}}
	if r := authorize(noPKCE); r.StatusCode != 302 || !strings.HasPrefix(r.Header.Get("Location"), callback+"?") ||
		!strings.Contains(r.Header.Get("Location"), "error=invalid_request") || !strings.Contains(r.Header.Get("Location"), "state=s") {
		t.Errorf("no PKCE: %d %q", r.StatusCode, r.Header.Get("Location"))
	}

	// The Connect page asks the API who is asking; it needs a signed-in user.
	var info map[string]string
	q := "?client_id=" + url.QueryEscape(clientID) + "&redirect_uri=" + url.QueryEscape(callback)
	if code := newClient(t, ts).do("GET", "/api/mcp/authorize"+q, nil, nil); code != 401 {
		t.Errorf("signed out: %d", code)
	}
	if code := alice.do("GET", "/api/mcp/authorize"+q, nil, &info); code != 200 || info["client_name"] != "Claude" || info["redirect_host"] != "claude.example" {
		t.Fatalf("describe: %d %v", code, info)
	}

	// Denying sends the browser back with access_denied.
	decide := func(c *client, allow bool, challenge string) (int, string) {
		var out map[string]string
		code := c.do("POST", "/api/mcp/authorize", map[string]any{
			"client_id": clientID, "redirect_uri": callback, "code_challenge": challenge, "state": "xyz", "allow": allow}, &out)
		return code, out["redirect_to"]
	}
	if code, to := decide(alice, false, pkce(verifier)); code != 200 || !strings.HasPrefix(to, callback+"?") || !strings.Contains(to, "error=access_denied") {
		t.Errorf("deny: %d %q", code, to)
	}
	// Allowing sends it back with a code.
	code, to := decide(alice, true, pkce(verifier))
	back, _ := url.Parse(to)
	authCode := back.Query().Get("code")
	if code != 200 || !strings.HasPrefix(to, callback+"?") || authCode == "" || back.Query().Get("state") != "xyz" || back.Query().Get("iss") != ts.URL {
		t.Fatalf("allow: %d %q", code, to)
	}

	// 4. The code becomes tokens only with the right verifier, client and
	// redirect URI, and only once.
	exchange := func(client, code, verifier, redirect string) (int, map[string]any) {
		return oauthPost(t, ts.URL+"/oauth/token", url.Values{"grant_type": {"authorization_code"}, "client_id": {client},
			"code": {code}, "code_verifier": {verifier}, "redirect_uri": {redirect}})
	}
	if code, out := exchange(otherID, authCode, verifier, callback); code != 400 || out["error"] != "invalid_grant" {
		t.Errorf("other client: %d %v", code, out)
	}
	_, to = decide(alice, true, pkce(verifier))
	back, _ = url.Parse(to)
	authCode = back.Query().Get("code")
	if code, out := exchange(clientID, authCode, "wrong-verifier-"+verifier, callback); code != 400 || out["error"] != "invalid_grant" {
		t.Errorf("wrong verifier: %d %v", code, out)
	}
	if code, out := exchange(clientID, authCode, verifier, callback); code != 400 {
		t.Errorf("a code is spent by a failed attempt: %d %v", code, out)
	}
	_, to = decide(alice, true, pkce(verifier))
	back, _ = url.Parse(to)
	authCode = back.Query().Get("code")
	code, tokens := exchange(clientID, authCode, verifier, callback)
	access, _ := tokens["access_token"].(string)
	refresh, _ := tokens["refresh_token"].(string)
	if code != 200 || !strings.HasPrefix(access, "wmcp_") || refresh == "" || tokens["token_type"] != "Bearer" || tokens["expires_in"] != float64(3600) {
		t.Fatalf("exchange: %d %v", code, tokens)
	}
	if code, _ := exchange(clientID, authCode, verifier, callback); code != 400 {
		t.Errorf("code used twice: %d", code)
	}
	if code, out := oauthPost(t, ts.URL+"/oauth/token", url.Values{"grant_type": {"password"}, "client_id": {clientID}}); code != 400 || out["error"] != "unsupported_grant_type" {
		t.Errorf("password grant: %d %v", code, out)
	}

	// The access token works at /mcp, for Alice.
	if code, out := mcpCall(t, ts.URL, access, "tools/call", map[string]any{"name": "get_overview", "arguments": map[string]any{}}); code != 200 || out["error"] != nil {
		t.Fatalf("mcp with signed-in token: %d %v", code, out)
	}

	// Refreshing replaces both tokens; the old ones stop working. Another
	// client can't use the refresh token.
	refreshWith := func(client, token string) (int, map[string]any) {
		return oauthPost(t, ts.URL+"/oauth/token", url.Values{"grant_type": {"refresh_token"}, "client_id": {client}, "refresh_token": {token}})
	}
	if code, _ := refreshWith(otherID, refresh); code != 400 {
		t.Errorf("other client refreshed: %d", code)
	}
	code, renewed := refreshWith(clientID, refresh)
	access2, _ := renewed["access_token"].(string)
	refresh2, _ := renewed["refresh_token"].(string)
	if code != 200 || access2 == "" || access2 == access || refresh2 == refresh {
		t.Fatalf("refresh: %d %v", code, renewed)
	}
	if code, _ := refreshWith(clientID, refresh); code != 400 {
		t.Errorf("old refresh token: %d", code)
	}
	if code, _ := mcpCall(t, ts.URL, access, "ping", nil); code != 401 {
		t.Errorf("old access token: %d", code)
	}
	if code, _ := mcpCall(t, ts.URL, access2, "ping", nil); code != 200 {
		t.Errorf("new access token: %d", code)
	}

	// The connection is listed in Settings, and only for Alice; disconnecting
	// it revokes it.
	var list []struct {
		ID       int64  `json:"id"`
		Name     string `json:"name"`
		SignedIn bool   `json:"signed_in"`
	}
	if alice.do("GET", "/api/mcp/tokens", nil, &list); len(list) != 1 || list[0].Name != "Claude" || !list[0].SignedIn {
		t.Fatalf("tokens %+v", list)
	}
	if code := bob.do("DELETE", "/api/mcp/tokens/"+itoa(list[0].ID), nil, nil); code != 404 {
		t.Errorf("bob disconnected alice: %d", code)
	}
	if code := alice.do("DELETE", "/api/mcp/tokens/"+itoa(list[0].ID), nil, nil); code != 204 {
		t.Fatalf("disconnect: %d", code)
	}
	if code, _ := mcpCall(t, ts.URL, access2, "ping", nil); code != 401 {
		t.Errorf("disconnected token: %d", code)
	}
	if code, _ := refreshWith(clientID, refresh2); code != 400 {
		t.Errorf("disconnected refresh token: %d", code)
	}

	// Another site can't approve on the user's behalf.
	alice.headers["Origin"] = "https://evil.example"
	if code, _ := decide(alice, true, pkce(verifier)); code != 403 {
		t.Errorf("cross-site approval: %d", code)
	}
}
