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
// each one maps to an account the user picks.

var jst = time.FixedZone("JST", 9*60*60)

// payPayBalance is the 取引方法 of payments from the PayPay balance, which
// charges (チャージ) move money into.
const payPayBalance = "PayPay残高"

// payPayRulePrefix marks the account remembered for a 取引方法 in
// source_rules, apart from the card names that emails use.
const payPayRulePrefix = "paypay-csv:"

// PayPayMethod is one way of paying found in a PayPay export, with the
// account its records go to: an existing one, or a new one to create.
type PayPayMethod struct {
	Method    string `json:"method"`
	Count     int    `json:"count"`
	AccountID *int64 `json:"account_id"`
	NewName   string `json:"new_name"`
	NewKind   string `json:"new_kind"`
}

// PayPayPreview describes a PayPay export before it is imported.
type PayPayPreview struct {
	Records  int            `json:"records"`
	From     *time.Time     `json:"from"`
	To       *time.Time     `json:"to"`
	Methods  []PayPayMethod `json:"methods"`
	Problems []string       `json:"problems"`
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
	p := PayPayPreview{Methods: []PayPayMethod{}}
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
	p.Methods, err = s.payPayMethods(ctx, userID, rows)
	return p, err
}

// ImportPayPay imports a PayPay export. accounts maps a 取引方法 to the
// account for it; a method left out, or mapped to 0, gets the suggested
// account (created if new). The choice is remembered for the next import.
//
// Rows are keyed by their 取引番号, so importing overlapping periods adds
// each payment once. A payment already recorded another way (from its email,
// or by hand) in the same account, for the same amount, within a day, is
// skipped and counted as Matched.
func (s *Store) ImportPayPay(ctx context.Context, userID int64, data []byte, accounts map[string]int64) (ImportResult, error) {
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
	for _, m := range methods {
		id := accounts[m.Method]
		if _, ok := owned[strconv.FormatInt(id, 10)]; id != 0 && !ok {
			return res, db.ErrNotFound
		}
		if id == 0 && m.AccountID != nil {
			id = *m.AccountID
		}
		if id == 0 {
			err := tx.QueryRowContext(ctx,
				`INSERT INTO wallet_accounts (user_id, name, kind) VALUES ($1, $2, $3)
				 ON CONFLICT (user_id, name) DO UPDATE SET name = EXCLUDED.name RETURNING id`,
				userID, m.NewName, m.NewKind).Scan(&id)
			if err != nil {
				return res, err
			}
			res.AccountsCreated++
		}
		account[m.Method] = id
		if _, err := tx.ExecContext(ctx,
			`INSERT INTO source_rules (user_id, source, account_id) VALUES ($1, $2, $3)
			 ON CONFLICT (user_id, source) DO UPDATE SET account_id = EXCLUDED.account_id`,
			userID, payPayRulePrefix+m.Method, id); err != nil {
			return res, err
		}
	}

	matched := []int64{} // existing records, each matched to one row
	for _, r := range rows {
		t := Transaction{Kind: r.kind, Amount: r.amount, AccountID: account[r.method], OccurredAt: r.when, Payee: r.payee, Note: r.note}
		if r.kind == "transfer" {
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
		if t.Kind == "expense" {
			if t.CategoryID, err = s.ruleCategory(ctx, userID, t.Payee); err != nil {
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

	methods := []PayPayMethod{}
	for _, m := range order {
		pm := PayPayMethod{Method: m, Count: counts[m]}
		switch {
		case strings.Contains(m, "残高"):
			pm.NewName, pm.NewKind = "PayPay", "e_money"
		case strings.Contains(m, "カード") || strings.Contains(m, "クレジット"):
			pm.NewName, pm.NewKind = "PayPay Card", "credit_card"
		case strings.Contains(m, "ポイント"):
			pm.NewName, pm.NewKind = "PayPay Points", "e_money"
		case strings.Contains(m, "銀行"):
			pm.NewName, pm.NewKind = clip(m, maxName), "bank"
		default:
			pm.NewName, pm.NewKind = clip(m, maxName), "other"
		}
		if id, ok := learned[payPayRulePrefix+m]; ok {
			pm.AccountID = &id
		} else if id := find("", false, m, pm.NewName); id != nil {
			pm.AccountID = id
		} else if pm.NewKind != "other" {
			// e.g. a card account called "PayPay Gold" for PayPayカード.
			pm.AccountID = find(pm.NewKind, true, "paypay", m)
		}
		methods = append(methods, pm)
	}
	return methods, nil
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
		row := payPayRow{when: when, payee: clip(get(rec, "取引先"), maxName), method: clip(get(rec, "取引方法"), maxName)}
		switch {
		case out > 0 && in == 0:
			row.kind, row.amount = "expense", out
		case in > 0 && out == 0:
			row.kind, row.amount = "income", in
			if strings.Contains(what, "チャージ") && row.method != payPayBalance {
				row.kind = "transfer"
			}
		default:
			problem(line, "no amount, or both paid and received")
			continue
		}
		if row.method == "" {
			row.method = payPayBalance
		}
		if row.payee == "お店" { // a physical PayPay Card doesn't name the shop
			row.payee = ""
		}

		var note []string
		if what != "" && what != "支払い" {
			note = append(note, what)
		}
		if plan := get(rec, "支払い区分"); plan != "" && plan != "一回払い" && plan != "ー回払い" {
			note = append(note, plan)
		}
		if abroad, cur := get(rec, "海外出金金額"), get(rec, "通貨"); abroad != "" && cur != "" {
			n := cur + " " + abroad
			if rate := get(rec, "変換レート"); rate != "" {
				n += " at " + rate
			}
			if country := get(rec, "利用国"); country != "" {
				n += " (" + country + ")"
			}
			note = append(note, n)
		}
		row.note = clip(strings.Join(note, ". "), maxText)

		// The 取引番号 identifies the payment; a payment split over several
		// rows (e.g. points and balance) shares it, so count repeats.
		id := get(rec, "取引番号")
		if id == "" {
			sum := sha256.Sum256([]byte(strings.Join(rec, "\x1f")))
			id = hex.EncodeToString(sum[:12])
		}
		seen[id]++
		row.key = fmt.Sprintf("pp:%s:%d", id, seen[id])
		rows = append(rows, row)
	}
	return rows, nil
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
