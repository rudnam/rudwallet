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
	"regexp"
	"slices"
	"strconv"
	"strings"
	"time"
	"unicode/utf8"

	"github.com/lib/pq"

	"github.com/rudnam/wallet-app/internal/db"
)

// PayPay's transaction history export (PayPay app → 取引履歴 → download):
// one row per transaction, amounts in yen, times in Japan time.
//
//	取引日,出金金額（円）,入金金額（円）,海外出金金額,通貨,変換レート（円）,利用国,取引内容,取引先,取引方法,支払い区分,利用者,取引番号
//	2024/06/03 11:28,1000,-,-,-,-,-,支払い,〇〇カレー,PayPay残高,-,本人,123456789012345
//
// 取引方法 is what paid (PayPay残高, PayPayカード, a bank for a charge…);
// each one maps to an account the user picks. A payment split over several
// methods lists each with its share: PayPayポイント（686円）、PayPayカード（4,794円）.
//
// With the app in English the export has English headers and values:
//
//	Date & Time,Amount Outgoing (Yen),Amount Incoming (Yen),Amount Outgoing Overseas,Currency,Exchange Rate (Yen),Country Paid In,Transaction Type,Business Name,Method,Payment Option,User,Transaction ID
//	2026/10/11 09:55:15,"5,480",-,-,-,-,-,Payment,Yahoo!ショッピング,"PayPay Point (686yen), Credit VISA 8602 (4,794yen)",-,-,05089832727741308928
//
// The English headers are read as their Japanese ones, and PayPay's own
// balance and points as PayPay残高 and PayPayポイント, so an account chosen
// for them is remembered whichever language the export is in.

var jst = time.FixedZone("JST", 9*60*60)

// payPayEnglish maps the English export's headers, without their unit, to
// the Japanese ones.
var payPayEnglish = map[string]string{
	"Date & Time":              "取引日",
	"Amount Outgoing":          "出金金額",
	"Amount Incoming":          "入金金額",
	"Amount Outgoing Overseas": "海外出金金額",
	"Currency":                 "通貨",
	"Exchange Rate":            "変換レート",
	"Country Paid In":          "利用国",
	"Transaction Type":         "取引内容",
	"Business Name":            "取引先",
	"Method":                   "取引方法",
	"Payment Option":           "支払い区分",
	"User":                     "利用者",
	"Transaction ID":           "取引番号",
}

// payPayMethodsJa names PayPay's own ways of paying as the Japanese export does.
var payPayMethodsJa = map[string]string{
	"PayPay Balance": payPayBalance,
	"PayPay Point":   "PayPayポイント",
	"PayPay Points":  "PayPayポイント",
}

// payPaySplit matches one share of a payment split over several methods,
// "PayPay Point (686yen)" or "PayPayポイント（686円）", with the separator after it.
var payPaySplit = regexp.MustCompile(`\s*([^,、]+?)\s*[（(]([\d,]+)\s*(?:yen|円)[）)]\s*(?:[,、]|$)`)

// cardDigits finds the last digits of a card in a method, e.g. 8602 in
// "PayPay Card Gold VISA 8602".
var cardDigits = regexp.MustCompile(`(\d{4})$`)

// payPayBalance is the 取引方法 of payments from the PayPay balance, which
// charges (チャージ) move money into.
const payPayBalance = "PayPay残高"

// payPayRulePrefix marks the account remembered for a 取引方法 in
// source_rules, apart from the card names that notifications use.
const payPayRulePrefix = "paypay-csv:"

// SkipAccount, as the account for a way of paying, leaves its records out
// of the import (e.g. PayPay points, for someone who doesn't track them).
const SkipAccount = -1

// PayPayMethod is one way of paying found in a PayPay export, with the
// account its records go to: an existing one, or a new one to create.
type PayPayMethod struct {
	Method    string `json:"method"`
	Count     int    `json:"count"`
	AccountID *int64 `json:"account_id"`
	NewName   string `json:"new_name"`
	NewKind   string `json:"new_kind"`
	// Skip: the user chose last time not to import this way of paying.
	Skip bool `json:"skip"`
}

// PayPayPreview describes a PayPay export before it is imported.
type PayPayPreview struct {
	Records int            `json:"records"`
	From    *time.Time     `json:"from"`
	To      *time.Time     `json:"to"`
	Methods []PayPayMethod `json:"methods"`
	// Categories groups the payments by shop (records without one, by what
	// they are), each with a suggested category: the one chosen for the
	// shop before, else a guess from its name.
	Categories []ImportCategory `json:"categories"`
	// NewCategories are the default categories the user doesn't have.
	NewCategories []Category `json:"new_categories"`
	Problems      []string   `json:"problems"`
}

type payPayRow struct {
	when   time.Time
	kind   string // expense, income, or transfer (a charge into the balance)
	amount int64
	payee  string
	method string
	note   string
	key    string
}

// PreviewPayPay reads a PayPay export and suggests an account for each way
// of paying in it.
func (s *Store) PreviewPayPay(ctx context.Context, userID int64, data []byte) (PayPayPreview, error) {
	p := PayPayPreview{Methods: []PayPayMethod{}, Categories: []ImportCategory{}}
	res := ImportResult{Problems: []string{}}
	rows, err := s.readPayPayFor(ctx, userID, data, &res)
	if err != nil {
		return p, err
	}
	p.Records, p.Problems = len(rows), res.Problems
	for _, r := range rows {
		if p.From == nil || r.when.Before(*p.From) {
			p.From = &r.when
		}
		if p.To == nil || r.when.After(*p.To) {
			p.To = &r.when
		}
	}
	if p.Methods, err = s.payPayMethods(ctx, userID, rows); err != nil {
		return p, err
	}
	cats, err := s.Categories(ctx, userID)
	if err != nil {
		return p, err
	}
	rules, err := s.payeeRules(ctx, userID)
	if err != nil {
		return p, err
	}
	index := map[string]int{}
	for _, r := range rows {
		label := recordLabel(r.payee, r.note)
		if r.kind == "transfer" || PayeeKey(label) == "" {
			continue
		}
		k := groupKey(r.kind, label)
		if _, ok := index[k]; !ok {
			g := ImportCategory{Name: label, Kind: r.kind}
			if id := rules.match(r.payee); id != nil && kindOf(cats, *id) == r.kind {
				g.CategoryID, g.Learned = id, true
			} else if d := guessCategory(r.kind, r.payee, r.note); d != "" {
				suggestNamed(cats, &g, d)
			}
			index[k] = len(p.Categories)
			p.Categories = append(p.Categories, g)
		}
		g := &p.Categories[index[k]]
		g.Count++
		g.Total += r.amount
	}
	// Shops not seen before first: they are the ones to decide on.
	sortGroups(p.Categories)
	slices.SortStableFunc(p.Categories, func(a, b ImportCategory) int {
		switch {
		case a.Learned == b.Learned:
			return 0
		case b.Learned:
			return -1
		}
		return 1
	})
	p.NewCategories = missingDefaults(cats)
	return p, nil
}

func kindOf(cats []Category, id int64) string {
	for _, c := range cats {
		if c.ID == id {
			return c.Kind
		}
	}
	return ""
}

// payeeRuleSet is the user's payee_rules, matched as ruleCategory does.
type payeeRuleSet struct {
	byKey map[string]int
	ids   []int64
}

func (s *Store) payeeRules(ctx context.Context, userID int64) (payeeRuleSet, error) {
	rs := payeeRuleSet{byKey: map[string]int{}}
	rows, err := s.db.QueryContext(ctx, `SELECT payee_key, category_id FROM payee_rules WHERE user_id = $1`, userID)
	if err != nil {
		return rs, err
	}
	defer rows.Close()
	for rows.Next() {
		var key string
		var id int64
		if err := rows.Scan(&key, &id); err != nil {
			return rs, err
		}
		rs.byKey[key] = len(rs.ids)
		rs.ids = append(rs.ids, id)
	}
	return rs, rows.Err()
}

func (rs payeeRuleSet) match(payee string) *int64 {
	key := PayeeKey(payee)
	if key == "" {
		return nil
	}
	if i, ok := matchRule(rs.byKey, key); ok {
		return &rs.ids[i]
	}
	return nil
}

// ImportPayPay imports a PayPay export. accounts maps a 取引方法 to the
// account for it; a method left out, or mapped to 0, gets the suggested
// account (created if new), and one mapped to SkipAccount isn't imported
// (as is one left out that was skipped last time).
// The choice is remembered for the next import. A charge into the balance
// from a method that isn't imported is income to the balance; one into a
// balance that isn't imported is an expense from the method that paid it.
//
// categories (groups from PreviewPayPay) give records a category; a shop's
// expense category is remembered for its next payments, as when the user
// sorts records. A record whose group has no choice gets its shop's
// remembered category, if an expense, as before previews had them.
//
// Rows are keyed by their 取引番号, so importing overlapping periods adds
// each payment once. A payment already recorded another way (from its
// notification, or by hand) in the same account, for the same amount, within a day, is
// skipped and counted as Matched.
func (s *Store) ImportPayPay(ctx context.Context, userID int64, data []byte, accounts map[string]int64, categories []CategoryChoice) (ImportResult, error) {
	res := ImportResult{Problems: []string{}}
	rows, err := s.readPayPayFor(ctx, userID, data, &res)
	if err != nil {
		return res, err
	}
	methods, err := s.payPayMethods(ctx, userID, rows)
	if err != nil {
		return res, err
	}

	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return res, err
	}
	defer tx.Rollback()

	owned := map[string]int64{}
	if err := loadNames(ctx, tx, `SELECT id::text, id FROM wallet_accounts WHERE user_id = $1`, userID, owned); err != nil {
		return res, err
	}
	account := map[string]int64{}
	skip := map[string]bool{}
	created := map[string]int64{} // new accounts by name, which methods can share
	for _, m := range methods {
		id, chosen := accounts[m.Method]
		if id == SkipAccount || !chosen && m.Skip {
			skip[m.Method] = true
			if _, err := tx.ExecContext(ctx,
				`DELETE FROM source_rules WHERE user_id = $1 AND source = $2`, userID, payPayRulePrefix+m.Method); err != nil {
				return res, err
			}
			if _, err := tx.ExecContext(ctx,
				`INSERT INTO import_skips (user_id, source) VALUES ($1, $2) ON CONFLICT DO NOTHING`,
				userID, payPayRulePrefix+m.Method); err != nil {
				return res, err
			}
			continue
		}
		if _, ok := owned[strconv.FormatInt(id, 10)]; id != 0 && !ok {
			return res, db.ErrNotFound
		}
		if id == 0 && m.AccountID != nil {
			id = *m.AccountID
		}
		if id == 0 {
			id = created[m.NewName]
		}
		if id == 0 {
			err := tx.QueryRowContext(ctx,
				`INSERT INTO wallet_accounts (user_id, name, kind) VALUES ($1, $2, $3)
				 ON CONFLICT (user_id, name) DO UPDATE SET name = EXCLUDED.name RETURNING id`,
				userID, m.NewName, m.NewKind).Scan(&id)
			if err != nil {
				return res, err
			}
			created[m.NewName] = id
			res.AccountsCreated++
		}
		account[m.Method] = id
		if _, err := tx.ExecContext(ctx,
			`DELETE FROM import_skips WHERE user_id = $1 AND source = $2`, userID, payPayRulePrefix+m.Method); err != nil {
			return res, err
		}
		if _, err := tx.ExecContext(ctx,
			`INSERT INTO source_rules (user_id, source, account_id) VALUES ($1, $2, $3)
			 ON CONFLICT (user_id, source) DO UPDATE SET account_id = EXCLUDED.account_id`,
			userID, payPayRulePrefix+m.Method, id); err != nil {
			return res, err
		}
	}

	ic, err := newImportCategories(ctx, tx, s, userID)
	if err != nil {
		return res, err
	}
	chosen := map[string]CategoryChoice{}
	for _, ch := range categories {
		chosen[groupKey(ch.Kind, ch.Name)] = ch
	}
	resolved := map[string]*int64{}
	learned := map[string]bool{}
	category := func(r payPayRow) (*int64, error) {
		k := groupKey(r.kind, recordLabel(r.payee, r.note))
		ch, ok := chosen[k]
		if !ok {
			if r.kind != "expense" {
				return nil, nil
			}
			return s.ruleCategory(ctx, userID, r.payee)
		}
		id, done := resolved[k]
		if !done {
			ch.Kind = r.kind
			var err error
			if id, err = ic.choose(ch); err != nil {
				return nil, err
			}
			resolved[k] = id
		}
		// Rules have no kind, so only expenses teach one: income from a
		// shop (a refund, points) mustn't change its payments' category.
		if key := PayeeKey(r.payee); id != nil && r.kind == "expense" && key != "" && !learned[key] {
			learned[key] = true
			if _, err := tx.ExecContext(ctx,
				`INSERT INTO payee_rules (user_id, payee_key, category_id) VALUES ($1, $2, $3)
				 ON CONFLICT (user_id, payee_key) DO UPDATE SET category_id = EXCLUDED.category_id`,
				userID, key, *id); err != nil {
				return nil, err
			}
		}
		return id, nil
	}

	matched := []int64{} // existing records, each matched to one row
	for _, r := range rows {
		t := Transaction{Kind: r.kind, Amount: r.amount, AccountID: account[r.method], OccurredAt: r.when, Payee: r.payee, Note: r.note}
		switch {
		case r.kind == "transfer" && skip[r.method] && skip[payPayBalance], r.kind != "transfer" && skip[r.method]:
			res.Skipped++
			continue
		case r.kind == "transfer" && skip[r.method]: // money in from somewhere not tracked
			t.Kind, t.AccountID = "income", account[payPayBalance]
		case r.kind == "transfer" && skip[payPayBalance]: // money out to somewhere not tracked
			t.Kind = "expense"
		case r.kind == "transfer":
			to := account[payPayBalance]
			t.ToAccountID = &to
			if to == t.AccountID { // both sides mapped to one account
				t.Kind, t.ToAccountID = "income", nil
			}
		}

		var exists bool
		if err := tx.QueryRowContext(ctx,
			`SELECT EXISTS (SELECT 1 FROM transactions WHERE user_id = $1 AND import_key = $2)`,
			userID, r.key).Scan(&exists); err != nil {
			return res, err
		}
		if exists {
			res.Duplicates++
			continue
		}
		if t.Kind != "transfer" {
			var id int64
			err := tx.QueryRowContext(ctx,
				`SELECT id FROM transactions
				 WHERE user_id = $1 AND kind = $2 AND amount = $3 AND account_id = $4
				   AND occurred_at BETWEEN $5::timestamptz - interval '1 day' AND $5::timestamptz + interval '1 day'
				   AND (import_key IS NULL OR import_key NOT LIKE 'pp:%')
				   AND NOT (id = ANY($6::bigint[]))
				 ORDER BY abs(extract(epoch FROM occurred_at - $5::timestamptz)) LIMIT 1`,
				userID, t.Kind, t.Amount, t.AccountID, t.OccurredAt, pq.Array(matched)).Scan(&id)
			if err == nil {
				matched = append(matched, id)
				res.Matched++
				continue
			}
			if !errors.Is(err, sql.ErrNoRows) {
				return res, err
			}
		}
		if t.Kind != "transfer" {
			if t.CategoryID, err = category(r); err != nil {
				return res, err
			}
		}
		if _, err := tx.ExecContext(ctx,
			`INSERT INTO transactions (user_id, kind, amount, account_id, to_account_id, category_id, occurred_at,
				payee, note, status, source, import_key)
			 VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, 'confirmed', 'import', $10)`,
			userID, t.Kind, t.Amount, t.AccountID, t.ToAccountID, t.CategoryID, t.OccurredAt, t.Payee, t.Note, r.key); err != nil {
			return res, err
		}
		res.Imported++
	}
	res.CategoriesCreated = ic.created
	return res, tx.Commit()
}

// payPayMethods lists each 取引方法 with a suggested account: the one chosen
// last time, else an account that fits by kind and name, else a new one.
func (s *Store) payPayMethods(ctx context.Context, userID int64, rows []payPayRow) ([]PayPayMethod, error) {
	counts := map[string]int{}
	var order []string
	add := func(m string) {
		if counts[m] == 0 {
			order = append(order, m)
		}
		counts[m]++
	}
	for _, r := range rows {
		add(r.method)
		if r.kind == "transfer" {
			add(payPayBalance)
		}
	}
	slices.SortStableFunc(order, func(a, b string) int { return counts[b] - counts[a] })

	type acct struct {
		id         int64
		name, kind string
	}
	var all []acct
	q, err := s.db.QueryContext(ctx,
		`SELECT id, name, kind FROM wallet_accounts WHERE user_id = $1 ORDER BY archived, id`, userID)
	if err != nil {
		return nil, err
	}
	defer q.Close()
	for q.Next() {
		var a acct
		if err := q.Scan(&a.id, &a.name, &a.kind); err != nil {
			return nil, err
		}
		all = append(all, a)
	}
	if err := q.Err(); err != nil {
		return nil, err
	}
	learned := map[string]int64{}
	if err := loadRules(ctx, s, userID, learned); err != nil {
		return nil, err
	}
	skipped := map[string]bool{}
	if err := loadSkips(ctx, s, userID, skipped); err != nil {
		return nil, err
	}
	// find returns the first account of the kind ("" for any) whose name
	// is one of names, or with contains, includes one of them.
	find := func(kind string, contains bool, names ...string) *int64 {
		for _, a := range all {
			if kind != "" && a.kind != kind {
				continue
			}
			for _, n := range names {
				if strings.EqualFold(a.name, n) || contains && strings.Contains(strings.ToLower(a.name), strings.ToLower(n)) {
					return &a.id
				}
			}
		}
		return nil
	}

	// A card is listed by its last digits, as "PayPay Card VISA 6385" when
	// paid with the PayPay Card and "Credit VISA 6385" when charged to it as
	// a credit card: both are suggested the same new account.
	cardName := map[string]string{}
	for _, m := range order {
		if d := cardDigits.FindString(m); d != "" && isCard(m) && (cardName[d] == "" || isPayPayCard(m)) {
			cardName[d] = "Card " + d
			if isPayPayCard(m) {
				cardName[d] = "PayPay Card " + d
			}
		}
	}

	methods := []PayPayMethod{}
	for _, m := range order {
		pm := PayPayMethod{Method: m, Count: counts[m], Skip: skipped[payPayRulePrefix+m]}
		digits := cardDigits.FindString(m)
		lower := strings.ToLower(m)
		switch {
		case strings.Contains(m, "残高"):
			pm.NewName, pm.NewKind = "PayPay", "e_money"
		case isCard(m) && digits != "":
			pm.NewName, pm.NewKind = cardName[digits], "credit_card"
		case isCard(m):
			pm.NewName, pm.NewKind = "PayPay Card", "credit_card"
		case strings.Contains(m, "ポイント"):
			pm.NewName, pm.NewKind = "PayPay Points", "e_money"
		case strings.Contains(m, "銀行") || strings.Contains(lower, "bank"):
			pm.NewName, pm.NewKind = clip(m, maxName), "bank"
		default:
			pm.NewName, pm.NewKind = clip(m, maxName), "other"
		}
		if id, ok := learned[payPayRulePrefix+m]; ok {
			pm.AccountID = &id
		} else if id := find("", false, m, pm.NewName); id != nil {
			pm.AccountID = id
		} else if pm.NewKind == "credit_card" && digits != "" && find(pm.NewKind, true, digits) != nil {
			// e.g. a card account called "Gold 8602" for "Credit VISA 8602".
			pm.AccountID = find(pm.NewKind, true, digits)
		} else if pm.NewKind == "credit_card" && strings.Contains(lower, "gold") && find(pm.NewKind, true, "gold") != nil {
			pm.AccountID = find(pm.NewKind, true, "gold")
		} else if pm.NewKind != "other" {
			// e.g. a card account called "PayPay Gold" for PayPayカード.
			pm.AccountID = find(pm.NewKind, true, "paypay", m)
		}
		methods = append(methods, pm)
	}
	return methods, nil
}

func isCard(m string) bool {
	lower := strings.ToLower(m)
	return strings.Contains(m, "カード") || strings.Contains(m, "クレジット") ||
		strings.Contains(lower, "card") || strings.Contains(lower, "credit")
}

func isPayPayCard(m string) bool {
	return strings.Contains(m, "PayPayカード") || strings.Contains(strings.ToLower(m), "paypay card")
}

func loadRules(ctx context.Context, s *Store, userID int64, into map[string]int64) error {
	rows, err := s.db.QueryContext(ctx,
		`SELECT source, account_id FROM source_rules WHERE user_id = $1 AND source LIKE 'paypay-csv:%'`, userID)
	if err != nil {
		return err
	}
	defer rows.Close()
	for rows.Next() {
		var src string
		var id int64
		if err := rows.Scan(&src, &id); err != nil {
			return err
		}
		into[src] = id
	}
	return rows.Err()
}

func loadSkips(ctx context.Context, s *Store, userID int64, into map[string]bool) error {
	rows, err := s.db.QueryContext(ctx,
		`SELECT source FROM import_skips WHERE user_id = $1 AND source LIKE 'paypay-csv:%'`, userID)
	if err != nil {
		return err
	}
	defer rows.Close()
	for rows.Next() {
		var src string
		if err := rows.Scan(&src); err != nil {
			return err
		}
		into[src] = true
	}
	return rows.Err()
}

func (s *Store) readPayPayFor(ctx context.Context, userID int64, data []byte, res *ImportResult) ([]payPayRow, error) {
	st, err := s.Settings(ctx, userID)
	if err != nil {
		return nil, err
	}
	if st.Currency != "JPY" {
		return nil, invalid("PayPay amounts are in yen, and your main currency is %s", st.Currency)
	}
	return readPayPay(data, res)
}

func readPayPay(data []byte, res *ImportResult) ([]payPayRow, error) {
	data = bytes.TrimPrefix(data, []byte("\xEF\xBB\xBF"))
	if !utf8.Valid(data) {
		return nil, invalid("the file isn't UTF-8 text; use the CSV file as PayPay sent it, not one saved again by Excel")
	}
	r := csv.NewReader(bytes.NewReader(data))
	r.FieldsPerRecord = -1
	header, err := r.Read()
	if err != nil {
		return nil, invalid("the file is empty or not a CSV file")
	}
	// Headers are matched without their unit, e.g. 出金金額（円） as 出金金額.
	col := map[string]int{}
	for i, h := range header {
		h = strings.TrimSpace(h)
		if j := strings.IndexAny(h, "（("); j > 0 {
			h = strings.TrimSpace(h[:j])
		}
		if ja, ok := payPayEnglish[h]; ok {
			h = ja
		}
		col[h] = i
	}
	for _, need := range []string{"取引日", "出金金額", "入金金額", "取引方法"} {
		if _, ok := col[need]; !ok {
			return nil, invalid("this doesn't look like a PayPay transaction history: no %s column", need)
		}
	}
	get := func(rec []string, name string) string {
		if i, ok := col[name]; ok && i < len(rec) {
			if v := strings.TrimSpace(rec[i]); v != "-" && v != "ー" {
				return v
			}
		}
		return ""
	}
	problem := func(line int, format string, args ...any) {
		if len(res.Problems) < maxProblems {
			res.Problems = append(res.Problems, fmt.Sprintf("line %d: ", line)+fmt.Sprintf(format, args...))
		}
	}

	var rows []payPayRow
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
		if len(rec) == 1 && strings.TrimSpace(rec[0]) == "" {
			continue
		}
		when, err := parseImportDate(get(rec, "取引日"), jst)
		if err != nil {
			problem(line, "date %q not understood", get(rec, "取引日"))
			continue
		}
		out, errOut := yen(get(rec, "出金金額"))
		in, errIn := yen(get(rec, "入金金額"))
		if errOut != nil || errIn != nil {
			problem(line, "amount not understood")
			continue
		}
		what := get(rec, "取引内容")
		row := payPayRow{when: when, payee: clip(get(rec, "取引先"), maxName)}
		switch {
		case out > 0 && in == 0:
			row.kind, row.amount = "expense", out
		case in > 0 && out == 0:
			row.kind, row.amount = "income", in
		default:
			problem(line, "no amount, or both paid and received")
			continue
		}
		if row.payee == "お店" { // a physical PayPay Card doesn't name the shop
			row.payee = ""
		}
		charge := strings.Contains(what, "チャージ") || strings.EqualFold(what, "Top-Up")

		var note []string
		if what != "" && what != "支払い" && what != "Payment" {
			note = append(note, what)
		}
		if plan := get(rec, "支払い区分"); plan != "" && plan != "一回払い" && plan != "ー回払い" && plan != "One-time Payment" {
			note = append(note, plan)
		}
		if abroad, cur := get(rec, "海外出金金額"), get(rec, "通貨"); abroad != "" && cur != "" {
			n := cur + " " + abroad
			if rate := get(rec, "変換レート"); rate != "" && cur != "JPY" {
				// 164.36400000 as 164.364, keeping two decimals.
				for dot := strings.Index(rate, "."); dot >= 0 && len(rate)-dot > 3 && strings.HasSuffix(rate, "0"); {
					rate = rate[:len(rate)-1]
				}
				n += " at " + rate
			}
			if country := get(rec, "利用国"); country != "" {
				n += " (" + country + ")"
			}
			note = append(note, n)
		}
		row.note = clip(strings.Join(note, ". "), maxText)

		// The 取引番号 identifies the payment; a payment split over several
		// methods (e.g. points and card) shares it, so count repeats.
		id := get(rec, "取引番号")
		if id == "" {
			sum := sha256.Sum256([]byte(strings.Join(rec, "\x1f")))
			id = hex.EncodeToString(sum[:12])
		}
		parts, ok := payPayMethodParts(get(rec, "取引方法"), row.amount)
		if !ok {
			problem(line, "the amounts paid by each method don't add up to %d", row.amount)
			continue
		}
		for _, p := range parts {
			r := row
			r.method, r.amount = p.method, p.amount
			if charge && r.kind == "income" && r.method != payPayBalance {
				r.kind = "transfer"
			}
			seen[id]++
			r.key = fmt.Sprintf("pp:%s:%d", id, seen[id])
			rows = append(rows, r)
		}
	}
	return rows, nil
}

type payPayPart struct {
	method string
	amount int64
}

// payPayMethodParts splits a 取引方法 into each method and the amount it
// paid. A single method pays the whole total; shares that don't add up to
// the total are not ok.
func payPayMethodParts(s string, total int64) ([]payPayPart, bool) {
	name := func(m string) string {
		if ja, ok := payPayMethodsJa[m]; ok {
			return ja
		}
		if m == "" {
			return payPayBalance
		}
		return clip(m, maxName)
	}
	all := payPaySplit.FindAllStringSubmatchIndex(s, -1)
	if len(all) < 2 {
		return []payPayPart{{name(s), total}}, true
	}
	var parts []payPayPart
	var sum int64
	end := 0
	for _, m := range all {
		if m[0] != end { // text between shares: not a list of shares
			return []payPayPart{{name(s), total}}, true
		}
		end = m[1]
		n, err := yen(s[m[4]:m[5]])
		if err != nil {
			return nil, false
		}
		if n > 0 {
			parts = append(parts, payPayPart{name(s[m[2]:m[3]]), n})
		}
		sum += n
	}
	if end != len(s) {
		return []payPayPart{{name(s), total}}, true
	}
	if sum != total {
		return nil, false
	}
	return parts, true
}

func yen(s string) (int64, error) {
	s = strings.NewReplacer(",", "", "¥", "", "￥", "", "円", "").Replace(s)
	if s == "" {
		return 0, nil
	}
	n, err := strconv.ParseInt(s, 10, 64)
	if err != nil || n < 0 || n > maxAmount {
		return 0, errors.New("bad amount")
	}
	return n, nil
}

func clip(s string, n int) string {
	if utf8.RuneCountInString(s) <= n {
		return s
	}
	return string([]rune(s)[:n])
}
