package wallet

import (
	"bytes"
	"context"
	"crypto/sha256"
	"database/sql"
	"encoding/csv"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"math"
	"slices"
	"strconv"
	"strings"
	"time"

	"github.com/rudnam/wallet-app/internal/db"
)

// ImportResult reports what a CSV import did.
type ImportResult struct {
	Imported   int `json:"imported"`
	Duplicates int `json:"duplicates"`
	// Matched counts rows already recorded another way (PayPay import only).
	Matched int `json:"matched"`
	// Skipped counts rows paid in a way the user chose not to import (PayPay only).
	Skipped           int      `json:"skipped"`
	AccountsCreated   int      `json:"accounts_created"`
	CategoriesCreated int      `json:"categories_created"`
	Problems          []string `json:"problems"`
}

const maxProblems = 20

type csvRow struct {
	line      int
	account   string
	category  string
	kind      string // expense or income
	amount    int64
	when      time.Time
	payee     string
	note      string
	transfer  bool
	key       string
	matchedTo int // index of the other half of a transfer, or -1
}

// BudgetBakersAccount is an account named in a BudgetBakers export, and the
// account suggested for its records: the user's of the same name, or a new
// one of NewKind.
type BudgetBakersAccount struct {
	Name      string `json:"name"`
	Count     int    `json:"count"`
	AccountID *int64 `json:"account_id"`
	NewKind   string `json:"new_kind"`
}

// BudgetBakersPreview describes a BudgetBakers export before it is imported.
type BudgetBakersPreview struct {
	Records    int                   `json:"records"`
	From       *time.Time            `json:"from"`
	To         *time.Time            `json:"to"`
	Accounts   []BudgetBakersAccount `json:"accounts"`
	Categories []ImportCategory      `json:"categories"`
	// NewCategories are the default categories the user doesn't have, which
	// a group's records can go to (created on import).
	NewCategories []Category `json:"new_categories"`
	Problems      []string   `json:"problems"`
}

func bbKey(kind, name string) string { return kind + ":" + strings.ToLower(strings.TrimSpace(name)) }

// PreviewBudgetBakers reads a BudgetBakers export and suggests an account
// for each of its accounts and a category for each of its categories.
func (s *Store) PreviewBudgetBakers(ctx context.Context, userID int64, data []byte, zone *time.Location) (BudgetBakersPreview, error) {
	p := BudgetBakersPreview{Accounts: []BudgetBakersAccount{}, Categories: []ImportCategory{}, Problems: []string{}}
	st, err := s.Settings(ctx, userID)
	if err != nil {
		return p, err
	}
	res := ImportResult{Problems: []string{}}
	rows, err := readBudgetBakers(data, st.Currency, zone, &res)
	if err != nil {
		return p, err
	}
	pairTransfers(rows)
	p.Problems = res.Problems
	accounts, err := s.Accounts(ctx, userID)
	if err != nil {
		return p, err
	}
	cats, err := s.Categories(ctx, userID)
	if err != nil {
		return p, err
	}

	acctIndex, catIndex := map[string]int{}, map[string]int{}
	for i, r := range rows {
		if r.matchedTo < 0 || r.matchedTo > i {
			p.Records++ // a transfer's two rows are one record
		}
		if p.From == nil || r.when.Before(*p.From) {
			p.From = &rows[i].when
		}
		if p.To == nil || r.when.After(*p.To) {
			p.To = &rows[i].when
		}
		k := strings.ToLower(r.account)
		if _, ok := acctIndex[k]; !ok {
			a := BudgetBakersAccount{Name: r.account, NewKind: guessAccountKind(r.account)}
			for _, have := range accounts {
				if strings.EqualFold(have.Name, r.account) {
					a.AccountID = &have.ID
					break
				}
			}
			acctIndex[k] = len(p.Accounts)
			p.Accounts = append(p.Accounts, a)
		}
		p.Accounts[acctIndex[k]].Count++

		if r.transfer || r.category == "" {
			continue
		}
		k = bbKey(r.kind, r.category)
		if _, ok := catIndex[k]; !ok {
			g := ImportCategory{Name: r.category, Kind: r.kind}
			suggestBudgetBakers(cats, &g)
			catIndex[k] = len(p.Categories)
			p.Categories = append(p.Categories, g)
		}
		g := &p.Categories[catIndex[k]]
		g.Count++
		g.Total += r.amount
	}
	slices.SortStableFunc(p.Accounts, func(a, b BudgetBakersAccount) int { return b.Count - a.Count })
	sortGroups(p.Categories)
	p.NewCategories = missingDefaults(cats)
	return p, nil
}

// sortGroups puts expenses before income, and the most records first.
func sortGroups(gs []ImportCategory) {
	slices.SortStableFunc(gs, func(a, b ImportCategory) int {
		if a.Kind != b.Kind {
			return strings.Compare(a.Kind, b.Kind) // expense < income
		}
		return b.Count - a.Count
	})
}

// guessAccountKind guesses what kind of account a name is.
func guessAccountKind(name string) string {
	n := strings.ToLower(name)
	has := func(words ...string) bool {
		for _, w := range words {
			if strings.Contains(n, w) {
				return true
			}
		}
		return false
	}
	switch {
	case has("cash", "現金", "wallet", "財布"):
		return "cash"
	case has("card", "カード", "visa", "master", "amex", "jcb"):
		return "credit_card"
	case has("saving", "貯金", "貯蓄", "定期"):
		return "savings"
	case has("bank", "銀行", "ufj", "smbc", "mizuho", "みずほ", "ゆうちょ"):
		return "bank"
	case has("paypay", "suica", "pasmo", "icoca", "nanaco", "waon", "edy", "メルペイ", "楽天ペイ", "d払い", "au pay", "line pay"):
		return "e_money"
	}
	return "other"
}

// ImportBudgetBakers imports the records export of Wallet by BudgetBakers
// (Settings → Export records → CSV). Each row gets a key from its content,
// so importing the same file again adds nothing.
//
// choices (from PreviewBudgetBakers) send the file's accounts and
// categories where the user wants them. An account or category without a
// choice, or with choices nil, is matched by name and created when missing.
//
// Dates without a UTC offset are read in zone, the user's local time.
func (s *Store) ImportBudgetBakers(ctx context.Context, userID int64, data []byte, zone *time.Location, choices *ImportChoices) (ImportResult, error) {
	res := ImportResult{Problems: []string{}}
	st, err := s.Settings(ctx, userID)
	if err != nil {
		return res, err
	}
	rows, err := readBudgetBakers(data, st.Currency, zone, &res)
	if err != nil {
		return res, err
	}
	pairTransfers(rows)
	if choices == nil {
		choices = &ImportChoices{}
	}

	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return res, err
	}
	defer tx.Rollback()

	accounts := map[string]int64{}
	owned := map[string]int64{}
	if err := loadNames(ctx, tx, `SELECT lower(name), id FROM wallet_accounts WHERE user_id = $1`, userID, accounts); err != nil {
		return res, err
	}
	if err := loadNames(ctx, tx, `SELECT id::text, id FROM wallet_accounts WHERE user_id = $1`, userID, owned); err != nil {
		return res, err
	}
	for _, ch := range choices.Accounts {
		if ch.AccountID == 0 {
			continue
		}
		if _, ok := owned[strconv.FormatInt(ch.AccountID, 10)]; !ok {
			return res, db.ErrNotFound
		}
		accounts[strings.ToLower(ch.Name)] = ch.AccountID
	}
	account := func(name string) (int64, error) {
		if id, ok := accounts[strings.ToLower(name)]; ok {
			return id, nil
		}
		var id int64
		err := tx.QueryRowContext(ctx,
			`INSERT INTO wallet_accounts (user_id, name, kind) VALUES ($1, $2, $3) RETURNING id`,
			userID, name, guessAccountKind(name)).Scan(&id)
		accounts[strings.ToLower(name)] = id
		res.AccountsCreated++
		return id, err
	}

	ic, err := newImportCategories(ctx, tx, s, userID)
	if err != nil {
		return res, err
	}
	chosen := map[string]CategoryChoice{}
	for _, ch := range choices.Categories {
		chosen[bbKey(ch.Kind, ch.Name)] = ch
	}
	resolved := map[string]*int64{}
	category := func(kind, name string) (*int64, error) {
		if name == "" {
			return nil, nil
		}
		k := bbKey(kind, name)
		if id, ok := resolved[k]; ok {
			return id, nil
		}
		var id *int64
		if ch, ok := chosen[k]; ok {
			ch.Kind = kind
			if id, err = ic.choose(ch); err != nil {
				return nil, err
			}
		} else {
			n, err := ic.named(kind, name)
			if err != nil {
				return nil, err
			}
			id = &n
		}
		resolved[k] = id
		return id, nil
	}
	for i, r := range rows {
		if r.matchedTo >= 0 && r.matchedTo < i {
			continue // recorded with its other half
		}
		from, err := account(r.account)
		if err != nil {
			return res, err
		}
		t := Transaction{Kind: r.kind, Amount: r.amount, AccountID: from, OccurredAt: r.when, Payee: r.payee, Note: r.note}
		key := r.key
		switch {
		case r.matchedTo >= 0:
			other := rows[r.matchedTo]
			to, err := account(other.account)
			if err != nil {
				return res, err
			}
			if r.kind == "income" { // money arrives here: the other side sent it
				from, to = to, from
			}
			t = Transaction{Kind: "transfer", Amount: r.amount, AccountID: from, ToAccountID: &to, OccurredAt: r.when, Note: r.note}
			key = r.key + "+" + other.key
		case r.transfer:
			t.Note = strings.TrimSpace("Transfer. " + t.Note)
		default:
			if t.CategoryID, err = category(r.kind, r.category); err != nil {
				return res, err
			}
		}
		result, err := tx.ExecContext(ctx,
			`INSERT INTO transactions (user_id, kind, amount, account_id, to_account_id, category_id, occurred_at,
				payee, note, status, source, import_key)
			 VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, 'confirmed', 'import', $10)
			 ON CONFLICT (user_id, import_key) WHERE import_key IS NOT NULL DO NOTHING`,
			userID, t.Kind, t.Amount, t.AccountID, t.ToAccountID, t.CategoryID, t.OccurredAt, t.Payee, t.Note, "bb:"+key)
		if err != nil {
			return res, err
		}
		if n, _ := result.RowsAffected(); n == 0 {
			res.Duplicates++
		} else {
			res.Imported++
		}
	}
	res.CategoriesCreated = ic.created
	return res, tx.Commit()
}

func loadNames(ctx context.Context, tx *sql.Tx, q string, userID int64, into map[string]int64) error {
	rows, err := tx.QueryContext(ctx, q, userID)
	if err != nil {
		return err
	}
	defer rows.Close()
	for rows.Next() {
		var name string
		var id int64
		if err := rows.Scan(&name, &id); err != nil {
			return err
		}
		into[name] = id
	}
	return rows.Err()
}

func readBudgetBakers(data []byte, currency string, zone *time.Location, res *ImportResult) ([]csvRow, error) {
	data = bytes.TrimPrefix(data, []byte("\xEF\xBB\xBF"))
	firstLine, _, _ := bytes.Cut(data, []byte("\n"))
	r := csv.NewReader(bytes.NewReader(data))
	r.Comma = ','
	if bytes.Count(firstLine, []byte(";")) > bytes.Count(firstLine, []byte(",")) {
		r.Comma = ';'
	}
	r.FieldsPerRecord = -1
	header, err := r.Read()
	if err != nil {
		return nil, invalid("the file is empty or not a CSV file")
	}
	col := map[string]int{}
	for i, h := range header {
		col[strings.ToLower(strings.TrimSpace(h))] = i
	}
	for _, need := range []string{"account", "amount", "date"} {
		if _, ok := col[need]; !ok {
			return nil, invalid("this doesn't look like a Wallet export: no %q column", need)
		}
	}
	get := func(rec []string, name string) string {
		if i, ok := col[name]; ok && i < len(rec) {
			return strings.TrimSpace(rec[i])
		}
		return ""
	}
	problem := func(line int, format string, args ...any) {
		if len(res.Problems) < maxProblems {
			res.Problems = append(res.Problems, fmt.Sprintf("line %d: ", line)+fmt.Sprintf(format, args...))
		}
	}

	var rows []csvRow
	seen := map[string]int{}
	for line := 2; ; line++ {
		rec, err := r.Read()
		if errors.Is(err, io.EOF) {
			break
		}
		if err != nil {
			problem(line, "unreadable row")
			continue
		}
		amountText := get(rec, "amount")
		if c := get(rec, "currency"); c != "" && !strings.EqualFold(c, currency) {
			// ref_currency_amount is in the main currency set in Wallet.
			if ref := get(rec, "ref_currency_amount"); ref != "" {
				amountText = ref
			} else {
				problem(line, "amount is in %s, not %s; skipped", c, currency)
				continue
			}
		}
		f, err := strconv.ParseFloat(strings.ReplaceAll(amountText, ",", ""), 64)
		if err != nil {
			problem(line, "amount %q is not a number", amountText)
			continue
		}
		when, err := parseImportDate(get(rec, "date"), zone)
		if err != nil {
			problem(line, "date %q not understood", get(rec, "date"))
			continue
		}
		acct := get(rec, "account")
		if acct == "" {
			problem(line, "no account")
			continue
		}
		// Older exports sign the amount ("Expenses", -1200); newer ones
		// don't ("Expense", 1200), so the type column decides when present.
		kind := "expense"
		switch t := strings.ToLower(get(rec, "type")); {
		case t == "income":
			kind = "income"
		case t == "expense" || t == "expenses":
		case f > 0:
			kind = "income"
		}
		raw := strings.Join(rec, "\x1f")
		seen[raw]++
		sum := sha256.Sum256([]byte(fmt.Sprintf("%s\x00%d", raw, seen[raw])))
		rows = append(rows, csvRow{
			line:      line,
			account:   acct,
			category:  get(rec, "category"),
			kind:      kind,
			amount:    int64(math.Round(math.Abs(f) * math.Pow10(CurrencyExponent(currency)))),
			when:      when,
			payee:     get(rec, "payee"),
			note:      get(rec, "note"),
			transfer:  strings.EqualFold(get(rec, "transfer"), "true"),
			key:       hex.EncodeToString(sum[:12]),
			matchedTo: -1,
		})
	}
	return rows, nil
}

// pairTransfers joins the two rows Wallet exports for each transfer: the
// same amount out of one account and into another at the same moment.
func pairTransfers(rows []csvRow) {
	for i := range rows {
		if !rows[i].transfer || rows[i].matchedTo >= 0 {
			continue
		}
		for j := i + 1; j < len(rows); j++ {
			o := rows[j]
			if o.transfer && o.matchedTo < 0 && o.kind != rows[i].kind && o.amount == rows[i].amount &&
				o.account != rows[i].account && o.when.Equal(rows[i].when) {
				rows[i].matchedTo, rows[j].matchedTo = j, i
				break
			}
		}
	}
}

var importDateLayouts = []string{
	time.RFC3339Nano,
	"2006-01-02T15:04:05.000Z",
	"2006-01-02 15:04:05",
	"2006-01-02T15:04:05",
	"2006-01-02 15:04",
	"2006-01-02",
	"2006/01/02 15:04:05",
	"2006/01/02 15:04",
	"2006/01/02",
	"01/02/2006 15:04",
	"01/02/2006",
}

func parseImportDate(s string, zone *time.Location) (time.Time, error) {
	for _, layout := range importDateLayouts {
		if t, err := time.ParseInLocation(layout, s, zone); err == nil {
			return t, nil
		}
	}
	return time.Time{}, errors.New("unknown date format")
}
