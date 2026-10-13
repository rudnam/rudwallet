// Package ai is the Claude features a user turns on with their own Anthropic
// API key: Claude suggests categories for records that have none (e.g. after
// an import), and internal/inbox uses the key to read payment emails when the
// server has no key of its own. Requests are billed to the user's key.
package ai

import (
	"context"
	"database/sql"
	"errors"
	"net/http"
	"strconv"
	"strings"
	"time"

	"github.com/rudnam/wallet-app/internal/account"
	"github.com/rudnam/wallet-app/internal/db"
	"github.com/rudnam/wallet-app/internal/httpx"
	"github.com/rudnam/wallet-app/internal/wallet"
)

// Store keeps each user's API key, encrypted (see sealer). Without the
// server's SECRETS_KEY it can't store keys at all.
type Store struct {
	db     *sql.DB
	sealer *sealer // nil without SECRETS_KEY
}

// NewStore keeps keys encrypted under secret; "" means keys can't be saved.
func NewStore(conn *sql.DB, secret string) *Store {
	s := &Store{db: conn}
	if secret != "" {
		var err error
		if s.sealer, err = newSealer(secret); err != nil {
			panic("ai: " + err.Error()) // only for a key size AES doesn't take
		}
	}
	return s
}

var (
	// ErrNoSecret: the server has no SECRETS_KEY, so it can't keep keys.
	ErrNoSecret = errors.New("no SECRETS_KEY")
	// ErrUnreadable: a saved key can't be decrypted, e.g. SECRETS_KEY changed.
	ErrUnreadable = errors.New("saved API key can't be decrypted")
)

// CanStore reports whether this server can save keys.
func (s *Store) CanStore() bool { return s.sealer != nil }

// Key returns the user's API key, or "" if they have none.
func (s *Store) Key(ctx context.Context, userID int64) (string, error) {
	var sealed []byte
	err := s.db.QueryRowContext(ctx, `SELECT sealed_key FROM ai_keys WHERE user_id = $1`, userID).Scan(&sealed)
	if errors.Is(err, sql.ErrNoRows) {
		return "", nil
	}
	if err != nil {
		return "", err
	}
	if s.sealer == nil {
		return "", ErrUnreadable
	}
	key, err := s.sealer.open(userID, sealed)
	if err != nil {
		return "", ErrUnreadable
	}
	return key, nil
}

// Hint returns the saved key's last four characters, and whether there is one.
func (s *Store) Hint(ctx context.Context, userID int64) (string, bool, error) {
	var hint string
	err := s.db.QueryRowContext(ctx, `SELECT key_hint FROM ai_keys WHERE user_id = $1`, userID).Scan(&hint)
	if errors.Is(err, sql.ErrNoRows) {
		return "", false, nil
	}
	return hint, err == nil, err
}

func (s *Store) SetKey(ctx context.Context, userID int64, key string) error {
	if s.sealer == nil {
		return ErrNoSecret
	}
	hint := ""
	if len(key) >= 4 {
		hint = key[len(key)-4:]
	}
	_, err := s.db.ExecContext(ctx,
		`INSERT INTO ai_keys (user_id, sealed_key, key_hint) VALUES ($1, $2, $3)
		 ON CONFLICT (user_id) DO UPDATE SET sealed_key = EXCLUDED.sealed_key, key_hint = EXCLUDED.key_hint, created_at = now()`,
		userID, s.sealer.seal(userID, key), hint)
	return err
}

func (s *Store) DeleteKey(ctx context.Context, userID int64) error {
	_, err := s.db.ExecContext(ctx, `DELETE FROM ai_keys WHERE user_id = $1`, userID)
	return err
}

type Handler struct {
	store  *Store
	wallet *wallet.Store
	model  string
}

// NewHandler serves the Claude features; model is the Claude model to use.
func NewHandler(st *Store, w *wallet.Store, model string) *Handler {
	return &Handler{store: st, wallet: w, model: model}
}

func (h *Handler) Register(mux *http.ServeMux, requireUser func(http.Handler) http.Handler) {
	route := func(pattern string, fn httpx.HandlerFunc) { mux.Handle(pattern, requireUser(fn)) }
	route("GET /api/ai", h.get)
	route("PUT /api/ai/key", h.setKey)
	route("DELETE /api/ai/key", h.deleteKey)
	route("POST /api/ai/categorize", h.categorize)
	route("POST /api/ai/categorize/apply", h.apply)
}

type status struct {
	// CanSave is false when the server has no SECRETS_KEY to encrypt keys with.
	CanSave bool `json:"can_save"`
	HasKey  bool `json:"has_key"`
	// KeyHint is the key's last four characters, to tell keys apart.
	KeyHint string `json:"key_hint"`
	// KeyUnreadable: the saved key can't be decrypted any more (the
	// server's SECRETS_KEY changed), so it must be added again.
	KeyUnreadable bool `json:"key_unreadable"`
	// Uncategorized counts the confirmed records Claude could sort.
	Uncategorized int `json:"uncategorized"`
}

func (h *Handler) status(ctx context.Context, userID int64) (status, error) {
	st := status{CanSave: h.store.CanStore()}
	var err error
	if st.KeyHint, st.HasKey, err = h.store.Hint(ctx, userID); err != nil {
		return st, err
	}
	if st.HasKey {
		if _, err := h.store.Key(ctx, userID); errors.Is(err, ErrUnreadable) {
			st.KeyUnreadable = true
		} else if err != nil {
			return st, err
		}
	}
	groups, err := h.wallet.UncategorizedGroups(ctx, userID)
	for _, g := range groups {
		st.Uncategorized += g.Count
	}
	return st, err
}

func (h *Handler) get(w http.ResponseWriter, r *http.Request) error {
	st, err := h.status(r.Context(), account.UserFrom(r).ID)
	if err != nil {
		return err
	}
	httpx.WriteJSON(w, http.StatusOK, st)
	return nil
}

func (h *Handler) setKey(w http.ResponseWriter, r *http.Request) error {
	var in struct {
		Key string `json:"key"`
	}
	if err := httpx.ReadJSON(w, r, httpx.MaxBody, &in); err != nil {
		return err
	}
	if !h.store.CanStore() {
		return httpx.Errorf(http.StatusConflict, "this server can't store API keys yet: whoever runs it needs to set SECRETS_KEY")
	}
	key := strings.TrimSpace(in.Key)
	if !strings.HasPrefix(key, "sk-ant-") || len(key) > 300 || strings.ContainsAny(key, " \t\r\n") {
		return httpx.Errorf(http.StatusBadRequest, "that doesn't look like an Anthropic API key; they start with sk-ant-")
	}
	ctx, cancel := context.WithTimeout(r.Context(), 15*time.Second)
	defer cancel()
	if err := checkKey(ctx, key, h.model); err != nil {
		return claudeErr(err)
	}
	userID := account.UserFrom(r).ID
	if err := h.store.SetKey(r.Context(), userID, key); err != nil {
		return err
	}
	st, err := h.status(r.Context(), userID)
	if err != nil {
		return err
	}
	httpx.WriteJSON(w, http.StatusOK, st)
	return nil
}

func (h *Handler) deleteKey(w http.ResponseWriter, r *http.Request) error {
	if err := h.store.DeleteKey(r.Context(), account.UserFrom(r).ID); err != nil {
		return err
	}
	w.WriteHeader(http.StatusNoContent)
	return nil
}

// batch is how many payees one categorize call suggests for.
const batch = 100

type suggestions struct {
	Suggestions []Suggestion `json:"suggestions"`
	// Remaining is how many payees come after this batch.
	Remaining int `json:"remaining"`
}

// categorize suggests a category for the next batch of payees whose records
// have none, skipping the first ?offset= (ones the user chose to leave).
// Nothing changes until the user applies the suggestions.
func (h *Handler) categorize(w http.ResponseWriter, r *http.Request) error {
	userID := account.UserFrom(r).ID
	offset, _ := strconv.Atoi(r.URL.Query().Get("offset"))
	key, err := h.store.Key(r.Context(), userID)
	if errors.Is(err, ErrUnreadable) {
		return httpx.Errorf(http.StatusConflict, "your saved API key can't be read any more; add it again in Settings → Claude")
	}
	if err != nil {
		return err
	}
	if key == "" {
		return httpx.Errorf(http.StatusConflict, "add your Claude API key in Settings first")
	}
	groups, err := h.wallet.UncategorizedGroups(r.Context(), userID)
	if err != nil {
		return err
	}
	groups = groups[min(max(offset, 0), len(groups)):]
	res := suggestions{Suggestions: []Suggestion{}}
	if len(groups) > batch {
		res.Remaining = len(groups) - batch
		groups = groups[:batch]
	}
	if len(groups) > 0 {
		settings, err := h.wallet.Settings(r.Context(), userID)
		if err != nil {
			return err
		}
		cats, err := h.wallet.Categories(r.Context(), userID)
		if err != nil {
			return err
		}
		// Claude can take a while; give this response longer than the
		// server's usual write timeout.
		_ = http.NewResponseController(w).SetWriteDeadline(time.Now().Add(2 * time.Minute))
		ctx, cancel := context.WithTimeout(r.Context(), 90*time.Second)
		defer cancel()
		res.Suggestions, err = suggest(ctx, key, h.model, settings.Currency, cats, groups)
		if err != nil {
			return claudeErr(err)
		}
	}
	httpx.WriteJSON(w, http.StatusOK, res)
	return nil
}

// apply files each payee's uncategorised records under the chosen category.
func (h *Handler) apply(w http.ResponseWriter, r *http.Request) error {
	var in struct {
		Items []wallet.PayeeCategory `json:"items"`
	}
	if err := httpx.ReadJSON(w, r, 1<<20, &in); err != nil {
		return err
	}
	if len(in.Items) > 1000 {
		return httpx.Errorf(http.StatusBadRequest, "at most 1000 payees at a time")
	}
	n, err := h.wallet.Categorize(r.Context(), account.UserFrom(r).ID, in.Items)
	var inv *wallet.InvalidError
	switch {
	case errors.Is(err, db.ErrNotFound):
		return httpx.Errorf(http.StatusNotFound, "no such category")
	case errors.As(err, &inv):
		return httpx.Errorf(http.StatusBadRequest, "%s", inv.Msg)
	case err != nil:
		return err
	}
	httpx.WriteJSON(w, http.StatusOK, map[string]int{"updated": n})
	return nil
}
