// Package widget serves the Android app's home screen widgets
// (android/app/src/main/java/com/rudnam/wallet/widget): one summary of the
// month that they all draw from. The widgets sign in with the app's own
// session token (see internal/account/app.go).
package widget

import (
	"net/http"
	"time"

	"github.com/rudnam/wallet-app/internal/account"
	"github.com/rudnam/wallet-app/internal/httpx"
	"github.com/rudnam/wallet-app/internal/wallet"
)

type Handler struct{ wallet *wallet.Store }

func NewHandler(ws *wallet.Store) *Handler { return &Handler{wallet: ws} }

func (h *Handler) Register(mux *http.ServeMux, requireUser func(http.Handler) http.Handler) {
	mux.Handle("GET /api/widget", requireUser(httpx.HandlerFunc(h.summary)))
}

// Budget is a monthly budget and what has gone against it.
type Budget struct {
	Name   string `json:"name"`
	Amount int64  `json:"amount"`
	Spent  int64  `json:"spent"`
}

// Summary is what the widgets show: "This month" all but Budgets, and
// "Budgets" those.
type Summary struct {
	Currency         string `json:"currency"`
	CurrencyExponent int    `json:"currency_exponent"`
	// Spent is confirmed expenses in the month.
	Spent int64 `json:"spent"`
	// Balance is the total of the accounts that aren't archived.
	Balance int64 `json:"balance"`
	// Budget is the first budget for all expenses, if there is one.
	Budget *Budget `json:"budget"`
	// Budgets are all of them, in the order the web app lists them.
	Budgets     []Budget `json:"budgets"`
	ReviewCount int      `json:"review_count"`
}

// summary answers the widgets, which send the app's session token and the
// month as from and to, in the phone's time zone like the web app.
func (h *Handler) summary(w http.ResponseWriter, r *http.Request) error {
	userID := account.UserFrom(r).ID
	from, err1 := time.Parse(time.RFC3339, r.URL.Query().Get("from"))
	to, err2 := time.Parse(time.RFC3339, r.URL.Query().Get("to"))
	if err1 != nil || err2 != nil || !to.After(from) {
		return httpx.Errorf(http.StatusBadRequest, "from and to must be RFC 3339 times, from first")
	}

	ctx := r.Context()
	o, err := h.wallet.Overview(ctx, userID)
	if err != nil {
		return err
	}
	budgets, err := h.wallet.Budgets(ctx, userID)
	if err != nil {
		return err
	}
	spending, err := h.wallet.Spending(ctx, userID, from, to)
	if err != nil {
		return err
	}
	s := Summary{Currency: o.Settings.Currency, CurrencyExponent: o.Settings.CurrencyExponent, ReviewCount: o.ReviewCount}
	for _, a := range o.Accounts {
		if !a.Archived {
			s.Balance += a.Balance
		}
	}
	for _, n := range spending {
		s.Spent += n
	}
	s.Budgets = []Budget{}
	for _, b := range budgets {
		spent := s.Spent
		if b.CategoryID != nil {
			spent = spending[*b.CategoryID]
		} else if s.Budget == nil {
			s.Budget = &Budget{Name: b.Name, Amount: b.Amount, Spent: spent}
		}
		s.Budgets = append(s.Budgets, Budget{Name: b.Name, Amount: b.Amount, Spent: spent})
	}
	httpx.WriteJSON(w, http.StatusOK, s)
	return nil
}
