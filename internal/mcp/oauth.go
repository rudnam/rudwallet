package mcp

// Sign-in for MCP clients: the OAuth 2.1 flow that the MCP specification's
// authorization section asks for, so a client such as a Claude connector
// can connect with just the /mcp address.
//
//  1. The client calls /mcp without a token and gets a 401 whose
//     WWW-Authenticate header points at the metadata documents below.
//  2. It registers itself at /oauth/register (dynamic client registration)
//     and gets a client ID. Clients are public: no secret, PKCE instead.
//  3. It sends the user's browser to /oauth/authorize, which checks the
//     request and passes it to the web app's Connect page (#/connect). There
//     the user signs in if needed and allows or denies the client, through
//     GET and POST /api/mcp/authorize.
//  4. The browser goes back to the client with a one-time code, which the
//     client exchanges at /oauth/token, with its PKCE verifier, for an access
//     token (an hour) and a refresh token (renewed on every use).
//
// A client that asks for scope=read (e.g. RudDash, which only shows
// spending) gets a read-only connection: only tools marked readOnlyHint,
// and the Connect page says so. Without a scope, access is full.
//
// Each connection is a row in mcp_tokens, listed in Settings → Claude, where
// disconnecting it revokes both tokens.

import (
	"crypto/sha256"
	"crypto/subtle"
	"encoding/base64"
	"encoding/json"
	"errors"
	"log/slog"
	"net/http"
	"net/url"
	"slices"
	"strings"
	"time"
	"unicode/utf8"

	"github.com/rudnam/wallet-app/internal/account"
	"github.com/rudnam/wallet-app/internal/db"
	"github.com/rudnam/wallet-app/internal/httpx"
)

const (
	resourceMetadataPath = "/.well-known/oauth-protected-resource/mcp"
	// maxRedirectURIs per client, and the longest one accepted.
	maxRedirectURIs   = 10
	maxRedirectURILen = 2000
)

func (h *Handler) registerOAuth(mux *http.ServeMux, requireUser func(http.Handler) http.Handler) {
	mux.HandleFunc("GET /.well-known/oauth-protected-resource", h.resourceMetadata)
	mux.HandleFunc("GET "+resourceMetadataPath, h.resourceMetadata)
	mux.HandleFunc("GET /.well-known/oauth-authorization-server", h.serverMetadata)
	mux.HandleFunc("POST /oauth/register", h.registerClient)
	mux.HandleFunc("GET /oauth/authorize", h.authorize)
	mux.HandleFunc("POST /oauth/token", h.token)
	mux.Handle("GET /api/mcp/authorize", requireUser(httpx.HandlerFunc(h.describeRequest)))
	mux.Handle("POST /api/mcp/authorize", requireUser(httpx.HandlerFunc(h.decide)))
}

// origin is the site's address, which issues tokens for origin + "/mcp".
func (h *Handler) origin(r *http.Request) string {
	if h.cfg.PublicOrigin != "" {
		return h.cfg.PublicOrigin
	}
	scheme := "http"
	if r.TLS != nil || h.cfg.TrustProxy && r.Header.Get("X-Forwarded-Proto") == "https" {
		scheme = "https"
	}
	return scheme + "://" + r.Host
}

// ---------- Discovery ----------

// writePublicJSON answers a metadata request; any site may read it.
func writePublicJSON(w http.ResponseWriter, v any) {
	w.Header().Set("Access-Control-Allow-Origin", "*")
	httpx.WriteJSON(w, http.StatusOK, v)
}

// resourceMetadata says which server issues tokens for /mcp (RFC 9728).
func (h *Handler) resourceMetadata(w http.ResponseWriter, r *http.Request) {
	o := h.origin(r)
	writePublicJSON(w, map[string]any{
		"resource":                 o + "/mcp",
		"resource_name":            "RudWallet",
		"authorization_servers":    []string{o},
		"bearer_methods_supported": []string{"header"},
	})
}

// serverMetadata describes the sign-in endpoints (RFC 8414).
func (h *Handler) serverMetadata(w http.ResponseWriter, r *http.Request) {
	o := h.origin(r)
	writePublicJSON(w, map[string]any{
		"issuer":                                o,
		"authorization_endpoint":                o + "/oauth/authorize",
		"token_endpoint":                        o + "/oauth/token",
		"registration_endpoint":                 o + "/oauth/register",
		"response_types_supported":              []string{"code"},
		"grant_types_supported":                 []string{"authorization_code", "refresh_token"},
		"code_challenge_methods_supported":      []string{"S256"},
		"token_endpoint_auth_methods_supported": []string{"none"},
		"scopes_supported":                      []string{readScope},
	})
}

// oauthError answers in the shape OAuth clients expect (RFC 6749 5.2).
func oauthError(w http.ResponseWriter, status int, code, description string) {
	w.Header().Set("Cache-Control", "no-store")
	httpx.WriteJSON(w, status, map[string]string{"error": code, "error_description": description})
}

// ---------- Registration ----------

// validRedirectURI accepts https addresses, http only on this machine (for
// apps that listen on a local port), and an app's own scheme in reverse
// domain form such as com.example.app:/callback (RFC 8252).
func validRedirectURI(raw string) bool {
	u, err := url.Parse(raw)
	if err != nil || len(raw) > maxRedirectURILen || u.Fragment != "" || u.User != nil {
		return false
	}
	switch u.Scheme {
	case "https":
		return u.Host != ""
	case "http":
		host := u.Hostname()
		return host == "localhost" || host == "127.0.0.1" || host == "::1"
	case "":
		return false
	}
	return strings.Contains(u.Scheme, ".")
}

func (h *Handler) registerClient(w http.ResponseWriter, r *http.Request) {
	if !h.limit.Allow(httpx.ClientIP(r, h.cfg.TrustProxy), time.Now()) {
		oauthError(w, http.StatusTooManyRequests, "slow_down", "too many registrations; wait a minute and try again")
		return
	}
	var in struct {
		ClientName   string   `json:"client_name"`
		RedirectURIs []string `json:"redirect_uris"`
	}
	// Clients send more metadata than is used here (grant_types, scope,
	// logo_uri, ...); RFC 7591 says to ignore what isn't understood.
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, httpx.MaxBody)).Decode(&in); err != nil {
		oauthError(w, http.StatusBadRequest, "invalid_client_metadata", "send the client's metadata as JSON")
		return
	}
	if len(in.RedirectURIs) == 0 || len(in.RedirectURIs) > maxRedirectURIs {
		oauthError(w, http.StatusBadRequest, "invalid_redirect_uri", "give 1 to 10 redirect_uris")
		return
	}
	for _, u := range in.RedirectURIs {
		if !validRedirectURI(u) {
			oauthError(w, http.StatusBadRequest, "invalid_redirect_uri", "redirect URIs must be https, http on localhost, or an app's own scheme")
			return
		}
	}
	name := strings.TrimSpace(in.ClientName)
	if name == "" {
		name = "MCP client"
	}
	if utf8.RuneCountInString(name) > 60 {
		name = string([]rune(name)[:60])
	}
	id, err := h.store.RegisterClient(r.Context(), name, in.RedirectURIs)
	if err != nil {
		slog.ErrorContext(r.Context(), "mcp client registration", "err", err)
		oauthError(w, http.StatusInternalServerError, "server_error", "internal error")
		return
	}
	httpx.WriteJSON(w, http.StatusCreated, map[string]any{
		"client_id":                  id,
		"client_name":                name,
		"redirect_uris":              in.RedirectURIs,
		"grant_types":                []string{"authorization_code", "refresh_token"},
		"response_types":             []string{"code"},
		"token_endpoint_auth_method": "none",
		"client_id_issued_at":        time.Now().Unix(),
	})
}

// ---------- Approval ----------

// errBadRequest means the client or its redirect URI is unknown: the user
// can't be sent back, so they are told instead.
var errBadRequest = errors.New("unknown client or redirect URI")

// checkClient finds the client and checks that redirectURI is one it
// registered. With one registered, redirectURI may be left out.
func (h *Handler) checkClient(r *http.Request, clientID, redirectURI string) (Client, string, error) {
	c, err := h.store.Client(r.Context(), clientID)
	if errors.Is(err, db.ErrNotFound) {
		return Client{}, "", errBadRequest
	}
	if err != nil {
		return Client{}, "", err
	}
	if redirectURI == "" && len(c.RedirectURIs) == 1 {
		redirectURI = c.RedirectURIs[0]
	}
	if !slices.Contains(c.RedirectURIs, redirectURI) {
		return Client{}, "", errBadRequest
	}
	return c, redirectURI, nil
}

// withParams adds query parameters to a redirect URI, keeping its own.
func withParams(redirectURI string, params url.Values) string {
	u, _ := url.Parse(redirectURI) // checked when the client registered
	q := u.Query()
	for k, v := range params {
		q[k] = v
	}
	u.RawQuery = q.Encode()
	return u.String()
}

// validChallenge accepts a PKCE S256 challenge: a base64url SHA-256.
func validChallenge(c string) bool {
	b, err := base64.RawURLEncoding.DecodeString(c)
	return err == nil && len(b) == sha256.Size
}

const badLink = "This sign-in link isn't valid. Go back to the app you were connecting and try again."

// readScope asks for a read-only connection.
const readScope = "read"

// wantsReadOnly reads an OAuth scope parameter (space-separated). Unknown
// scopes are ignored, so "read" anywhere in it means read-only.
func wantsReadOnly(scope string) bool {
	return slices.Contains(strings.Fields(scope), readScope)
}

// authorize is where a client sends the user. It checks the request and
// hands it to the web app's Connect page.
func (h *Handler) authorize(w http.ResponseWriter, r *http.Request) {
	q := r.URL.Query()
	_, redirectURI, err := h.checkClient(r, q.Get("client_id"), q.Get("redirect_uri"))
	if errors.Is(err, errBadRequest) {
		w.Header().Set("Content-Type", "text/plain; charset=utf-8")
		w.WriteHeader(http.StatusBadRequest)
		w.Write([]byte(badLink + "\n"))
		return
	}
	if err != nil {
		slog.ErrorContext(r.Context(), "mcp authorize", "err", err)
		httpx.WriteError(w, http.StatusInternalServerError, "internal error")
		return
	}
	fail := func(code, description string) {
		params := url.Values{"error": {code}, "error_description": {description}}
		if s := q.Get("state"); s != "" {
			params.Set("state", s)
		}
		http.Redirect(w, r, withParams(redirectURI, params), http.StatusFound)
	}
	if q.Get("response_type") != "code" {
		fail("unsupported_response_type", "only response_type=code is supported")
		return
	}
	if q.Get("code_challenge_method") != "S256" || !validChallenge(q.Get("code_challenge")) {
		fail("invalid_request", "PKCE with code_challenge_method=S256 is required")
		return
	}
	next := url.Values{
		"client_id":      {q.Get("client_id")},
		"redirect_uri":   {redirectURI},
		"code_challenge": {q.Get("code_challenge")},
	}
	if s := q.Get("state"); s != "" {
		next.Set("state", s)
	}
	if wantsReadOnly(q.Get("scope")) {
		next.Set("scope", readScope)
	}
	http.Redirect(w, r, "/app/#/connect?"+next.Encode(), http.StatusFound)
}

// describeRequest tells the Connect page which app is asking.
func (h *Handler) describeRequest(w http.ResponseWriter, r *http.Request) error {
	q := r.URL.Query()
	c, redirectURI, err := h.checkClient(r, q.Get("client_id"), q.Get("redirect_uri"))
	if errors.Is(err, errBadRequest) {
		return httpx.Errorf(http.StatusBadRequest, badLink)
	}
	if err != nil {
		return err
	}
	u, _ := url.Parse(redirectURI)
	back := u.Host
	if back == "" {
		back = u.Scheme
	}
	out := map[string]any{"client_name": c.Name, "redirect_host": back}
	if wantsReadOnly(q.Get("scope")) {
		out["read_only"] = true // only then, so the answer for other clients is as it was
	}
	httpx.WriteJSON(w, http.StatusOK, out)
	return nil
}

// decide records the user's answer and says where to send the browser.
func (h *Handler) decide(w http.ResponseWriter, r *http.Request) error {
	// A demo connects nothing: the app would reach a wallet about to vanish.
	if err := account.NotInDemo(r); err != nil {
		return err
	}
	var in struct {
		ClientID      string `json:"client_id"`
		RedirectURI   string `json:"redirect_uri"`
		CodeChallenge string `json:"code_challenge"`
		State         string `json:"state"`
		Scope         string `json:"scope"`
		Allow         bool   `json:"allow"`
	}
	if err := httpx.ReadJSON(w, r, httpx.MaxBody, &in); err != nil {
		return err
	}
	c, redirectURI, err := h.checkClient(r, in.ClientID, in.RedirectURI)
	if errors.Is(err, errBadRequest) {
		return httpx.Errorf(http.StatusBadRequest, badLink)
	}
	if err != nil {
		return err
	}
	params := url.Values{}
	if in.State != "" {
		params.Set("state", in.State)
	}
	if !in.Allow {
		params.Set("error", "access_denied")
		params.Set("error_description", "the user denied access")
	} else {
		if !validChallenge(in.CodeChallenge) {
			return httpx.Errorf(http.StatusBadRequest, badLink)
		}
		code, err := h.store.CreateCode(r.Context(), account.UserFrom(r).ID, c.ID, redirectURI, in.CodeChallenge, wantsReadOnly(in.Scope))
		if errors.Is(err, errTooMany) {
			return httpx.Errorf(http.StatusConflict, "you have %d connections already; disconnect one in Settings → Claude first", maxTokens)
		}
		if err != nil {
			return err
		}
		params.Set("code", code)
		params.Set("iss", h.origin(r)) // RFC 9207: which server the code is from
	}
	httpx.WriteJSON(w, http.StatusOK, map[string]string{"redirect_to": withParams(redirectURI, params)})
	return nil
}

// ---------- Tokens ----------

func (h *Handler) token(w http.ResponseWriter, r *http.Request) {
	r.Body = http.MaxBytesReader(w, r.Body, httpx.MaxBody)
	if err := r.ParseForm(); err != nil {
		oauthError(w, http.StatusBadRequest, "invalid_request", "send the request as a form")
		return
	}
	clientID := r.PostForm.Get("client_id")
	if id, _, ok := r.BasicAuth(); ok && clientID == "" {
		clientID = id
	}
	c, err := h.store.Client(r.Context(), clientID)
	if errors.Is(err, db.ErrNotFound) {
		oauthError(w, http.StatusUnauthorized, "invalid_client", "unknown client_id")
		return
	}
	if err != nil {
		slog.ErrorContext(r.Context(), "mcp token", "err", err)
		oauthError(w, http.StatusInternalServerError, "server_error", "internal error")
		return
	}

	var t Tokens
	switch r.PostForm.Get("grant_type") {
	case "authorization_code":
		var g grant
		g, err = h.store.TakeCode(r.Context(), r.PostForm.Get("code"))
		if err == nil {
			sum := sha256.Sum256([]byte(r.PostForm.Get("code_verifier")))
			proof := base64.RawURLEncoding.EncodeToString(sum[:])
			redirectURI := r.PostForm.Get("redirect_uri")
			if redirectURI == "" && len(c.RedirectURIs) == 1 {
				redirectURI = c.RedirectURIs[0]
			}
			if g.clientID != c.ID || g.redirectURI != redirectURI ||
				subtle.ConstantTimeCompare([]byte(proof), []byte(g.challenge)) != 1 {
				err = db.ErrNotFound
			}
		}
		if err == nil {
			t, err = h.store.Connect(r.Context(), g.userID, c, g.readOnly)
		}
	case "refresh_token":
		t, err = h.store.Refresh(r.Context(), c.ID, r.PostForm.Get("refresh_token"))
	default:
		oauthError(w, http.StatusBadRequest, "unsupported_grant_type", "use authorization_code or refresh_token")
		return
	}
	if errors.Is(err, db.ErrNotFound) {
		oauthError(w, http.StatusBadRequest, "invalid_grant", "the code or refresh token is invalid, expired or already used")
		return
	}
	if err != nil {
		slog.ErrorContext(r.Context(), "mcp token", "err", err)
		oauthError(w, http.StatusInternalServerError, "server_error", "internal error")
		return
	}
	w.Header().Set("Cache-Control", "no-store")
	out := map[string]any{
		"access_token":  t.Access,
		"token_type":    "Bearer",
		"expires_in":    int(accessTTL.Seconds()),
		"refresh_token": t.Refresh,
	}
	if t.ReadOnly {
		out["scope"] = readScope
	}
	httpx.WriteJSON(w, http.StatusOK, out)
}
