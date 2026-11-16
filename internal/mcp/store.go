package mcp

import (
	"context"
	"crypto/rand"
	"crypto/sha256"
	"database/sql"
	"encoding/base64"
	"errors"
	"time"

	"github.com/lib/pq"
	"github.com/rudnam/wallet-app/internal/db"
)

// tokenPrefix marks the app's MCP tokens, so a leaked one is recognisable.
const tokenPrefix = "wmcp_"

// maxTokens per user: one per device or app is plenty.
const maxTokens = 20

// Store keeps MCP access tokens; only their SHA-256 hashes are stored.
type Store struct{ db *sql.DB }

func NewStore(conn *sql.DB) *Store { return &Store{db: conn} }

type Token struct {
	ID         int64      `json:"id"`
	Name       string     `json:"name"`
	CreatedAt  time.Time  `json:"created_at"`
	LastUsedAt *time.Time `json:"last_used_at"`
	// SignedIn is true for a connection made by signing in from the client
	// (OAuth), false for a token made by hand in Settings.
	SignedIn bool `json:"signed_in"`
	// ReadOnly: the connection can look but not change anything.
	ReadOnly bool `json:"read_only"`
}

// newSecret returns prefix and 256 random bits.
func newSecret(prefix string) string {
	b := make([]byte, 32)
	rand.Read(b)
	return prefix + base64.RawURLEncoding.EncodeToString(b)
}

func hash(token string) []byte {
	h := sha256.Sum256([]byte(token))
	return h[:]
}

var errTooMany = errors.New("too many tokens")

// Create makes a token and returns it; it can't be read again later.
func (s *Store) Create(ctx context.Context, userID int64, name string) (Token, string, error) {
	var n int
	if err := s.db.QueryRowContext(ctx, `SELECT count(*) FROM mcp_tokens WHERE user_id = $1`, userID).Scan(&n); err != nil {
		return Token{}, "", err
	}
	if n >= maxTokens {
		return Token{}, "", errTooMany
	}
	secret := newSecret(tokenPrefix)
	t := Token{Name: name}
	err := s.db.QueryRowContext(ctx,
		`INSERT INTO mcp_tokens (token_hash, user_id, name) VALUES ($1, $2, $3) RETURNING id, created_at`,
		hash(secret), userID, name).Scan(&t.ID, &t.CreatedAt)
	return t, secret, err
}

func (s *Store) List(ctx context.Context, userID int64) ([]Token, error) {
	rows, err := s.db.QueryContext(ctx,
		`SELECT id, name, created_at, last_used_at, client_id IS NOT NULL, read_only FROM mcp_tokens WHERE user_id = $1 ORDER BY id`, userID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []Token{}
	for rows.Next() {
		var t Token
		var used sql.NullTime
		if err := rows.Scan(&t.ID, &t.Name, &t.CreatedAt, &used, &t.SignedIn, &t.ReadOnly); err != nil {
			return nil, err
		}
		if used.Valid {
			t.LastUsedAt = &used.Time
		}
		out = append(out, t)
	}
	return out, rows.Err()
}

func (s *Store) Delete(ctx context.Context, userID, id int64) error {
	res, err := s.db.ExecContext(ctx, `DELETE FROM mcp_tokens WHERE user_id = $1 AND id = $2`, userID, id)
	if err != nil {
		return err
	}
	if n, _ := res.RowsAffected(); n == 0 {
		return db.ErrNotFound
	}
	return nil
}

// User finds whose token this is and whether it may only read, and notes
// that it was used.
func (s *Store) User(ctx context.Context, token string) (userID int64, readOnly bool, err error) {
	err = s.db.QueryRowContext(ctx,
		`UPDATE mcp_tokens SET last_used_at = now()
		 WHERE token_hash = $1 AND (expires_at IS NULL OR expires_at > now()) RETURNING user_id, read_only`, hash(token)).
		Scan(&userID, &readOnly)
	if errors.Is(err, sql.ErrNoRows) {
		return 0, false, db.ErrNotFound
	}
	return userID, readOnly, err
}

// ---------- Sign-in (OAuth) ----------

const (
	clientPrefix  = "wmcpc_"
	codePrefix    = "wmcpa_"
	refreshPrefix = "wmcpr_"
	// accessTTL is how long a token from sign-in works before the client
	// renews it with its refresh token.
	accessTTL = time.Hour
	// codeTTL is how long the client has to exchange an approval for tokens.
	codeTTL = 5 * time.Minute
)

// Client is an MCP client that registered itself.
type Client struct {
	ID           string
	Name         string
	RedirectURIs []string
}

// RegisterClient records a new client and returns its ID.
func (s *Store) RegisterClient(ctx context.Context, name string, redirectURIs []string) (string, error) {
	id := newSecret(clientPrefix)[:len(clientPrefix)+22] // 128 bits: an ID, not a secret
	_, err := s.db.ExecContext(ctx,
		`INSERT INTO mcp_clients (client_id, name, redirect_uris) VALUES ($1, $2, $3)`, id, name, pq.Array(redirectURIs))
	return id, err
}

// Client returns a registered client, or db.ErrNotFound.
func (s *Store) Client(ctx context.Context, id string) (Client, error) {
	c := Client{ID: id}
	err := s.db.QueryRowContext(ctx, `SELECT name, redirect_uris FROM mcp_clients WHERE client_id = $1`, id).
		Scan(&c.Name, pq.Array(&c.RedirectURIs))
	if errors.Is(err, sql.ErrNoRows) {
		return Client{}, db.ErrNotFound
	}
	return c, err
}

// CreateCode records the user's approval of a client and returns the code
// the client exchanges for tokens. challenge is the PKCE S256 challenge;
// readOnly limits the tokens to read tools.
func (s *Store) CreateCode(ctx context.Context, userID int64, clientID, redirectURI, challenge string, readOnly bool) (string, error) {
	var n int
	if err := s.db.QueryRowContext(ctx, `SELECT count(*) FROM mcp_tokens WHERE user_id = $1`, userID).Scan(&n); err != nil {
		return "", err
	}
	if n >= maxTokens {
		return "", errTooMany
	}
	if _, err := s.db.ExecContext(ctx, `DELETE FROM mcp_auth_codes WHERE expires_at < now()`); err != nil {
		return "", err
	}
	code := newSecret(codePrefix)
	_, err := s.db.ExecContext(ctx,
		`INSERT INTO mcp_auth_codes (code_hash, client_id, user_id, redirect_uri, code_challenge, expires_at, read_only)
		 VALUES ($1, $2, $3, $4, $5, now() + $6 * interval '1 second', $7)`,
		hash(code), clientID, userID, redirectURI, challenge, codeTTL.Seconds(), readOnly)
	return code, err
}

// grant is an approval taken back from the database: who approved which
// client, and what the client must prove.
type grant struct {
	clientID, redirectURI, challenge string
	userID                           int64
	readOnly                         bool
}

// TakeCode returns the approval behind code and deletes it, so it is used
// at most once. It returns db.ErrNotFound if the code is unknown or expired.
func (s *Store) TakeCode(ctx context.Context, code string) (grant, error) {
	var g grant
	err := s.db.QueryRowContext(ctx,
		`DELETE FROM mcp_auth_codes WHERE code_hash = $1 AND expires_at > now()
		 RETURNING client_id, user_id, redirect_uri, code_challenge, read_only`, hash(code)).
		Scan(&g.clientID, &g.userID, &g.redirectURI, &g.challenge, &g.readOnly)
	if errors.Is(err, sql.ErrNoRows) {
		return grant{}, db.ErrNotFound
	}
	return g, err
}

// Tokens is what the token endpoint hands a client.
type Tokens struct {
	Access, Refresh string
	// ReadOnly: the connection may only call read tools (the "read" scope).
	ReadOnly bool
}

// Connect makes the tokens for a client the user approved. The connection
// is listed in Settings under the client's name, like a token made by hand.
func (s *Store) Connect(ctx context.Context, userID int64, c Client, readOnly bool) (Tokens, error) {
	t := Tokens{Access: newSecret(tokenPrefix), Refresh: newSecret(refreshPrefix), ReadOnly: readOnly}
	_, err := s.db.ExecContext(ctx,
		`INSERT INTO mcp_tokens (token_hash, user_id, name, client_id, expires_at, refresh_hash, read_only)
		 VALUES ($1, $2, $3, $4, now() + $5 * interval '1 second', $6, $7)`,
		hash(t.Access), userID, c.Name, c.ID, accessTTL.Seconds(), hash(t.Refresh), readOnly)
	return t, err
}

// Refresh swaps a client's refresh token for new tokens; the old ones stop
// working. It returns db.ErrNotFound for an unknown refresh token, or one
// that belongs to another client.
func (s *Store) Refresh(ctx context.Context, clientID, refresh string) (Tokens, error) {
	t := Tokens{Access: newSecret(tokenPrefix), Refresh: newSecret(refreshPrefix)}
	err := s.db.QueryRowContext(ctx,
		`UPDATE mcp_tokens SET token_hash = $1, refresh_hash = $2, expires_at = now() + $3 * interval '1 second'
		 WHERE refresh_hash = $4 AND client_id = $5 RETURNING read_only`,
		hash(t.Access), hash(t.Refresh), accessTTL.Seconds(), hash(refresh), clientID).Scan(&t.ReadOnly)
	if errors.Is(err, sql.ErrNoRows) {
		return Tokens{}, db.ErrNotFound
	}
	return t, err
}
