package inbox

import (
	"crypto/subtle"
	"errors"
	"io"
	"mime"
	"net/http"
	"strconv"
	"strings"

	"github.com/rudnam/wallet-app/internal/account"
	"github.com/rudnam/wallet-app/internal/db"
	"github.com/rudnam/wallet-app/internal/httpx"
	"github.com/rudnam/wallet-app/internal/inbox/parse"
)

// maxEmail is the largest email accepted. Receipts with their HTML are
// rarely over 200 KB; attachments are not needed.
const maxEmail = 5 << 20

type Config struct {
	// Domain receives mail for the forwarding addresses; empty hides them.
	Domain string
	// Secret authenticates the inbound webhook; empty disables it.
	Secret string
}

type Handler struct {
	cfg     Config
	store   *Store
	service *Service
}

func NewHandler(cfg Config, st *Store, svc *Service) *Handler {
	return &Handler{cfg: cfg, store: st, service: svc}
}

func (h *Handler) Register(mux *http.ServeMux, requireUser func(http.Handler) http.Handler) {
	mux.Handle("GET /api/inbox", requireUser(httpx.HandlerFunc(h.get)))
	mux.Handle("POST /api/inbox/address", requireUser(httpx.HandlerFunc(h.rotate)))
	mux.Handle("POST /api/inbox/emails", requireUser(httpx.HandlerFunc(h.upload)))
	mux.Handle("POST /api/inbox/emails/{id}/dismiss", requireUser(httpx.HandlerFunc(h.dismiss)))
	mux.Handle("POST /api/inbound/email", httpx.HandlerFunc(h.inbound))
}

type inboxView struct {
	// Address is empty when the server has no inbound domain configured.
	Address   string  `json:"address"`
	AIEnabled bool    `json:"ai_enabled"`
	Emails    []Email `json:"emails"`
}

func (h *Handler) view(r *http.Request, token string) (inboxView, error) {
	userID := account.UserFrom(r).ID
	ai, err := h.service.reader(r.Context(), userID)
	if err != nil {
		return inboxView{}, err
	}
	v := inboxView{AIEnabled: ai != nil}
	// A demo gets no address: mail from outside never reaches it.
	if h.cfg.Domain != "" && h.cfg.Secret != "" && !account.UserFrom(r).Demo {
		v.Address = AddressPrefix + token + "@" + h.cfg.Domain
	}
	v.Emails, err = h.store.Recent(r.Context(), userID, 50)
	return v, err
}

func (h *Handler) get(w http.ResponseWriter, r *http.Request) error {
	token, err := h.store.Token(r.Context(), account.UserFrom(r).ID)
	if err != nil {
		return err
	}
	v, err := h.view(r, token)
	if err != nil {
		return err
	}
	httpx.WriteJSON(w, http.StatusOK, v)
	return nil
}

func (h *Handler) rotate(w http.ResponseWriter, r *http.Request) error {
	if err := account.NotInDemo(r); err != nil {
		return err
	}
	token, err := h.store.Rotate(r.Context(), account.UserFrom(r).ID)
	if err != nil {
		return err
	}
	v, err := h.view(r, token)
	if err != nil {
		return err
	}
	httpx.WriteJSON(w, http.StatusOK, v)
	return nil
}

// readEmail reads a raw email body of an accepted content type. Requiring
// one also means a form on another site cannot send it.
func readEmail(w http.ResponseWriter, r *http.Request) ([]byte, error) {
	switch mt, _, _ := mime.ParseMediaType(r.Header.Get("Content-Type")); mt {
	case "message/rfc822", "text/plain":
	default:
		return nil, httpx.Errorf(http.StatusUnsupportedMediaType, "Content-Type must be message/rfc822")
	}
	raw, err := io.ReadAll(http.MaxBytesReader(w, r.Body, maxEmail))
	var tooBig *http.MaxBytesError
	if errors.As(err, &tooBig) {
		return nil, httpx.Errorf(http.StatusRequestEntityTooLarge, "the email is over %d MB", maxEmail>>20)
	}
	return raw, err
}

func (h *Handler) receive(w http.ResponseWriter, r *http.Request, userID int64, raw []byte) error {
	e, err := h.service.Receive(r.Context(), userID, raw)
	if errors.Is(err, parse.ErrNotEmail) {
		return httpx.Errorf(http.StatusBadRequest, "that isn't an email. In Gmail, open the email, choose ⋮ → Show original, and copy everything")
	}
	if err != nil {
		return err
	}
	httpx.WriteJSON(w, http.StatusOK, e)
	return nil
}

// upload lets the user send an email's source themselves, e.g. to test the
// automation before setting up forwarding.
func (h *Handler) upload(w http.ResponseWriter, r *http.Request) error {
	// Not in a demo: reading an email can use the server's Claude key.
	if err := account.NotInDemo(r); err != nil {
		return err
	}
	raw, err := readEmail(w, r)
	if err != nil {
		return err
	}
	return h.receive(w, r, account.UserFrom(r).ID, raw)
}

func (h *Handler) dismiss(w http.ResponseWriter, r *http.Request) error {
	id, err := strconv.ParseInt(r.PathValue("id"), 10, 64)
	if err != nil || id <= 0 {
		return httpx.Errorf(http.StatusNotFound, "no such email")
	}
	err = h.store.Dismiss(r.Context(), account.UserFrom(r).ID, id)
	if errors.Is(err, db.ErrNotFound) {
		return httpx.Errorf(http.StatusNotFound, "no such email")
	}
	if err != nil {
		return err
	}
	w.WriteHeader(http.StatusNoContent)
	return nil
}

// inbound is called by the email provider (e.g. a Cloudflare Email Worker)
// for each message: Authorization: Bearer <INBOUND_SECRET>, the envelope
// recipient in X-Inbound-To (forwarded mail keeps its original To: header),
// and the raw message as the body. Unknown addresses get 404, so the
// provider can bounce the mail.
func (h *Handler) inbound(w http.ResponseWriter, r *http.Request) error {
	if h.cfg.Secret == "" {
		return httpx.Errorf(http.StatusNotFound, "not found")
	}
	got, ok := strings.CutPrefix(r.Header.Get("Authorization"), "Bearer ")
	if !ok || subtle.ConstantTimeCompare([]byte(got), []byte(h.cfg.Secret)) != 1 {
		return httpx.Errorf(http.StatusUnauthorized, "bad credentials")
	}
	userID, err := h.recipient(r)
	if err != nil {
		return err
	}
	raw, err := readEmail(w, r)
	if err != nil {
		return err
	}
	return h.receive(w, r, userID, raw)
}

func (h *Handler) recipient(r *http.Request) (int64, error) {
	to := strings.ToLower(strings.TrimSpace(r.Header.Get("X-Inbound-To")))
	to = strings.Trim(to, "<>")
	local, _, _ := strings.Cut(to, "@")
	local, _, _ = strings.Cut(local, "+") // pay-abc+anything@ still reaches pay-abc
	token, ok := strings.CutPrefix(local, AddressPrefix)
	if !ok || token == "" {
		return 0, httpx.Errorf(http.StatusNotFound, "unknown address")
	}
	id, err := h.store.UserByToken(r.Context(), token)
	if errors.Is(err, db.ErrNotFound) {
		return 0, httpx.Errorf(http.StatusNotFound, "unknown address")
	}
	return id, err
}
