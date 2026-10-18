package wallet

import (
	"errors"
	"io"
	"mime"
	"net/http"
	"regexp"
	"slices"
	"strconv"
	"strings"
	"time"
	"unicode/utf8"

	"github.com/rudnam/wallet-app/internal/account"
	"github.com/rudnam/wallet-app/internal/db"
	"github.com/rudnam/wallet-app/internal/httpx"
)

const (
	maxName = 60
	maxText = 500
	// maxAmount keeps sums far from overflowing (a trillion yen).
	maxAmount = 1_000_000_000_000
	// maxImport is the largest CSV accepted; years of records are a few MB.
	maxImport = 20 << 20
)

type Handler struct{ store *Store }

func NewHandler(st *Store) *Handler { return &Handler{store: st} }

func (h *Handler) Register(mux *http.ServeMux, requireUser func(http.Handler) http.Handler) {
	route := func(pattern string, fn httpx.HandlerFunc) { mux.Handle(pattern, requireUser(fn)) }
	route("GET /api/wallet", h.overview)
	route("PUT /api/settings", h.updateSettings)

	route("POST /api/accounts", h.createAccount)
	route("PUT /api/accounts/{id}", h.updateAccount)
	route("DELETE /api/accounts/{id}", h.deleteAccount)

	route("GET /api/categories/missing", h.missingCategories)
	route("POST /api/categories", h.createCategory)
	route("PUT /api/categories/{id}", h.updateCategory)
	route("DELETE /api/categories/{id}", h.deleteCategory)

	route("GET /api/transactions", h.listTransactions)
	route("POST /api/transactions", h.createTransaction)
	route("POST /api/transactions/bulk", h.bulkTransactions)
	route("POST /api/transactions/restore", h.restoreTransactions)
	route("PUT /api/transactions/{id}", h.updateTransaction)
	route("DELETE /api/transactions/{id}", h.deleteTransaction)
	route("GET /api/tags", h.listTags)

	route("GET /api/rules", h.rules)
	route("PUT /api/rules/payees", h.setPayeeRule)
	route("DELETE /api/rules/payees", h.deletePayeeRule)
	route("PUT /api/rules/cards", h.setCardRule)
	route("DELETE /api/rules/cards", h.deleteCardRule)
	route("GET /api/stats", h.stats)

	route("GET /api/budgets", h.listBudgets)
	route("POST /api/budgets", h.createBudget)
	route("PUT /api/budgets/{id}", h.updateBudget)
	route("DELETE /api/budgets/{id}", h.deleteBudget)

	route("POST /api/import/budgetbakers", h.importBudgetBakers)
	route("POST /api/import/paypay", h.importPayPay)
}

func userID(r *http.Request) int64 { return account.UserFrom(r).ID }

func pathID(r *http.Request, what string) (int64, error) {
	id, err := strconv.ParseInt(r.PathValue("id"), 10, 64)
	if err != nil || id <= 0 {
		return 0, httpx.Errorf(http.StatusNotFound, "no such %s", what)
	}
	return id, nil
}

// storeErr turns the store's errors into answers for the user.
func storeErr(err error, what string) error {
	var inv *InvalidError
	switch {
	case err == nil:
		return nil
	case errors.Is(err, db.ErrNotFound):
		return httpx.Errorf(http.StatusNotFound, "no such %s", what)
	case errors.Is(err, ErrDuplicateName):
		return httpx.Errorf(http.StatusConflict, "you already have a %s with that name", what)
	case errors.Is(err, ErrCurrencyLocked):
		return httpx.Errorf(http.StatusConflict, "the currency can't change once you have records")
	case errors.As(err, &inv):
		return httpx.Errorf(http.StatusBadRequest, "%s", inv.Msg)
	}
	return err
}

func name(s, what string) (string, error) {
	s = strings.TrimSpace(s)
	if s == "" || utf8.RuneCountInString(s) > maxName {
		return "", httpx.Errorf(http.StatusBadRequest, "a %s name must be 1 to %d characters", what, maxName)
	}
	return s, nil
}

var colorRE = regexp.MustCompile(`^#[0-9a-fA-F]{6}$`)

func color(s, def string) (string, error) {
	if s == "" {
		return def, nil
	}
	if !colorRE.MatchString(s) {
		return "", httpx.Errorf(http.StatusBadRequest, "colour must look like #2f5d50")
	}
	return s, nil
}

// ---------- Overview and settings ----------

func (h *Handler) overview(w http.ResponseWriter, r *http.Request) error {
	// The app opens a new wallet here, so its categories are named in the app's language.
	if err := h.store.SetUp(r.Context(), userID(r), httpx.Language(r)); err != nil {
		return err
	}
	o, err := h.store.Overview(r.Context(), userID(r))
	if err != nil {
		return err
	}
	httpx.WriteJSON(w, http.StatusOK, o)
	return nil
}

var currencyRE = regexp.MustCompile(`^[A-Z]{3}$`)

func (h *Handler) updateSettings(w http.ResponseWriter, r *http.Request) error {
	var in struct {
		Currency         string `json:"currency"`
		AutoConfirm      bool   `json:"auto_confirm"`
		DefaultAccountID *int64 `json:"default_account_id"`
	}
	if err := httpx.ReadJSON(w, r, httpx.MaxBody, &in); err != nil {
		return err
	}
	st := Settings{Currency: strings.ToUpper(strings.TrimSpace(in.Currency)), AutoConfirm: in.AutoConfirm, DefaultAccountID: in.DefaultAccountID}
	if !currencyRE.MatchString(st.Currency) {
		return httpx.Errorf(http.StatusBadRequest, "currency must be a 3-letter code such as JPY")
	}
	if err := storeErr(h.store.UpdateSettings(r.Context(), userID(r), st), "account"); err != nil {
		return err
	}
	st, err := h.store.Settings(r.Context(), userID(r))
	if err != nil {
		return err
	}
	httpx.WriteJSON(w, http.StatusOK, st)
	return nil
}

// ---------- Accounts ----------

type accountInput struct {
	Name           string `json:"name"`
	Kind           string `json:"kind"`
	InitialBalance int64  `json:"initial_balance"`
	Color          string `json:"color"`
	Archived       bool   `json:"archived"`
}

func (in accountInput) account() (Account, error) {
	n, err := name(in.Name, "account")
	if err != nil {
		return Account{}, err
	}
	if !slices.Contains(accountKinds, in.Kind) {
		return Account{}, httpx.Errorf(http.StatusBadRequest, "account type must be one of %s", strings.Join(accountKinds, ", "))
	}
	if in.InitialBalance > maxAmount || in.InitialBalance < -maxAmount {
		return Account{}, httpx.Errorf(http.StatusBadRequest, "starting balance is too large")
	}
	c, err := color(in.Color, "#2f5d50")
	if err != nil {
		return Account{}, err
	}
	return Account{Name: n, Kind: in.Kind, InitialBalance: in.InitialBalance, Color: c, Archived: in.Archived}, nil
}

func (h *Handler) createAccount(w http.ResponseWriter, r *http.Request) error {
	var in accountInput
	if err := httpx.ReadJSON(w, r, httpx.MaxBody, &in); err != nil {
		return err
	}
	a, err := in.account()
	if err != nil {
		return err
	}
	a, err = h.store.CreateAccount(r.Context(), userID(r), a)
	if err := storeErr(err, "account"); err != nil {
		return err
	}
	httpx.WriteJSON(w, http.StatusCreated, a)
	return nil
}

func (h *Handler) updateAccount(w http.ResponseWriter, r *http.Request) error {
	id, err := pathID(r, "account")
	if err != nil {
		return err
	}
	var in accountInput
	if err := httpx.ReadJSON(w, r, httpx.MaxBody, &in); err != nil {
		return err
	}
	a, err := in.account()
	if err != nil {
		return err
	}
	a.ID = id
	a, err = h.store.UpdateAccount(r.Context(), userID(r), a)
	if err := storeErr(err, "account"); err != nil {
		return err
	}
	httpx.WriteJSON(w, http.StatusOK, a)
	return nil
}

func (h *Handler) deleteAccount(w http.ResponseWriter, r *http.Request) error {
	id, err := pathID(r, "account")
	if err != nil {
		return err
	}
	if err := storeErr(h.store.DeleteAccount(r.Context(), userID(r), id), "account"); err != nil {
		return err
	}
	w.WriteHeader(http.StatusNoContent)
	return nil
}

// ---------- Categories ----------

type categoryInput struct {
	Name  string `json:"name"`
	Kind  string `json:"kind"`
	Icon  string `json:"icon"`
	Color string `json:"color"`
}

func (in categoryInput) category() (Category, error) {
	n, err := name(in.Name, "category")
	if err != nil {
		return Category{}, err
	}
	if in.Kind != "expense" && in.Kind != "income" {
		return Category{}, httpx.Errorf(http.StatusBadRequest, "category type must be expense or income")
	}
	icon := strings.TrimSpace(in.Icon)
	if utf8.RuneCountInString(icon) > 8 {
		return Category{}, httpx.Errorf(http.StatusBadRequest, "the icon must be one emoji")
	}
	c, err := color(in.Color, "#66706b")
	if err != nil {
		return Category{}, err
	}
	return Category{Name: n, Kind: in.Kind, Icon: icon, Color: c}, nil
}

// missingCategories lists the default categories the user doesn't have.
func (h *Handler) missingCategories(w http.ResponseWriter, r *http.Request) error {
	cats, err := h.store.MissingDefaults(r.Context(), userID(r))
	if err != nil {
		return err
	}
	httpx.WriteJSON(w, http.StatusOK, cats)
	return nil
}

func (h *Handler) createCategory(w http.ResponseWriter, r *http.Request) error {
	var in categoryInput
	if err := httpx.ReadJSON(w, r, httpx.MaxBody, &in); err != nil {
		return err
	}
	c, err := in.category()
	if err != nil {
		return err
	}
	c, err = h.store.CreateCategory(r.Context(), userID(r), c)
	if err := storeErr(err, "category"); err != nil {
		return err
	}
	httpx.WriteJSON(w, http.StatusCreated, c)
	return nil
}

func (h *Handler) updateCategory(w http.ResponseWriter, r *http.Request) error {
	id, err := pathID(r, "category")
	if err != nil {
		return err
	}
	var in categoryInput
	if err := httpx.ReadJSON(w, r, httpx.MaxBody, &in); err != nil {
		return err
	}
	c, err := in.category()
	if err != nil {
		return err
	}
	c.ID = id
	c, err = h.store.UpdateCategory(r.Context(), userID(r), c)
	if err := storeErr(err, "category"); err != nil {
		return err
	}
	httpx.WriteJSON(w, http.StatusOK, c)
	return nil
}

func (h *Handler) deleteCategory(w http.ResponseWriter, r *http.Request) error {
	id, err := pathID(r, "category")
	if err != nil {
		return err
	}
	if err := storeErr(h.store.DeleteCategory(r.Context(), userID(r), id), "category"); err != nil {
		return err
	}
	w.WriteHeader(http.StatusNoContent)
	return nil
}

// ---------- Records ----------

func (h *Handler) listTransactions(w http.ResponseWriter, r *http.Request) error {
	q := r.URL.Query()
	var f Filter
	var err error
	parseTime := func(key string) time.Time {
		if v := q.Get(key); v != "" && err == nil {
			var t time.Time
			if t, err = time.Parse(time.RFC3339, v); err != nil {
				err = httpx.Errorf(http.StatusBadRequest, "%s must be an RFC 3339 time", key)
			}
			return t
		}
		return time.Time{}
	}
	parseID := func(key string) int64 {
		if v := q.Get(key); v != "" && err == nil {
			var n int64
			if n, err = strconv.ParseInt(v, 10, 64); err != nil {
				err = httpx.Errorf(http.StatusBadRequest, "%s must be a number", key)
			}
			return n
		}
		return 0
	}
	f.From, f.To = parseTime("from"), parseTime("to")
	f.AccountID = parseID("account_id")
	if q.Get("category_id") == "none" {
		f.Uncategorized = true
	} else {
		f.CategoryID = parseID("category_id")
	}
	if err != nil {
		return err
	}
	f.Query = q.Get("q")
	f.Tag = q.Get("tag")
	switch f.Status = q.Get("status"); f.Status {
	case "", "confirmed", "review":
	default:
		return httpx.Errorf(http.StatusBadRequest, "status must be confirmed or review")
	}
	list, err := h.store.List(r.Context(), userID(r), f)
	if err != nil {
		return err
	}
	httpx.WriteJSON(w, http.StatusOK, list)
	return nil
}

type transactionInput struct {
	Kind        string    `json:"kind"`
	Amount      int64     `json:"amount"`
	AccountID   int64     `json:"account_id"`
	ToAccountID *int64    `json:"to_account_id"`
	CategoryID  *int64    `json:"category_id"`
	OccurredAt  time.Time `json:"occurred_at"`
	Payee       string    `json:"payee"`
	Note        string    `json:"note"`
	Status      string    `json:"status"`
	// Remember, when false, confirms a record from a
	// notification without changing the category learned for its shop.
	Remember *bool `json:"remember"`
	// Tags left out keep the record's tags (older apps don't send them).
	Tags []string `json:"tags"`
}

func (in transactionInput) transaction() (Transaction, error) {
	t := Transaction{
		Kind: in.Kind, Amount: in.Amount, AccountID: in.AccountID, ToAccountID: in.ToAccountID,
		CategoryID: in.CategoryID, OccurredAt: in.OccurredAt,
		Payee: strings.TrimSpace(in.Payee), Note: strings.TrimSpace(in.Note), Status: in.Status,
	}
	switch {
	case t.Kind != "expense" && t.Kind != "income" && t.Kind != "transfer":
		return t, httpx.Errorf(http.StatusBadRequest, "type must be expense, income or transfer")
	case t.Amount <= 0 || t.Amount > maxAmount:
		return t, httpx.Errorf(http.StatusBadRequest, "enter an amount above zero")
	case t.OccurredAt.IsZero():
		return t, httpx.Errorf(http.StatusBadRequest, "enter a date")
	case utf8.RuneCountInString(t.Payee) > maxText || utf8.RuneCountInString(t.Note) > maxText:
		return t, httpx.Errorf(http.StatusBadRequest, "payee and note must be at most %d characters", maxText)
	}
	if t.Status == "" {
		t.Status = "confirmed"
	} else if t.Status != "confirmed" && t.Status != "review" {
		return t, httpx.Errorf(http.StatusBadRequest, "status must be confirmed or review")
	}
	var err error
	t.Tags, err = cleanTags(in.Tags)
	return t, err
}

func (h *Handler) createTransaction(w http.ResponseWriter, r *http.Request) error {
	var in transactionInput
	if err := httpx.ReadJSON(w, r, httpx.MaxBody, &in); err != nil {
		return err
	}
	t, err := in.transaction()
	if err != nil {
		return err
	}
	t.Status = "confirmed" // only notifications create records to review
	t, err = h.store.Create(r.Context(), userID(r), t)
	if err := storeErr(err, "record"); err != nil {
		return err
	}
	httpx.WriteJSON(w, http.StatusCreated, t)
	return nil
}

func (h *Handler) updateTransaction(w http.ResponseWriter, r *http.Request) error {
	id, err := pathID(r, "record")
	if err != nil {
		return err
	}
	var in transactionInput
	if err := httpx.ReadJSON(w, r, httpx.MaxBody, &in); err != nil {
		return err
	}
	t, err := in.transaction()
	if err != nil {
		return err
	}
	t.ID = id
	t, err = h.store.Update(r.Context(), userID(r), t, in.Remember == nil || *in.Remember)
	if err := storeErr(err, "record"); err != nil {
		return err
	}
	httpx.WriteJSON(w, http.StatusOK, t)
	return nil
}

func (h *Handler) deleteTransaction(w http.ResponseWriter, r *http.Request) error {
	id, err := pathID(r, "record")
	if err != nil {
		return err
	}
	if err := storeErr(h.store.Delete(r.Context(), userID(r), id), "record"); err != nil {
		return err
	}
	w.WriteHeader(http.StatusNoContent)
	return nil
}

// ---------- Budgets ----------

func (h *Handler) listBudgets(w http.ResponseWriter, r *http.Request) error {
	list, err := h.store.Budgets(r.Context(), userID(r))
	if err != nil {
		return err
	}
	httpx.WriteJSON(w, http.StatusOK, list)
	return nil
}

type budgetInput struct {
	Name       string `json:"name"`
	CategoryID *int64 `json:"category_id"`
	Amount     int64  `json:"amount"`
}

func (in budgetInput) budget() (Budget, error) {
	n, err := name(in.Name, "budget")
	if err != nil {
		return Budget{}, err
	}
	if in.Amount <= 0 || in.Amount > maxAmount {
		return Budget{}, httpx.Errorf(http.StatusBadRequest, "enter a monthly limit above zero")
	}
	return Budget{Name: n, CategoryID: in.CategoryID, Amount: in.Amount}, nil
}

func (h *Handler) createBudget(w http.ResponseWriter, r *http.Request) error {
	var in budgetInput
	if err := httpx.ReadJSON(w, r, httpx.MaxBody, &in); err != nil {
		return err
	}
	b, err := in.budget()
	if err != nil {
		return err
	}
	b, err = h.store.CreateBudget(r.Context(), userID(r), b)
	if err := storeErr(err, "budget"); err != nil {
		return err
	}
	httpx.WriteJSON(w, http.StatusCreated, b)
	return nil
}

func (h *Handler) updateBudget(w http.ResponseWriter, r *http.Request) error {
	id, err := pathID(r, "budget")
	if err != nil {
		return err
	}
	var in budgetInput
	if err := httpx.ReadJSON(w, r, httpx.MaxBody, &in); err != nil {
		return err
	}
	b, err := in.budget()
	if err != nil {
		return err
	}
	b.ID = id
	b, err = h.store.UpdateBudget(r.Context(), userID(r), b)
	if err := storeErr(err, "budget"); err != nil {
		return err
	}
	httpx.WriteJSON(w, http.StatusOK, b)
	return nil
}

func (h *Handler) deleteBudget(w http.ResponseWriter, r *http.Request) error {
	id, err := pathID(r, "budget")
	if err != nil {
		return err
	}
	if err := storeErr(h.store.DeleteBudget(r.Context(), userID(r), id), "budget"); err != nil {
		return err
	}
	w.WriteHeader(http.StatusNoContent)
	return nil
}

// ---------- Import ----------

// importBudgetBakers takes the CSV file as the request body, or JSON:
// {"csv", "accounts": [AccountChoice], "categories": [CategoryChoice]}.
// With ?preview=1 (and the CSV file) it only reads the file and suggests
// where its accounts and categories go. tz_offset is the browser's
// Date.getTimezoneOffset(), in minutes.
func (h *Handler) importBudgetBakers(w http.ResponseWriter, r *http.Request) error {
	offset, _ := strconv.Atoi(r.URL.Query().Get("tz_offset"))
	if offset < -14*60 || offset > 14*60 {
		offset = 0
	}
	zone := time.FixedZone("", -offset*60)
	data, choices, err := readImport(w, r)
	if err != nil {
		return err
	}
	if r.URL.Query().Get("preview") == "1" {
		p, err := h.store.PreviewBudgetBakers(r.Context(), userID(r), data, zone)
		if err := storeErr(err, "record"); err != nil {
			return err
		}
		httpx.WriteJSON(w, http.StatusOK, p)
		return nil
	}
	res, err := h.store.ImportBudgetBakers(r.Context(), userID(r), data, zone, choices)
	if err := storeErr(err, "account"); err != nil {
		return err
	}
	httpx.WriteJSON(w, http.StatusOK, res)
	return nil
}

// importPayPay takes a PayPay transaction history CSV as the request body,
// or JSON: {"csv", "accounts": [AccountChoice], "categories": [CategoryChoice]}.
// With ?preview=1 it only reads the file and suggests an account for each
// way of paying in it and a category for each shop. Otherwise it imports,
// sending each way of paying to the account chosen for it, given either in
// the JSON or as ?account=<id>:<取引方法> (0, or none: the suggestion).
func (h *Handler) importPayPay(w http.ResponseWriter, r *http.Request) error {
	data, choices, err := readImport(w, r)
	if err != nil {
		return err
	}
	if r.URL.Query().Get("preview") == "1" {
		p, err := h.store.PreviewPayPay(r.Context(), userID(r), data)
		if err := storeErr(err, "record"); err != nil {
			return err
		}
		httpx.WriteJSON(w, http.StatusOK, p)
		return nil
	}
	accounts := map[string]int64{}
	for _, v := range r.URL.Query()["account"] {
		idText, method, ok := strings.Cut(v, ":")
		id, err := strconv.ParseInt(idText, 10, 64)
		if !ok || err != nil || id < 0 {
			return httpx.Errorf(http.StatusBadRequest, "account must be <id>:<payment method>")
		}
		accounts[method] = id
	}
	var categories []CategoryChoice
	if choices != nil {
		for _, a := range choices.Accounts {
			if a.AccountID < 0 {
				return httpx.Errorf(http.StatusBadRequest, "no such account")
			}
			accounts[a.Name] = a.AccountID
		}
		categories = choices.Categories
	}
	res, err := h.store.ImportPayPay(r.Context(), userID(r), data, accounts, categories)
	if err := storeErr(err, "account"); err != nil {
		return err
	}
	httpx.WriteJSON(w, http.StatusOK, res)
	return nil
}

// readImport reads an import: the CSV file as the body, or JSON with the
// file and the choices made in its preview (nil without them).
func readImport(w http.ResponseWriter, r *http.Request) ([]byte, *ImportChoices, error) {
	if mt, _, _ := mime.ParseMediaType(r.Header.Get("Content-Type")); mt != "application/json" {
		data, err := readCSV(w, r)
		return data, nil, err
	}
	var in struct {
		CSV string `json:"csv"`
		ImportChoices
	}
	// JSON escapes can double the file's size.
	if err := httpx.ReadJSON(w, r, 2*maxImport, &in); err != nil {
		return nil, nil, err
	}
	if len(in.CSV) > maxImport {
		return nil, nil, httpx.Errorf(http.StatusRequestEntityTooLarge, "the file is over %d MB", maxImport>>20)
	}
	return []byte(in.CSV), &in.ImportChoices, nil
}

// readCSV reads an uploaded CSV file. Requiring the text/csv type means a
// form on another site cannot send it.
func readCSV(w http.ResponseWriter, r *http.Request) ([]byte, error) {
	if mt, _, _ := mime.ParseMediaType(r.Header.Get("Content-Type")); mt != "text/csv" {
		return nil, httpx.Errorf(http.StatusUnsupportedMediaType, "Content-Type must be text/csv")
	}
	data, err := io.ReadAll(http.MaxBytesReader(w, r.Body, maxImport))
	var tooBig *http.MaxBytesError
	if errors.As(err, &tooBig) {
		return nil, httpx.Errorf(http.StatusRequestEntityTooLarge, "the file is over %d MB", maxImport>>20)
	}
	return data, err
}
