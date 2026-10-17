// Package mcp lets an MCP client (Claude Desktop, Claude Code, or any other)
// connect to a user's wallet at /mcp, so Claude can look through their
// records and tidy them: sort uncategorised records, fix categories, add
// categories. A client sends an access token as "Authorization: Bearer
// <token>": one the user made in Settings → Claude, or one the client got by
// having the user sign in and approve it (OAuth, see oauth.go), which is how
// Claude's connectors work.
//
// It speaks the Model Context Protocol's Streamable HTTP transport, the
// simple way: every JSON-RPC request gets one JSON response, there are no
// server-sent events and no sessions.
package mcp

import (
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"net/http"
	"slices"
	"strings"
	"time"
	"unicode/utf8"

	"github.com/rudnam/wallet-app/internal/account"
	"github.com/rudnam/wallet-app/internal/db"
	"github.com/rudnam/wallet-app/internal/httpx"
	"github.com/rudnam/wallet-app/internal/wallet"
)

type Config struct {
	// Version is reported to clients as the server's version.
	Version string
	// PublicOrigin is the site's address, e.g. https://app.example.com, which
	// sign-in names as its issuer. Empty means the request's own.
	PublicOrigin string
	// TrustProxy reads the client IP, and whether the request came over
	// HTTPS, from the headers the reverse proxy adds.
	TrustProxy bool
}

type Handler struct {
	cfg    Config
	store  *Store
	wallet *wallet.Store
	// limit caps how often one IP address can register clients.
	limit *httpx.RateLimiter
}

// NewHandler serves /mcp, the sign-in for MCP clients, and the token settings.
func NewHandler(st *Store, w *wallet.Store, cfg Config) *Handler {
	return &Handler{cfg: cfg, store: st, wallet: w, limit: httpx.NewRateLimiter(10, time.Minute)}
}

func (h *Handler) Register(mux *http.ServeMux, requireUser func(http.Handler) http.Handler) {
	mux.Handle("GET /api/mcp/tokens", requireUser(httpx.HandlerFunc(h.listTokens)))
	mux.Handle("POST /api/mcp/tokens", requireUser(httpx.HandlerFunc(h.createToken)))
	mux.Handle("DELETE /api/mcp/tokens/{id}", requireUser(httpx.HandlerFunc(h.deleteToken)))
	mux.Handle("/mcp", http.HandlerFunc(h.serve))
	h.registerOAuth(mux, requireUser)
}

// ---------- Token settings ----------

func (h *Handler) listTokens(w http.ResponseWriter, r *http.Request) error {
	list, err := h.store.List(r.Context(), account.UserFrom(r).ID)
	if err != nil {
		return err
	}
	httpx.WriteJSON(w, http.StatusOK, list)
	return nil
}

type newToken struct {
	Token
	// Secret is the token itself, shown this once.
	Secret string `json:"token"`
}

func (h *Handler) createToken(w http.ResponseWriter, r *http.Request) error {
	if err := account.NotInDemo(r); err != nil {
		return err
	}
	var in struct {
		Name string `json:"name"`
	}
	if err := httpx.ReadJSON(w, r, httpx.MaxBody, &in); err != nil {
		return err
	}
	name := strings.TrimSpace(in.Name)
	if name == "" {
		name = "MCP client"
	}
	if utf8.RuneCountInString(name) > 60 {
		return httpx.Errorf(http.StatusBadRequest, "a name must be at most 60 characters")
	}
	t, secret, err := h.store.Create(r.Context(), account.UserFrom(r).ID, name)
	if errors.Is(err, errTooMany) {
		return httpx.Errorf(http.StatusConflict, "you have %d tokens already; remove one first", maxTokens)
	}
	if err != nil {
		return err
	}
	httpx.WriteJSON(w, http.StatusCreated, newToken{Token: t, Secret: secret})
	return nil
}

func (h *Handler) deleteToken(w http.ResponseWriter, r *http.Request) error {
	id, err := parseID(r.PathValue("id"))
	if err == nil {
		err = h.store.Delete(r.Context(), account.UserFrom(r).ID, id)
	}
	if errors.Is(err, db.ErrNotFound) || errors.Is(err, errBadID) {
		return httpx.Errorf(http.StatusNotFound, "no such token")
	}
	if err != nil {
		return err
	}
	w.WriteHeader(http.StatusNoContent)
	return nil
}

// ---------- The MCP endpoint ----------

// protocolVersions are the MCP revisions this server speaks, newest first.
var protocolVersions = []string{"2025-11-25", "2025-06-18", "2025-03-26"}

type rpcRequest struct {
	JSONRPC string          `json:"jsonrpc"`
	ID      json.RawMessage `json:"id"`
	Method  string          `json:"method"`
	Params  json.RawMessage `json:"params"`
}

type rpcError struct {
	Code    int    `json:"code"`
	Message string `json:"message"`
}

type rpcResponse struct {
	JSONRPC string          `json:"jsonrpc"`
	ID      json.RawMessage `json:"id"`
	Result  any             `json:"result,omitempty"`
	Error   *rpcError       `json:"error,omitempty"`
}

const (
	codeParse          = -32700
	codeInvalidRequest = -32600
	codeNoMethod       = -32601
	codeInvalidParams  = -32602
	codeInternal       = -32603
)

func (h *Handler) serve(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		// No server-sent event stream: clients then just POST.
		w.Header().Set("Allow", "POST")
		httpx.WriteError(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}
	token, ok := strings.CutPrefix(r.Header.Get("Authorization"), "Bearer ")
	userID, err := int64(0), db.ErrNotFound
	if ok && strings.HasPrefix(token, tokenPrefix) {
		userID, err = h.store.User(r.Context(), token)
	}
	if errors.Is(err, db.ErrNotFound) {
		// Tells clients that can sign in (OAuth) where to start.
		w.Header().Set("WWW-Authenticate", `Bearer realm="wallet", resource_metadata="`+h.origin(r)+resourceMetadataPath+`"`)
		httpx.WriteError(w, http.StatusUnauthorized, "make an access token in the app's Settings → Claude, and send it as a bearer token")
		return
	}
	if err != nil {
		slog.ErrorContext(r.Context(), "mcp auth", "err", err)
		httpx.WriteError(w, http.StatusInternalServerError, "internal error")
		return
	}

	body, err := io.ReadAll(http.MaxBytesReader(w, r.Body, 1<<20))
	if err != nil {
		httpx.WriteError(w, http.StatusRequestEntityTooLarge, "request too large")
		return
	}
	var req rpcRequest
	if err := json.Unmarshal(body, &req); err != nil {
		code := codeParse
		if len(body) > 0 && body[0] == '[' {
			code = codeInvalidRequest // batches were dropped from the protocol
		}
		writeRPC(w, rpcResponse{JSONRPC: "2.0", ID: json.RawMessage("null"), Error: &rpcError{code, "send one JSON-RPC request per POST"}})
		return
	}
	if req.Method == "" {
		// A response from the client: this server never asks for one.
		w.WriteHeader(http.StatusAccepted)
		return
	}
	if len(req.ID) == 0 {
		w.WriteHeader(http.StatusAccepted) // a notification, e.g. notifications/initialized
		return
	}

	res := rpcResponse{JSONRPC: "2.0", ID: req.ID}
	result, rerr := h.dispatch(r, userID, req)
	if rerr != nil {
		res.Error = rerr
	} else {
		res.Result = result
	}
	writeRPC(w, res)
}

func writeRPC(w http.ResponseWriter, res rpcResponse) {
	httpx.WriteJSON(w, http.StatusOK, res)
}

const instructions = `This is the user's personal finance app (a wallet). Amounts are decimal strings in the user's currency. Records ("transactions") are expenses, income or transfers between the user's accounts. Use get_overview first to learn the accounts and categories. To tidy up, list_uncategorized_payees shows records without a category grouped by payee, and categorize_payees files them; prefer the user's existing categories, and ask before creating new ones or deleting any.`

func (h *Handler) dispatch(r *http.Request, userID int64, req rpcRequest) (any, *rpcError) {
	switch req.Method {
	case "initialize":
		var p struct {
			ProtocolVersion string `json:"protocolVersion"`
		}
		json.Unmarshal(req.Params, &p)
		version := protocolVersions[0]
		if slices.Contains(protocolVersions, p.ProtocolVersion) {
			version = p.ProtocolVersion
		}
		return map[string]any{
			"protocolVersion": version,
			"capabilities":    map[string]any{"tools": map[string]any{}},
			"serverInfo":      map[string]any{"name": "wallet", "title": "RudWallet", "version": h.cfg.Version},
			"instructions":    instructions,
		}, nil
	case "ping":
		return map[string]any{}, nil
	case "tools/list":
		return map[string]any{"tools": toolList}, nil
	case "tools/call":
		var p struct {
			Name      string          `json:"name"`
			Arguments json.RawMessage `json:"arguments"`
		}
		if err := json.Unmarshal(req.Params, &p); err != nil {
			return nil, &rpcError{codeInvalidParams, "params must be {name, arguments}"}
		}
		t, ok := tools[p.Name]
		if !ok {
			return nil, &rpcError{codeInvalidParams, "unknown tool: " + p.Name}
		}
		if len(p.Arguments) == 0 || string(p.Arguments) == "null" {
			p.Arguments = json.RawMessage("{}")
		}
		out, err := t.run(&call{r: r, h: h, userID: userID}, p.Arguments)
		var te *toolError
		switch {
		case errors.As(err, &te):
			return toolResult(map[string]string{"error": te.msg}, true), nil
		case err != nil:
			slog.ErrorContext(r.Context(), "mcp tool failed", "tool", p.Name, "err", err)
			return nil, &rpcError{codeInternal, "internal error"}
		}
		return toolResult(out, false), nil
	}
	return nil, &rpcError{codeNoMethod, "unknown method: " + req.Method}
}

// toolResult wraps a tool's answer: as JSON text for every client, and as
// structured content for clients that read it.
func toolResult(v any, isError bool) map[string]any {
	text, _ := json.Marshal(v)
	res := map[string]any{
		"content": []map[string]any{{"type": "text", "text": string(text)}},
		"isError": isError,
	}
	if !isError {
		res["structuredContent"] = v
	}
	return res
}
