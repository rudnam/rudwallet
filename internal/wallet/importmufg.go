package wallet

import (
	"bytes"
	"context"
	"crypto/sha256"
	"database/sql"
	"encoding/csv"
	"encoding/hex"
	"errors"
	"io"
	"slices"
	"strconv"
	"strings"
	"time"
	"unicode"
	"unicode/utf8"

	"github.com/lib/pq"
	"golang.org/x/text/encoding/japanese"
	"golang.org/x/text/unicode/norm"
	"golang.org/x/text/width"

	"github.com/rudnam/wallet-app/internal/db"
)

// MUFG's statement export (三菱UFJダイレクト → 入出金明細 → CSV): one row
// per movement of money in the account, oldest first, in Shift_JIS (CP932).
//
//	日付,摘要,摘要内容,支払い金額,預かり金額,差引残高,メモ,未資金化区分,入払区分
//	"2026/10/14","カ－ド","","10,000","","12,007","","",""
//	"2026/10/25","振込１","シービーエステクノ（カ","","250,000","262,007","","",""
//
// Dates are days (Japan time) without padding. Each row has an amount paid
// out or one paid in, and 差引残高, the balance after it, which proves no
// row is missing (verifyMUFGBalances).
//
// The text mixes full- and half-width letters, several kinds of dash
// (カ－ド with U+FF0D, カードＣ１ with U+30FC) and padding, so rules match
// it normalized (normalizeMUFG); records keep it as written.
//
// Rows are recorded by rules (classifyMUFG) that know the bank's own
// wording. What they can't know, such as who pays the user's salary, is
// the user's to choose in the preview, and remembered: the account for each
// counterpart (cash, cards, PayPay) in source_rules under mufgRulePrefix,
// and the category for each payer or payee in payee_rules under the same
// prefix.

// mufgRulePrefix marks what an MUFG import remembers in source_rules
// (account for a role) and payee_rules (category for a payee), apart from
// what payments teach the app.
const mufgRulePrefix = "mufg-csv:"

// mufgKeyPrefix starts the import_key of each imported row.
const mufgKeyPrefix = "mufg:"

// Roles: the accounts an MUFG statement moves money between. MUFGBank is
// the account the statement is for.
const (
	MUFGBank       = "bank"
	MUFGCash       = "cash"        // ATM withdrawals and deposits
	MUFGPayPayCard = "paypay-card" // PayPayカード's monthly debit
	MUFGSMBCCard   = "smbc-card"   // 三井住友カード's monthly debit
	MUFGPayPay     = "paypay"      // charges to, and withdrawals from, the PayPay balance
)

var mufgRoles = []string{MUFGBank, MUFGCash, MUFGPayPayCard, MUFGSMBCCard, MUFGPayPay}

var mufgHeader = []string{"日付", "摘要", "摘要内容", "支払い金額", "預かり金額", "差引残高"}

// mufgRow is one row of the statement.
type mufgRow struct {
	line            int
	day             time.Time // midnight, Japan time
	summary, detail string    // 摘要 and 摘要内容 as written, trimmed
	memo            string
	out, in         int64
	balance         int64
	key             string
}

// mufgRecord is how a row would be recorded.
type mufgRecord struct {
	kind string // expense, income or transfer
	// role is the other side of a transfer: money goes from the bank to it
	// (out) or from it to the bank (in).
	role string
	// group names the payee for its category, for expenses and income.
	group string
	// suggest is the default category to suggest when nothing was learned.
	suggest string
	// rule is false when no rule knew the row: it is recorded without a
	// category, for the user to sort.
	rule bool
}

// normalizeMUFG folds widths, turns every dash into ー, upper-cases letters
// and drops spaces, so "カ－ド", "カード" and "ｶｰﾄﾞ" are one spelling.
// Half-width ﾄﾞ folds to ト and a combining mark, which NFC makes ド.
func normalizeMUFG(s string) string {
	var b strings.Builder
	for _, r := range norm.NFC.String(width.Fold.String(s)) {
		switch {
		case unicode.IsSpace(r):
			continue
		case r == '-' || r == 'ー' || r == '−' || r == '－' || r == 'ｰ' || (r >= 0x2010 && r <= 0x2015):
			r = 'ー'
		}
		b.WriteRune(unicode.ToUpper(r))
	}
	return b.String()
}

// tidyMUFG is text as shown on a record: half-width letters, single spaces.
func tidyMUFG(s string) string {
	return strings.Join(strings.Fields(norm.NFC.String(width.Fold.String(s))), " ")
}

// classifyMUFG reads what a row is. The first rule that matches wins; a row
// none match is recorded by its direction, without a category (rule false).
func classifyMUFG(r mufgRow) mufgRecord {
	s, d := normalizeMUFG(r.summary), normalizeMUFG(r.detail)
	out := r.out > 0
	expenseOrIncome := func(rule bool, suggest string) mufgRecord {
		rec := mufgRecord{kind: "income", group: mufgPayee(r), suggest: suggest, rule: rule}
		if out {
			rec.kind = "expense"
		}
		return rec
	}
	switch {
	// カード: MUFG's own ATMs; カードC1 and the like: convenience-store ATMs.
	case strings.HasPrefix(s, "カード"), strings.HasPrefix(s, "ゆうちょ"):
		return mufgRecord{kind: "transfer", role: MUFGCash, rule: true}
	case strings.Contains(s, "手数料"):
		return expenseOrIncome(true, "fee")
	case strings.Contains(s, "口座振替") && strings.Contains(d, "PAYPAYカード"):
		return mufgRecord{kind: "transfer", role: MUFGPayPayCard, rule: true}
	case strings.Contains(s, "口座振替") && strings.Contains(d, "ミツイスミトモカード"):
		return mufgRecord{kind: "transfer", role: MUFGSMBCCard, rule: true}
	case strings.Contains(s, "口座振替") && strings.Contains(d, "ペイペイ"):
		return mufgRecord{kind: "transfer", role: MUFGPayPay, rule: true}
	case strings.Contains(s, "振込") && !out && (strings.Contains(d, "PAYPAY") || strings.Contains(d, "ペイペイ")):
		return mufgRecord{kind: "transfer", role: MUFGPayPay, rule: true}
	case strings.Contains(s, "現金還元"): // D現金還元: the debit card's cashback
		return expenseOrIncome(true, "cashback")
	}
	// Anything else, such as a 振込 from a person or an employer: a
	// category the user picked for the payee before (mufgPayeeRules) still
	// gives it one.
	return expenseOrIncome(false, "")
}

// mufgPayee is who a row was paid to or by, as its records name them.
func mufgPayee(r mufgRow) string {
	if d := tidyMUFG(r.detail); d != "" {
		return clip(d, maxName)
	}
	return clip(tidyMUFG(r.summary), maxName)
}

// readMUFG reads a statement: Shift_JIS as MUFG sends it, or UTF-8 if it
// was converted. Rows come oldest first, and their balances must add up.
func readMUFG(data []byte) ([]mufgRow, error) {
	data = bytes.TrimPrefix(data, []byte("\xEF\xBB\xBF"))
	if !utf8.Valid(data) {
		var err error
		if data, err = japanese.ShiftJIS.NewDecoder().Bytes(data); err != nil {
			return nil, invalid("the file is neither Shift_JIS nor UTF-8 text")
		}
	}
	r := csv.NewReader(bytes.NewReader(data))
	r.FieldsPerRecord = -1
	header, err := r.Read()
	if err != nil {
		return nil, invalid("the file is empty or not a CSV file")
	}
	col := map[string]int{}
	for i, h := range header {
		col[strings.TrimSpace(h)] = i
	}
	for _, need := range mufgHeader {
		if _, ok := col[need]; !ok {
			return nil, invalid("this doesn't look like an MUFG statement (入出金明細): no %s column", need)
		}
	}
	get := func(rec []string, name string) string {
		if i, ok := col[name]; ok && i < len(rec) {
			return strings.TrimSpace(rec[i])
		}
		return ""
	}

	var rows []mufgRow
	for line := 2; ; line++ {
		rec, err := r.Read()
		if errors.Is(err, io.EOF) {
			break
		}
		if err != nil {
			return nil, invalid("line %d: unreadable row", line)
		}
		if len(rec) == 1 && strings.TrimSpace(rec[0]) == "" {
			continue
		}
		day, err := time.ParseInLocation("2006/1/2", width.Fold.String(get(rec, "日付")), jst)
		if err != nil {
			return nil, invalid("line %d: date %q not understood", line, get(rec, "日付"))
		}
		row := mufgRow{line: line, day: day, summary: get(rec, "摘要"), detail: get(rec, "摘要内容"), memo: get(rec, "メモ")}
		var errs [3]error
		row.out, errs[0] = yen(width.Fold.String(get(rec, "支払い金額")))
		row.in, errs[1] = yen(width.Fold.String(get(rec, "預かり金額")))
		row.balance, errs[2] = signedYen(width.Fold.String(get(rec, "差引残高")))
		if errs[0] != nil || errs[1] != nil || errs[2] != nil {
			return nil, invalid("line %d: amount not understood", line)
		}
		if (row.out > 0) == (row.in > 0) {
			return nil, invalid("line %d: a row must have either 支払い金額 or 預かり金額", line)
		}
		row.key = mufgKey(row)
		rows = append(rows, row)
	}
	if len(rows) == 0 {
		return nil, invalid("the statement has no rows")
	}
	if rows[0].day.After(rows[len(rows)-1].day) { // newest first
		slices.Reverse(rows)
	}
	return rows, verifyMUFGBalances(rows)
}

// mufgKey identifies a row across exports: its day, normalized text,
// amounts and the balance after it. The balance keeps two equal ATM
// withdrawals on one day apart.
func mufgKey(r mufgRow) string {
	sum := sha256.Sum256([]byte(strings.Join([]string{
		r.day.Format(time.DateOnly), normalizeMUFG(r.summary), normalizeMUFG(r.detail),
		strconv.FormatInt(r.out, 10), strconv.FormatInt(r.in, 10), strconv.FormatInt(r.balance, 10),
	}, "\x1f")))
	return mufgKeyPrefix + hex.EncodeToString(sum[:16])
}

// verifyMUFGBalances checks that each row's balance is the one before it,
// less what was paid out, plus what came in. A gap means rows are missing
// or were edited, and the import would leave the account wrong.
func verifyMUFGBalances(rows []mufgRow) error {
	for i := 1; i < len(rows); i++ {
		want := rows[i-1].balance - rows[i].out + rows[i].in
		if rows[i].balance != want {
			return invalid("line %d: the balance %s should be %s after the row before it, so rows are missing or were changed; export the statement again",
				rows[i].line, strconv.FormatInt(rows[i].balance, 10), strconv.FormatInt(want, 10))
		}
	}
	return nil
}

func signedYen(s string) (int64, error) {
	if rest, ok := strings.CutPrefix(s, "-"); ok {
		n, err := yen(rest)
		return -n, err
	}
	return yen(s)
}

// ---------- Preview ----------

// MUFGAccount is one role in a statement and the account its records use:
// an existing one, or a new one to create.
type MUFGAccount struct {
	Role      string `json:"role"`
	Count     int    `json:"count"`
	AccountID *int64 `json:"account_id"`
	NewName   string `json:"new_name"`
	NewKind   string `json:"new_kind"`
	// Candidates are the user's other accounts that also look like this
	// role (e.g. two bank accounts named after MUFG), for the user to check.
	Candidates []int64 `json:"candidates"`
}

// MUFGRow is how one statement row would be (or was) recorded.
type MUFGRow struct {
	Line    int    `json:"line"`
	Date    string `json:"date"`
	Summary string `json:"summary"`
	Detail  string `json:"detail"`
	Out     int64  `json:"out"`
	In      int64  `json:"in"`
	Balance int64  `json:"balance"`
	Kind    string `json:"kind"`
	// FromRole and ToRole are the accounts a transfer moves money between;
	// an expense or income has FromRole bank.
	FromRole string `json:"from_role"`
	ToRole   string `json:"to_role,omitempty"`
	// Group is the payee whose category the row gets (ImportCategory.Name).
	Group string `json:"group,omitempty"`
	// Rule is false when no rule knew the row; it is recorded without a
	// category unless one is chosen for its payee.
	Rule bool `json:"rule"`
	// Status: new (to record), imported (by an earlier import of the same
	// row) or matched (already recorded another way, e.g. from PayPay's
	// notification, so not recorded again).
	Status string `json:"status"`
	// MatchedID is the record a matched row is, and MatchedAccountID the
	// account it has on the bank's side, which may not be the bank's.
	MatchedID        *int64 `json:"matched_id,omitempty"`
	MatchedAccountID *int64 `json:"matched_account_id,omitempty"`
}

// MUFGBalance compares the bank account in the app with the statement.
type MUFGBalance struct {
	AccountID *int64 `json:"account_id"`
	From      string `json:"from"`
	To        string `json:"to"`
	// Opening is the balance before the statement's first row; Closing,
	// after its last.
	Opening int64 `json:"opening"`
	Closing int64 `json:"closing"`
	// App is the account's balance at the end of the statement's last day,
	// counting what the import adds; Difference is Closing - App.
	App        int64 `json:"app"`
	Difference int64 `json:"difference"`
	// Misplaced is what statement rows already recorded in another account
	// (matched there) add up to, in (+) less out (-): moving those records
	// to this account changes Difference by -Misplaced.
	Misplaced int64 `json:"misplaced"`
	// InitialBalance is the account's starting balance, and
	// SuggestedInitialBalance the one that leaves only Misplaced apart: what
	// the account's records before the statement are missing.
	InitialBalance          int64 `json:"initial_balance"`
	SuggestedInitialBalance int64 `json:"suggested_initial_balance"`
	// Adjusted is how much an import with matchBalance changed the starting
	// balance by.
	Adjusted int64 `json:"adjusted"`
}

// MUFGPreview is the dry run of an import: how every row would be
// recorded, and the accounts and categories to choose.
type MUFGPreview struct {
	Records       int              `json:"records"`
	New           int              `json:"new"`
	Accounts      []MUFGAccount    `json:"accounts"`
	Categories    []ImportCategory `json:"categories"`
	NewCategories []Category       `json:"new_categories"`
	Rows          []MUFGRow        `json:"rows"`
	Balance       MUFGBalance      `json:"balance"`
}

// MUFGResult is what an import did.
type MUFGResult struct {
	ImportResult
	Rows    []MUFGRow   `json:"rows"`
	Balance MUFGBalance `json:"balance"`
}

// PreviewMUFG reads a statement and shows how each row would be recorded,
// with the accounts and categories suggested. Nothing is saved.
func (s *Store) PreviewMUFG(ctx context.Context, userID int64, data []byte) (MUFGPreview, error) {
	p := MUFGPreview{Accounts: []MUFGAccount{}, Categories: []ImportCategory{}, Rows: []MUFGRow{}}
	rows, err := s.readMUFGFor(ctx, userID, data)
	if err != nil {
		return p, err
	}
	recs := make([]mufgRecord, len(rows))
	for i, r := range rows {
		recs[i] = classifyMUFG(r)
	}
	if p.Accounts, err = s.mufgAccounts(ctx, userID, recs); err != nil {
		return p, err
	}
	account := map[string]*int64{}
	for _, a := range p.Accounts {
		account[a.Role] = a.AccountID
	}
	cats, err := s.Categories(ctx, userID)
	if err != nil {
		return p, err
	}
	learned, err := s.mufgPayeeRules(ctx, userID)
	if err != nil {
		return p, err
	}
	index := map[string]int{}
	for _, rec := range recs {
		if rec.kind == "transfer" {
			continue
		}
		k := groupKey(rec.kind, rec.group)
		if _, ok := index[k]; !ok {
			index[k] = len(p.Categories)
			p.Categories = append(p.Categories, suggestMUFG(cats, learned, rec))
		}
	}

	// Preview in a transaction that is rolled back, so matching works as
	// the import's does.
	tx, err := s.db.BeginTx(ctx, &sql.TxOptions{ReadOnly: true})
	if err != nil {
		return p, err
	}
	defer tx.Rollback()
	m := mufgMatcher{ctx: ctx, tx: tx, userID: userID, matched: []int64{}}
	var added []mufgAdded
	for i, r := range rows {
		out := describeMUFG(r, recs[i])
		var bank int64
		if account[MUFGBank] != nil {
			bank = *account[MUFGBank]
		}
		var other *int64
		if recs[i].role != "" {
			other = account[recs[i].role]
		}
		if err := m.status(&out, r, recs[i], bank, other); err != nil {
			return p, err
		}
		if out.Status == "new" {
			p.New++
			added = append(added, mufgAdded{r, recs[i]})
		}
		p.Rows = append(p.Rows, out)
	}
	for i := range p.Categories {
		g := &p.Categories[i]
		for j, rec := range recs {
			if rec.kind != "transfer" && groupKey(rec.kind, rec.group) == groupKey(g.Kind, g.Name) {
				g.Count++
				g.Total += rows[j].out + rows[j].in
			}
		}
	}
	p.Records = len(rows)
	p.NewCategories = missingDefaults(cats)
	p.Balance, err = mufgBalance(ctx, tx, userID, account[MUFGBank], rows, added, p.Rows)
	return p, err
}

// suggestMUFG suggests a category for a payee: the one chosen for it in an
// earlier import, else one that fits what the rule found, else a guess
// from its name.
func suggestMUFG(cats []Category, learned map[string]int64, rec mufgRecord) ImportCategory {
	g := ImportCategory{Name: rec.group, Kind: rec.kind}
	if id, ok := learned[PayeeKey(rec.group)]; ok && kindOf(cats, id) == rec.kind {
		g.CategoryID, g.Learned = &id, true
		return g
	}
	switch rec.suggest {
	case "fee":
		if id := categoryLike(cats, rec.kind, "fee", "手数料"); id != nil {
			g.CategoryID = id
		} else {
			suggestNamed(cats, &g, "Other")
		}
	case "cashback":
		if id := categoryLike(cats, rec.kind, "cashback", "還元", "rewards"); id != nil {
			g.CategoryID = id
		} else {
			suggestNamed(cats, &g, "Rewards & Cashback")
		}
	default:
		if d := guessCategory(rec.kind, rec.group, ""); d != "" {
			suggestNamed(cats, &g, d)
		}
	}
	return g
}

// categoryLike is the first category of the kind whose name has one of words.
func categoryLike(cats []Category, kind string, words ...string) *int64 {
	for _, c := range cats {
		name := strings.ToLower(c.Name)
		for _, w := range words {
			if c.Kind == kind && strings.Contains(name, w) {
				return &c.ID
			}
		}
	}
	return nil
}

func describeMUFG(r mufgRow, rec mufgRecord) MUFGRow {
	out := MUFGRow{
		Line: r.line, Date: r.day.Format(time.DateOnly), Summary: r.summary, Detail: r.detail,
		Out: r.out, In: r.in, Balance: r.balance, Kind: rec.kind, FromRole: MUFGBank, Group: rec.group, Rule: rec.rule,
	}
	if rec.kind == "transfer" {
		out.ToRole = rec.role
		if r.in > 0 {
			out.FromRole, out.ToRole = rec.role, MUFGBank
		}
	}
	return out
}

// mufgMatcher finds rows already recorded: by an earlier import (the same
// key), or another way. Each existing record matches one row at most.
type mufgMatcher struct {
	ctx     context.Context
	tx      *sql.Tx
	userID  int64
	matched []int64 // never nil: pq sends a nil slice as NULL, which matches nothing
}

// status sets out.Status, and the matched record. bank is the bank's
// account (0 if new) and other the counterpart's (nil if new).
//
// A record is the same payment when it is of the same kind and amount,
// within a day either side of the row's date, and has the same counterpart:
// for a transfer, the account on the other side (so a PayPay charge
// recorded from its notification matches whichever bank account it was put
// in), and for an expense or income, the bank's account.
func (m *mufgMatcher) status(out *MUFGRow, r mufgRow, rec mufgRecord, bank int64, other *int64) error {
	var exists bool
	if err := m.tx.QueryRowContext(m.ctx,
		`SELECT EXISTS (SELECT 1 FROM transactions WHERE user_id = $1 AND import_key = $2)`,
		m.userID, r.key).Scan(&exists); err != nil {
		return err
	}
	if exists {
		out.Status = "imported"
		return nil
	}
	out.Status = "new"
	q := `SELECT id, account_id FROM transactions
		WHERE user_id = $1 AND kind = $2 AND amount = $3
		  AND occurred_at >= $4::timestamptz - interval '1 day' AND occurred_at < $4::timestamptz + interval '2 days'
		  AND (import_key IS NULL OR import_key NOT LIKE 'mufg:%')
		  AND NOT (id = ANY($5::bigint[]))`
	args := []any{m.userID, rec.kind, r.out + r.in, r.day, pq.Array(m.matched)}
	switch {
	case rec.kind != "transfer":
		if bank == 0 {
			return nil
		}
		q += ` AND account_id = $6`
		args = append(args, bank)
	case other == nil:
		return nil
	case r.out > 0:
		q += ` AND to_account_id = $6`
		args = append(args, *other)
	default:
		q += ` AND account_id = $6`
		args = append(args, *other)
	}
	q += ` ORDER BY abs(extract(epoch FROM occurred_at - ($4::timestamptz + interval '12 hours'))), id LIMIT 1`
	var id, acct int64
	err := m.tx.QueryRowContext(m.ctx, q, args...).Scan(&id, &acct)
	if errors.Is(err, sql.ErrNoRows) {
		return nil
	}
	if err != nil {
		return err
	}
	m.matched = append(m.matched, id)
	out.Status, out.MatchedID = "matched", &id
	if r.in > 0 && rec.kind == "transfer" {
		// The bank's side of an incoming transfer is where it goes.
		if err := m.tx.QueryRowContext(m.ctx,
			`SELECT to_account_id FROM transactions WHERE user_id = $1 AND id = $2`, m.userID, id).Scan(&acct); err != nil {
			return err
		}
	}
	out.MatchedAccountID = &acct
	return nil
}

type mufgAdded struct {
	row mufgRow
	rec mufgRecord
}

// mufgBalance compares the bank's account with the statement: its balance
// at the end of the last day, plus the rows still to add (none after an
// import), against the last row's 差引残高. described are the rows as
// mufgMatcher left them, to find those matched in another account.
func mufgBalance(ctx context.Context, tx *sql.Tx, userID int64, bank *int64, rows []mufgRow, added []mufgAdded, described []MUFGRow) (MUFGBalance, error) {
	first, last := rows[0], rows[len(rows)-1]
	b := MUFGBalance{
		AccountID: bank, From: first.day.Format(time.DateOnly), To: last.day.Format(time.DateOnly),
		Opening: first.balance + first.out - first.in, Closing: last.balance,
	}
	if bank == nil {
		b.Difference = b.Closing
		b.SuggestedInitialBalance = b.Closing
		return b, nil
	}
	err := tx.QueryRowContext(ctx,
		`SELECT a.initial_balance, a.initial_balance + COALESCE((
			SELECT sum(CASE
				WHEN t.kind = 'income' THEN t.amount
				WHEN t.kind = 'expense' THEN -t.amount
				WHEN t.kind = 'transfer' AND t.account_id = a.id THEN -t.amount
				ELSE t.amount END)
			FROM transactions t
			WHERE t.user_id = a.user_id AND t.status = 'confirmed' AND (t.account_id = a.id OR t.to_account_id = a.id)
			  AND t.occurred_at < $3::timestamptz + interval '1 day'), 0)
		 FROM wallet_accounts a WHERE a.user_id = $1 AND a.id = $2`,
		userID, *bank, last.day).Scan(&b.InitialBalance, &b.App)
	if err != nil {
		return b, err
	}
	for _, a := range added {
		b.App += a.row.in - a.row.out
	}
	for _, r := range described {
		if r.Status == "matched" && r.MatchedAccountID != nil && *r.MatchedAccountID != *bank {
			b.Misplaced += r.In - r.Out
		}
	}
	b.Difference = b.Closing - b.App
	b.SuggestedInitialBalance = b.InitialBalance + b.Difference - b.Misplaced
	return b, nil
}

// mufgAccounts suggests an account for each role in the statement: the one
// chosen last time, else the user's account that fits by kind and name,
// else a new one.
func (s *Store) mufgAccounts(ctx context.Context, userID int64, recs []mufgRecord) ([]MUFGAccount, error) {
	counts := map[string]int{MUFGBank: len(recs)}
	for _, r := range recs {
		if r.role != "" {
			counts[r.role]++
		}
	}
	all, err := s.Accounts(ctx, userID)
	if err != nil {
		return nil, err
	}
	learned := map[string]int64{}
	rows, err := s.db.QueryContext(ctx,
		`SELECT source, account_id FROM source_rules WHERE user_id = $1 AND source LIKE $2 || '%'`, userID, mufgRulePrefix)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	for rows.Next() {
		var src string
		var id int64
		if err := rows.Scan(&src, &id); err != nil {
			return nil, err
		}
		learned[strings.TrimPrefix(src, mufgRulePrefix)] = id
	}
	if err := rows.Err(); err != nil {
		return nil, err
	}
	// like lists the unarchived accounts of the kind whose name has one of
	// words, ignoring width and case; with no words, every one of the kind.
	like := func(kind string, words ...string) []int64 {
		var ids []int64
		for _, a := range all {
			if a.Archived || a.Kind != kind {
				continue
			}
			name := normalizeMUFG(a.Name)
			if len(words) == 0 || slices.ContainsFunc(words, func(w string) bool { return strings.Contains(name, normalizeMUFG(w)) }) {
				ids = append(ids, a.ID)
			}
		}
		return ids
	}
	out := []MUFGAccount{}
	for _, role := range mufgRoles {
		if counts[role] == 0 {
			continue
		}
		a := MUFGAccount{Role: role, Count: counts[role], Candidates: []int64{}}
		var found []int64
		switch role {
		case MUFGBank:
			a.NewName, a.NewKind = "MUFG", "bank"
			found = like("bank", "MUFG", "三菱UFJ", "UFJ")
		case MUFGCash:
			a.NewName, a.NewKind = "Cash", "cash"
			found = like("cash")
		case MUFGPayPayCard:
			a.NewName, a.NewKind = "PayPay Card", "credit_card"
			found = like("credit_card", "PayPay")
		case MUFGSMBCCard:
			a.NewName, a.NewKind = "SMBC Card", "credit_card"
			found = like("credit_card", "SMBC", "三井住友", "Sumitomo")
		case MUFGPayPay:
			a.NewName, a.NewKind = "PayPay", "e_money"
			found = like("e_money", "PayPay")
		}
		if id, ok := learned[role]; ok && slices.ContainsFunc(all, func(x Account) bool { return x.ID == id }) {
			a.AccountID = &id
		} else if len(found) > 0 {
			a.AccountID = &found[0]
		}
		for _, id := range found {
			if a.AccountID == nil || id != *a.AccountID {
				a.Candidates = append(a.Candidates, id)
			}
		}
		out = append(out, a)
	}
	return out, nil
}

// mufgPayeeRules are the categories chosen for payees in earlier imports,
// by PayeeKey.
func (s *Store) mufgPayeeRules(ctx context.Context, userID int64) (map[string]int64, error) {
	out := map[string]int64{}
	rows, err := s.db.QueryContext(ctx,
		`SELECT payee_key, category_id FROM payee_rules WHERE user_id = $1 AND payee_key LIKE $2 || '%'`, userID, mufgRulePrefix)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	for rows.Next() {
		var key string
		var id int64
		if err := rows.Scan(&key, &id); err != nil {
			return nil, err
		}
		out[strings.TrimPrefix(key, mufgRulePrefix)] = id
	}
	return out, rows.Err()
}

func (s *Store) readMUFGFor(ctx context.Context, userID int64, data []byte) ([]mufgRow, error) {
	st, err := s.Settings(ctx, userID)
	if err != nil {
		return nil, err
	}
	if st.Currency != "JPY" {
		return nil, invalid("MUFG amounts are in yen, and your main currency is %s", st.Currency)
	}
	return readMUFG(data)
}

// ---------- Import ----------

// ImportMUFG records a statement. accounts maps a role to the account for
// it; one with a NewName goes to the account by that name (created if
// new), and a role left out, or mapped to 0, gets the suggested account (created
// if new). categories (groups from PreviewMUFG) give expenses and income a
// category; a group left out gets its suggested one, if the user has it.
// Both choices are remembered for the next import.
//
// Each row is recorded once: its key makes importing an overlapping period
// a no-op for rows already imported, and a row already recorded another way
// is matched (see mufgMatcher.status) and not recorded again. A row no rule
// knows is still recorded, without a category, so the account's balance
// follows the statement; the user sorts it afterwards.
//
// With matchBalance, the bank account's starting balance is then changed so
// its balance at the statement's end is the statement's, but for rows
// recorded in another account (MUFGBalance.Misplaced): those stay a
// difference to fix by moving them, rather than being hidden in the
// starting balance, which would be wrong once they're moved.
func (s *Store) ImportMUFG(ctx context.Context, userID int64, data []byte, accounts map[string]AccountPick, categories []CategoryChoice, matchBalance bool) (MUFGResult, error) {
	res := MUFGResult{ImportResult: ImportResult{Problems: []string{}}, Rows: []MUFGRow{}}
	rows, err := s.readMUFGFor(ctx, userID, data)
	if err != nil {
		return res, err
	}
	recs := make([]mufgRecord, len(rows))
	for i, r := range rows {
		recs[i] = classifyMUFG(r)
	}
	suggested, err := s.mufgAccounts(ctx, userID, recs)
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
	for _, a := range suggested {
		pick := accounts[a.Role]
		id := pick.ID
		if _, ok := owned[strconv.FormatInt(id, 10)]; id != 0 && !ok {
			return res, db.ErrNotFound
		}
		if id == 0 && pick.NewName != "" {
			var made bool
			id, made, err = accountNamed(ctx, tx, userID, pick.NewName, a.NewKind)
			if err != nil {
				return res, err
			}
			if made {
				res.AccountsCreated++
			}
		}
		if id == 0 && a.AccountID != nil {
			id = *a.AccountID
		}
		if id == 0 {
			err := tx.QueryRowContext(ctx,
				`INSERT INTO wallet_accounts (user_id, name, kind) VALUES ($1, $2, $3)
				 ON CONFLICT (user_id, name) DO UPDATE SET name = EXCLUDED.name RETURNING id`,
				userID, a.NewName, a.NewKind).Scan(&id)
			if err != nil {
				return res, err
			}
			res.AccountsCreated++
		}
		account[a.Role] = id
	}
	for role, id := range account {
		if role != MUFGBank && id == account[MUFGBank] {
			return res, invalid("the bank account and the account for %s must be different", MUFGRoleLabel(role))
		}
		if _, err := tx.ExecContext(ctx,
			`INSERT INTO source_rules (user_id, source, account_id) VALUES ($1, $2, $3)
			 ON CONFLICT (user_id, source) DO UPDATE SET account_id = EXCLUDED.account_id`,
			userID, mufgRulePrefix+role, id); err != nil {
			return res, err
		}
	}

	ic, err := newImportCategories(ctx, tx, s, userID)
	if err != nil {
		return res, err
	}
	learned, err := s.mufgPayeeRules(ctx, userID)
	if err != nil {
		return res, err
	}
	chosen := map[string]CategoryChoice{}
	for _, ch := range categories {
		chosen[groupKey(ch.Kind, ch.Name)] = ch
	}
	resolved := map[string]*int64{}
	category := func(rec mufgRecord) (*int64, error) {
		k := groupKey(rec.kind, rec.group)
		if id, done := resolved[k]; done {
			return id, nil
		}
		var id *int64
		if ch, ok := chosen[k]; ok {
			ch.Kind = rec.kind
			var err error
			if id, err = ic.choose(ch); err != nil {
				return nil, err
			}
			// Remember the choice for this payee, or forget one undone.
			key := mufgRulePrefix + PayeeKey(rec.group)
			if id != nil {
				_, err = tx.ExecContext(ctx,
					`INSERT INTO payee_rules (user_id, payee_key, category_id) VALUES ($1, $2, $3)
					 ON CONFLICT (user_id, payee_key) DO UPDATE SET category_id = EXCLUDED.category_id`,
					userID, key, *id)
			} else {
				_, err = tx.ExecContext(ctx, `DELETE FROM payee_rules WHERE user_id = $1 AND payee_key = $2`, userID, key)
			}
			if err != nil {
				return nil, err
			}
		} else {
			// No choice: what the preview suggested, if it's a category
			// the user has.
			id = suggestMUFG(ic.cats, learned, rec).CategoryID
		}
		resolved[k] = id
		return id, nil
	}

	m := mufgMatcher{ctx: ctx, tx: tx, userID: userID, matched: []int64{}}
	for i, r := range rows {
		rec := recs[i]
		out := describeMUFG(r, rec)
		other := account[rec.role]
		if err := m.status(&out, r, rec, account[MUFGBank], &other); err != nil {
			return res, err
		}
		res.Rows = append(res.Rows, out)
		switch out.Status {
		case "imported":
			res.Duplicates++
			continue
		case "matched":
			res.Matched++
			continue
		}
		t := Transaction{Kind: rec.kind, Amount: r.out + r.in, AccountID: account[MUFGBank],
			OccurredAt: r.day.Add(12 * time.Hour), Note: mufgNote(r)}
		if rec.kind == "transfer" {
			from, to := account[MUFGBank], account[rec.role]
			if r.in > 0 {
				from, to = to, from
			}
			t.AccountID, t.ToAccountID = from, &to
		} else {
			t.Payee = rec.group
			if t.CategoryID, err = category(rec); err != nil {
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
	bank := account[MUFGBank]
	if res.Balance, err = mufgBalance(ctx, tx, userID, &bank, rows, nil, res.Rows); err != nil {
		return res, err
	}
	if adjust := res.Balance.Difference - res.Balance.Misplaced; matchBalance && adjust != 0 {
		if _, err := tx.ExecContext(ctx,
			`UPDATE wallet_accounts SET initial_balance = initial_balance + $3 WHERE user_id = $1 AND id = $2`,
			userID, bank, adjust); err != nil {
			return res, err
		}
		b := &res.Balance
		b.Adjusted, b.InitialBalance, b.App, b.Difference = adjust, b.InitialBalance+adjust, b.App+adjust, b.Misplaced
	}
	res.CategoriesCreated = ic.created
	return res, tx.Commit()
}

// mufgNote is the row's text as the statement has it, for the record.
func mufgNote(r mufgRow) string {
	var parts []string
	for _, p := range []string{r.summary, r.detail, r.memo} {
		if p = tidyMUFG(p); p != "" {
			parts = append(parts, p)
		}
	}
	return clip(strings.Join(parts, " · "), maxText)
}

// MUFGRoleLabel names a role for messages.
func MUFGRoleLabel(role string) string {
	return map[string]string{
		MUFGBank: "the MUFG account", MUFGCash: "cash", MUFGPayPayCard: "PayPay Card",
		MUFGSMBCCard: "SMBC card", MUFGPayPay: "PayPay balance",
	}[role]
}
