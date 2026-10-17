package server

import (
	"net/http"

	"github.com/rudnam/wallet-app/internal/httpx"
)

// legacy answers what older Android builds still ask for, so they show an
// empty section rather than an error. Email automation (GET /api/inbox) and
// users' own Claude API keys (GET /api/ai) were removed; the app now reads
// payment notifications, and Claude connects over MCP.
func legacy(mux *http.ServeMux, requireUser func(http.Handler) http.Handler) {
	mux.Handle("GET /api/inbox", requireUser(httpx.HandlerFunc(func(w http.ResponseWriter, r *http.Request) error {
		httpx.WriteJSON(w, http.StatusOK, map[string]any{"address": "", "ai_enabled": false, "emails": []any{}})
		return nil
	})))
	mux.Handle("GET /api/ai", requireUser(httpx.HandlerFunc(func(w http.ResponseWriter, r *http.Request) error {
		httpx.WriteJSON(w, http.StatusOK, map[string]any{
			"can_save": false, "has_key": false, "key_hint": "", "key_unreadable": false, "uncategorized": 0,
		})
		return nil
	})))
}
