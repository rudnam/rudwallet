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
	"regexp"
	"slices"
	"strconv"
	"strings"
	"time"
	"unicode/utf8"

	"golang.org/x/text/encoding/japanese"
	"golang.org/x/text/width"

	"github.com/rudnam/wallet-app/internal/db"
)

// Vpass's statement export (三井住友カード's Vpass → ご利用明細 → CSV
// download, a file named after the month billed, e.g. 202611.csv): in
// Shift_JIS (CP932), with no header row. Each card's purchases follow a line
// naming the card, and the statement ends with lines without a date (what
// a revolving balance adds, the total billed):
//
//	山田　太郎　様,5334-91**-****-****,Ａｍａｚｏｎマスター
//	2026/10/11,ＡＭＡＺＯＮ．ＣＯ．ＪＰ,318,１,１,318,
//	2026/10/13,ＧＯＯＧＬＥ　ＹＯＵＴＵＢＥＰＲＥＭＩＵ,1280,１,１,1280,
//	,※リボ払いお支払い額（弁済金）※,,,,5523,残高などはお支払予定明細欄をご覧ください
//	,,,,,17449,
//
// The columns are 利用日, 利用店名, 利用金額 (the purchase; less than zero
// for a refund), 支払区分 (１: in one payment, or 分割, リボ…), 今回回数,
// 支払金額 (what this month's bill takes of it) and 備考. A purchase paid in
// instalments is listed on each month's statement, with the same 利用日,
// shop and 利用金額, so it is recorded once, for its whole amount, on the
// day it was made. Lines without a date are about the bill, not purchases,
// and are left out; so is paying the bill, which the bank's statement
// records (MUFGSMBCCard).
//
// Each card goes to an account the user picks, remembered under
// vpassRulePrefix; the one Vpass's notifications for the card go to
// ("Vpass · <card>") is suggested first. Purchases already recorded from
// those notifications are matched, not added again.

// vpassRulePrefix marks the account remembered for a card in source_rules.
const vpassRulePrefix = "vpass-csv:"

// vpassKeyPrefix starts the import_key of each imported row.
const vpassKeyPrefix = "vp:"

// vpassNotifySource is the source label Vpass's notifications give a card
// (internal/notify, vpass).
func vpassNotifySource(card string) string { return "Vpass · " + card }

// vpassCurrency finds a foreign currency in 備考 (e.g. "10.00 USD").
var vpassCurrency = regexp.MustCompile(`\b[A-Z]{3}\b`)

type vpassRow struct {
	line   int
	card   string
	when   time.Time // the day at noon, Japan time
	kind   string    // expense, or income for a refund
	amount int64
	payee  string
	note   string
	key    string
	// foreign: paid in another currency, so the notification at the time
	// of paying only estimated the yen amount billed.
	foreign bool
}

// tidyVpass is text as a record shows it: half-width letters and digits,
// single spaces.
func tidyVpass(s string) string {
	return strings.Join(strings.Fields(width.Fold.String(s)), " ")
}

// readVpass reads a statement: Shift_JIS as Vpass sends it, or UTF-8 if it
// was converted. Rows that can't be read are reported in res.Problems and
// left out.
func readVpass(data []byte, res *ImportResult) ([]vpassRow, error) {
	data = bytes.TrimPrefix(data, []byte("\xEF\xBB\xBF"))
	if !utf8.Valid(data) {
		var err error
		if data, err = japanese.ShiftJIS.NewDecoder().Bytes(data); err != nil {
			return nil, invalid("the file is neither Shift_JIS nor UTF-8 text")
		}
	}
	r := csv.NewReader(bytes.NewReader(data))
	r.FieldsPerRecord = -1
	r.LazyQuotes = true

	var rows []vpassRow
	card := ""
	seenCard := false
	seen := map[string]int{} // rows alike so far, for their keys
	for line := 1; ; line++ {
		rec, err := r.Read()
		if errors.Is(err, io.EOF) {
			break
		}
		if err != nil {
			return nil, invalid("line %d: unreadable row", line)
		}
		for i := range rec {
			rec[i] = strings.TrimSpace(rec[i])
		}
		get := func(i int) string {
			if i < len(rec) {
				return rec[i]
			}
			return ""
		}
		first := width.Fold.String(get(0))
		// A card's line: the holder ("… 様"), the card's number, masked, and its name.
		if strings.HasSuffix(first, "様") {
			card, seenCard = tidyVpass(get(2)), true
			if card == "" {
				card = "Vpass"
			}
			continue
		}
		if first == "" {
			continue // a line about the bill, or blank
		}
		day, err := time.ParseInLocation("2006/1/2", first, jst)
		if err != nil {
			if !seenCard && len(rows) == 0 && line <= 2 {
				// A heading, as a converted file may have.
				continue
			}
			addProblem(res, "line %d: date %q not understood", line, get(0))
			continue
		}
		if !seenCard {
			return nil, invalid("this doesn't look like a Vpass statement (ご利用明細): no line naming the card before the purchases")
		}
		amount, err := signedYen(width.Fold.String(get(2)))
		if err != nil {
			addProblem(res, "line %d: amount not understood", line)
			continue
		}
		if amount == 0 {
			continue
		}
		row := vpassRow{line: line, card: card, when: day.Add(12 * time.Hour), kind: "expense", amount: amount,
			payee: clip(tidyVpass(get(1)), maxName)}
		if amount < 0 {
			row.kind, row.amount = "income", -amount
		}
		var note []string
		if class := tidyVpass(get(3)); class != "" && class != "1" && class != "1回" {
			n := "支払区分 " + class
			if count := tidyVpass(get(4)); count != "" {
				n += " (" + count + ")"
			}
			note = append(note, n)
		}
		if remark := tidyVpass(get(6)); remark != "" {
			note = append(note, remark)
			row.foreign = slices.ContainsFunc(vpassCurrency.FindAllString(remark, -1), func(c string) bool { return c != "JPY" })
		}
		row.note = clip(strings.Join(note, ". "), maxText)

		alike := strings.Join([]string{card, day.Format(time.DateOnly), PayeeKey(row.payee), strconv.FormatInt(amount, 10)}, "\x1f")
		sum := sha256.Sum256([]byte(alike + "\x1f" + strconv.Itoa(seen[alike])))
		seen[alike]++
		row.key = vpassKeyPrefix + hex.EncodeToString(sum[:16])
		rows = append(rows, row)
	}
	if !seenCard {
		return nil, invalid("this doesn't look like a Vpass statement (ご利用明細): no line naming the card before the purchases")
	}
	return rows, nil
}

func addProblem(res *ImportResult, format string, args ...any) {
	if len(res.Problems) < maxProblems {
		res.Problems = append(res.Problems, invalid(format, args...).Error())
	}
}

func (s *Store) readVpassFor(ctx context.Context, userID int64, data []byte, res *ImportResult) ([]vpassRow, error) {
	st, err := s.Settings(ctx, userID)
	if err != nil {
		return nil, err
	}
	if st.Currency != "JPY" {
		return nil, invalid("Vpass amounts are in yen, and your main currency is %s", st.Currency)
	}
	return readVpass(data, res)
}

// VpassPreview has the shape of a PayPay preview: each card is a way of
// paying (Methods), with the account suggested for it.
type VpassPreview = PayPayPreview

// PreviewVpass reads a Vpass statement, suggests an account for each card
// in it and a category for each shop, and says what importing it with
// accounts and categories (nil: the suggestions) would do.
func (s *Store) PreviewVpass(ctx context.Context, userID int64, data []byte, accounts map[string]AccountPick, categories []CategoryChoice) (VpassPreview, error) {
	p := VpassPreview{Methods: []PayPayMethod{}, Categories: []ImportCategory{}, Rows: []ImportRow{}}
	res := ImportResult{Problems: []string{}}
	rows, err := s.readVpassFor(ctx, userID, data, &res)
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
	if p.Methods, err = s.vpassCards(ctx, userID, rows); err != nil {
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
		if PayeeKey(r.payee) == "" {
			continue
		}
		k := groupKey(r.kind, r.payee)
		if _, ok := index[k]; !ok {
			g := ImportCategory{Name: r.payee, Kind: r.kind}
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
	dry, err := s.importVpass(ctx, userID, data, accounts, categories, true)
	if err != nil {
		return p, err
	}
	p.New, p.Duplicates, p.Matched, p.Skipped, p.Rows = dry.Imported, dry.Duplicates, dry.Matched, dry.Skipped, dry.Rows
	return p, nil
}

// vpassCards lists each card in the statement with a suggested account:
// the one chosen for it last time, else the one its notifications go to,
// else a card account named like it, else a new one.
func (s *Store) vpassCards(ctx context.Context, userID int64, rows []vpassRow) ([]PayPayMethod, error) {
	counts := map[string]int{}
	var order []string
	for _, r := range rows {
		if counts[r.card] == 0 {
			order = append(order, r.card)
		}
		counts[r.card]++
	}
	all, err := s.Accounts(ctx, userID)
	if err != nil {
		return nil, err
	}
	owned := func(id int64) bool { return slices.ContainsFunc(all, func(a Account) bool { return a.ID == id }) }
	// like is the first unarchived card account whose name has one of words,
	// ignoring width and case.
	like := func(words ...string) *int64 {
		for _, a := range all {
			if a.Archived || a.Kind != "credit_card" {
				continue
			}
			name := strings.ToLower(width.Fold.String(a.Name))
			for _, w := range words {
				if w = strings.ToLower(width.Fold.String(w)); w != "" && strings.Contains(name, w) {
					return &a.ID
				}
			}
		}
		return nil
	}
	methods := []PayPayMethod{}
	for _, card := range order {
		m := PayPayMethod{Method: card, Count: counts[card], NewName: clip(card, maxName), NewKind: "credit_card"}
		if card == "Vpass" {
			m.NewName = "SMBC Card"
		}
		for _, src := range []string{vpassRulePrefix + card, vpassNotifySource(card)} {
			var id int64
			err := s.db.QueryRowContext(ctx,
				`SELECT account_id FROM source_rules WHERE user_id = $1 AND source = $2`, userID, src).Scan(&id)
			if errors.Is(err, sql.ErrNoRows) {
				continue
			}
			if err != nil {
				return nil, err
			}
			if owned(id) {
				m.AccountID = &id
				break
			}
		}
		if m.AccountID == nil {
			// The card's own name, then its brand: an "Amazon Mastercard" account
			// for "Amazonマスター", or any 三井住友 card account.
			brand := strings.TrimSuffix(strings.TrimSuffix(card, "マスター"), "カード")
			m.AccountID = like(card, brand, "SMBC", "三井住友", "Sumitomo", "Vpass")
		}
		methods = append(methods, m)
	}
	return methods, nil
}

// ImportVpass imports a Vpass statement. accounts maps a card's name to its
// account: one with a NewName goes to the account by that name (created if
// new), and a card left out, or mapped to 0, gets the suggested account
// (created if new). The choice is remembered for the next import, and for
// the card's notifications if they have none yet.
//
// categories (groups from PreviewVpass) give records a category; a shop's
// expense category is remembered for its next payments. A record whose
// group has no choice gets its shop's remembered category, if an expense.
//
// Each purchase is recorded once: its key makes importing an overlapping
// statement (or a purchase in instalments, listed each month) a no-op for
// rows imported before, and one already recorded another way (from its
// notification, or by hand), for the same amount within a day, is matched
// and not recorded again.
func (s *Store) ImportVpass(ctx context.Context, userID int64, data []byte, accounts map[string]AccountPick, categories []CategoryChoice) (ImportResult, error) {
	return s.importVpass(ctx, userID, data, accounts, categories, false)
}

func (s *Store) importVpass(ctx context.Context, userID int64, data []byte, accounts map[string]AccountPick, categories []CategoryChoice, dry bool) (ImportResult, error) {
	res := ImportResult{Problems: []string{}}
	rows, err := s.readVpassFor(ctx, userID, data, &res)
	if err != nil {
		return res, err
	}
	cards, err := s.vpassCards(ctx, userID, rows)
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
	for _, c := range cards {
		pick := accounts[c.Method]
		id := pick.ID
		if _, ok := owned[strconv.FormatInt(id, 10)]; id != 0 && !ok {
			return res, db.ErrNotFound
		}
		if id == 0 && pick.NewName != "" {
			var made bool
			if id, made, err = accountNamed(ctx, tx, userID, pick.NewName, c.NewKind); err != nil {
				return res, err
			}
			if made {
				res.AccountsCreated++
			}
		}
		if id == 0 && c.AccountID != nil {
			id = *c.AccountID
		}
		if id == 0 {
			var made bool
			if id, made, err = accountNamed(ctx, tx, userID, c.NewName, c.NewKind); err != nil {
				return res, err
			}
			if made {
				res.AccountsCreated++
			}
		}
		account[c.Method] = id
		if _, err := tx.ExecContext(ctx,
			`INSERT INTO source_rules (user_id, source, account_id) VALUES ($1, $2, $3)
			 ON CONFLICT (user_id, source) DO UPDATE SET account_id = EXCLUDED.account_id`,
			userID, vpassRulePrefix+c.Method, id); err != nil {
			return res, err
		}
		// The card's notifications go there too, unless the user chose otherwise.
		if _, err := tx.ExecContext(ctx,
			`INSERT INTO source_rules (user_id, source, account_id) VALUES ($1, $2, $3) ON CONFLICT DO NOTHING`,
			userID, vpassNotifySource(c.Method), id); err != nil {
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
	category := func(r vpassRow) (*int64, error) {
		k := groupKey(r.kind, r.payee)
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
		// Only expenses teach a shop's category: a refund mustn't change it.
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

	keys := make([]string, len(rows))
	for i, r := range rows {
		keys[i] = r.key
	}
	run, err := newImportRun(ctx, tx, userID, SourceVpass, vpassKeyPrefix, keys, dry)
	if err != nil {
		return res, err
	}
	names, err := accountNames(ctx, tx, userID)
	if err != nil {
		return res, err
	}
	res.Rows = []ImportRow{}
	for _, r := range rows {
		t := Transaction{Kind: r.kind, Amount: r.amount, AccountID: account[r.card], OccurredAt: r.when, Payee: r.payee, Note: r.note}
		row := ImportRow{OccurredAt: r.when, Kind: r.kind, Amount: r.amount, Payee: r.payee, Note: r.note, Account: names[t.AccountID], AllDay: true}
		run.period(r.when)

		// A purchase is on the statement by its day only; its notification
		// has the time, so a day either side of noon covers the whole day.
		status, id, err := run.check(r.key, t, 24*time.Hour)
		if err != nil {
			return res, err
		}
		if status == "" && r.foreign {
			if mid, ok, err := run.recordedNear(t, 24*time.Hour, foreignSlack); err != nil {
				return res, err
			} else if ok {
				status, id = "matched", mid
			}
		}
		if status == "" {
			// Recorded from the card's notification, in another account (the
			// default one, before the card had its own).
			if mid, ok, err := run.find(
				`SELECT id FROM transactions
				 WHERE user_id = $1 AND kind = $2 AND amount = $3 AND to_account_id IS NULL
				   AND source = 'notification' AND source_label = $4
				   AND occurred_at BETWEEN $5::timestamptz - interval '1 day' AND $5::timestamptz + interval '1 day'
				   AND NOT (id = ANY($6::bigint[]))
				 ORDER BY abs(extract(epoch FROM occurred_at - $5::timestamptz)), id LIMIT 1`,
				userID, t.Kind, t.Amount, vpassNotifySource(r.card), t.OccurredAt, run.takenArray()); err != nil {
				return res, err
			} else if ok {
				status, id = "matched", mid
			}
		}
		if status == "" {
			if t.CategoryID, err = category(r); err != nil {
				return res, err
			}
			if id, err = run.add(t, r.key); err != nil {
				return res, err
			}
			status = "new"
			res.Imported++
		} else {
			row.MatchedID = &id
			if status == "imported" {
				res.Duplicates++
			} else {
				res.Matched++
			}
		}
		row.Status = status
		res.Rows = append(res.Rows, row)
	}
	res.CategoriesCreated = ic.created
	if err := run.finish(&res, 0, 0); err != nil {
		return res, err
	}
	if dry {
		return res, nil
	}
	return res, tx.Commit()
}
