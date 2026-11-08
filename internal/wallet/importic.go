package wallet

import (
	"cmp"
	"context"
	"database/sql"
	"encoding/hex"
	"errors"
	"fmt"
	"slices"
	"strconv"
	"strings"
	"time"

	"github.com/lib/pq"

	"github.com/rudnam/wallet-app/internal/db"
)

// Transit IC cards (Suica, PASMO, ICOCA and the others that work on the
// same gates) are FeliCa cards, which the Android app reads over NFC
// (android/…/ICCardPlugin.java). Their history can be read without a key:
// system code 0003, service 090F, one 16-byte block per entry, newest
// first. The card keeps only its last 20 or so entries.
//
//	byte  0      terminal type (05: bus, C7: shop, C8: vending machine, …)
//	byte  1      process (01: fare, 02: top-up, 46: shop, …; 80 set: with cash)
//	bytes 4-5    date: 7 bits year (since 2000), 4 bits month, 5 bits day
//	bytes 6-7    a shop's time: 5 bits hour, 6 bits minute, 5 bits second/2
//	bytes 6-9    for trains, the line and station codes in (6-7) and out (8-9)
//	bytes 6-7    for buses, the bus company's code (big-endian)
//	bytes 10-11  the balance after it, little-endian yen
//	bytes 13-14  the card's sequence number for the entry
//	byte  15     for trains, the region of the station in (bits 7-6) and out (5-4)
//
// Stations and bus companies are named from the lists in icstations/
// (icstations.go): a fare is recorded as "新宿 → 渋谷", or by the bus
// company's name, and its note keeps the lines and the codes.
//
// The card doesn't keep amounts: an entry's amount is the change from the
// balance before it. So the oldest entry read is only where the history
// starts; it is recorded only if an earlier read had the entry before it.
//
// Each entry is keyed by the card's IDm, day, sequence number and balance,
// so reading the card again adds only what is new. What the user picks for
// the card (its account, where top-ups come from, and the categories) is
// remembered under icRulePrefix.

// icRulePrefix marks what an IC card import remembers in source_rules
// (the card's account, by IDm, and the top-up account) and payee_rules
// (category for each kind of entry).
const icRulePrefix = "ic-card:"

// icKeyPrefix starts the import_key of each imported entry.
const icKeyPrefix = "ic:"

// maxICHistory is the most entries accepted from one read; cards keep 20.
const maxICHistory = 64

// Roles: the accounts a card's history moves money between.
const (
	ICCard  = "card"   // the card itself
	ICTopUp = "top-up" // where money put on the card comes from
)

// Kinds of entry, which name their records and group them for categories.
const (
	icTrain  = "Train"
	icBus    = "Bus"
	icShop   = "Shop payment"
	icRefund = "Refund"
)

var icNamesJa = map[string]string{icTrain: "電車", icBus: "バス", icShop: "物販", icRefund: "払い戻し"}

// ICCardRead is what the phone read from a card: its IDm, and the history
// blocks as read, newest first, each 16 bytes in hex.
type ICCardRead struct {
	IDm     string   `json:"idm"`
	History []string `json:"history"`
}

// icRow is one entry of a card's history.
type icRow struct {
	when     time.Time // the day at noon, Japan time, or a shop's time
	seq      int
	balance  int64
	process  byte
	terminal byte
	// change is the balance's change from the entry before; start is set
	// on the oldest entry read, which has none.
	change int64
	start  bool
	key    string
	// entry and exit are a train fare's stations (nil without one), and
	// busCompany a bus fare's company code.
	entry, exit *icStationCode
	busCompany  uint16
}

// readICCard decodes a read into the card's entries, oldest first. Empty
// blocks (a new card's) are left out.
func readICCard(read ICCardRead) (string, []icRow, error) {
	idm := strings.ToUpper(strings.TrimSpace(read.IDm))
	if b, err := hex.DecodeString(idm); err != nil || len(b) != 8 {
		return "", nil, invalid("the card's IDm must be 8 bytes in hex")
	}
	if len(read.History) == 0 {
		return "", nil, invalid("the card has no history")
	}
	if len(read.History) > maxICHistory {
		return "", nil, invalid("a card keeps at most %d entries", maxICHistory)
	}
	var rows []icRow
	for i, h := range read.History {
		b, err := hex.DecodeString(strings.TrimSpace(h))
		if err != nil || len(b) != 16 {
			return "", nil, invalid("entry %d: must be 16 bytes in hex", i+1)
		}
		if b[4] == 0 && b[5] == 0 {
			continue // never written
		}
		year, month, day := 2000+int(b[4]>>1), time.Month(int(b[4]&1)<<3|int(b[5]>>5)), int(b[5]&0x1f)
		if month < 1 || month > 12 || day < 1 || day > 31 {
			return "", nil, invalid("entry %d: the date isn't one", i+1)
		}
		r := icRow{
			when:     time.Date(year, month, day, 12, 0, 0, 0, jst),
			seq:      int(b[13])<<8 | int(b[14]),
			balance:  int64(b[10]) | int64(b[11])<<8,
			process:  b[1],
			terminal: b[0],
		}
		switch icKindOf(r) {
		case icShop:
			h, m, s := int(b[6]>>3), int(b[6]&7)<<3|int(b[7]>>5), int(b[7]&0x1f)*2
			if h < 24 && m < 60 && s < 60 {
				r.when = time.Date(year, month, day, h, m, s, 0, jst)
			}
		case icBus:
			r.busCompany = uint16(b[6])<<8 | uint16(b[7])
		default:
			if !icTopUp(r) {
				r.entry = icStationOf(b[15]>>6&3, b[6], b[7])
				r.exit = icStationOf(b[15]>>4&3, b[8], b[9])
			}
		}
		r.key = fmt.Sprintf("%s%s:%s:%d:%d", icKeyPrefix, idm, r.when.Format(time.DateOnly), r.seq, r.balance)
		rows = append(rows, r)
	}
	if len(rows) == 0 {
		return "", nil, invalid("the card has no history")
	}
	slices.Reverse(rows) // read newest first
	rows[0].start = true
	for i := 1; i < len(rows); i++ {
		rows[i].change = rows[i].balance - rows[i-1].balance
	}
	return idm, rows, nil
}

// icStationOf is the station with the codes, or nil for none (all zero).
func icStationOf(region, line, station byte) *icStationCode {
	if line == 0 && station == 0 {
		return nil
	}
	return &icStationCode{region, line, station}
}

// icTopUp: the process puts money on the card (at a machine, by
// auto-charge at a gate, on a bus, at a shop's till).
func icTopUp(r icRow) bool {
	switch r.process & 0x7f {
	case 0x02, 0x14, 0x15, 0x1f, 0x49:
		return true
	}
	return false
}

// icKindOf names what a payment or refund was for.
func icKindOf(r icRow) string {
	switch {
	case r.terminal == 0x05 || slices.Contains([]byte{0x0d, 0x0f, 0x23}, r.process&0x7f):
		return icBus
	case r.terminal == 0xc7 || r.terminal == 0xc8 || slices.Contains([]byte{0x46, 0x4a, 0x4b}, r.process&0x7f):
		return icShop
	}
	return icTrain
}

// icRecord is how an entry would be recorded: an expense or income
// (group names it), or a transfer from the top-up account to the card.
type icRecord struct {
	kind, group string
	amount      int64
}

func classifyIC(r icRow) icRecord {
	switch {
	case r.change > 0 && icTopUp(r):
		return icRecord{kind: "transfer", amount: r.change}
	case r.change > 0:
		return icRecord{kind: "income", group: icRefund, amount: r.change}
	}
	return icRecord{kind: "expense", group: icKindOf(r), amount: -r.change}
}

// ---------- Preview ----------

// ICAccount is one role in a card's history and the account its records
// use: an existing one, or a new one to create.
type ICAccount struct {
	Role      string `json:"role"`
	Count     int    `json:"count"`
	AccountID *int64 `json:"account_id"`
	NewName   string `json:"new_name"`
	NewKind   string `json:"new_kind"`
}

// ICRow is how one entry would be (or was) recorded.
type ICRow struct {
	Date    string `json:"date"`
	Time    string `json:"time,omitempty"` // a shop's, as HH:MM
	Seq     int    `json:"seq"`
	Balance int64  `json:"balance"`
	// Amount is the balance's change: negative for a payment.
	Amount int64  `json:"amount"`
	Kind   string `json:"kind"`
	// Group is the kind of entry its category is chosen for
	// (ImportCategory.Name), and Payee the record's: the stations of a train
	// fare ("新宿 → 渋谷"), a bus company, or the group.
	Group string `json:"group,omitempty"`
	Payee string `json:"payee,omitempty"`
	// Entry and Exit are a train fare's stations; Company, a bus company.
	Entry   *ICStation `json:"entry,omitempty"`
	Exit    *ICStation `json:"exit,omitempty"`
	Company string     `json:"company,omitempty"`
	// Status: new (to record), imported (by an earlier read), matched
	// (already recorded another way, e.g. by hand), start (the oldest entry
	// read, whose amount isn't known), or none (no money moved).
	Status    string `json:"status"`
	MatchedID *int64 `json:"matched_id,omitempty"`
}

// ICBalance compares the card's account in the app with the card.
type ICBalance struct {
	AccountID *int64 `json:"account_id"`
	// Card is what the card holds now; App, the account's balance counting
	// what the import adds; Difference is Card - App.
	Card       int64 `json:"card"`
	App        int64 `json:"app"`
	Difference int64 `json:"difference"`
	// InitialBalance is the account's starting balance, and
	// SuggestedInitialBalance the one that makes App the card's.
	InitialBalance          int64 `json:"initial_balance"`
	SuggestedInitialBalance int64 `json:"suggested_initial_balance"`
	// Adjusted is how much an import with matchBalance changed the starting
	// balance by.
	Adjusted int64 `json:"adjusted"`
}

// ICPreview is the dry run of an import from a card.
type ICPreview struct {
	// Card is the last four hex digits of the card's IDm, to tell cards apart.
	Card          string           `json:"card"`
	From          string           `json:"from"`
	To            string           `json:"to"`
	Records       int              `json:"records"`
	New           int              `json:"new"`
	Accounts      []ICAccount      `json:"accounts"`
	Categories    []ImportCategory `json:"categories"`
	NewCategories []Category       `json:"new_categories"`
	Rows          []ICRow          `json:"rows"`
	Balance       ICBalance        `json:"balance"`
	// Gap: the card was read before, but none of the entries it has now
	// were: more happened since than the card keeps, and those are lost.
	Gap bool `json:"gap"`
}

// ICResult is what an import did.
type ICResult struct {
	ImportResult
	Rows    []ICRow   `json:"rows"`
	Balance ICBalance `json:"balance"`
}

// PreviewICCard reads a card's history and shows how each entry would be
// recorded, with the accounts and categories suggested. Nothing is saved.
func (s *Store) PreviewICCard(ctx context.Context, userID int64, read ICCardRead) (ICPreview, error) {
	p := ICPreview{Accounts: []ICAccount{}, Categories: []ImportCategory{}, Rows: []ICRow{}}
	idm, rows, err := s.readICCardFor(ctx, userID, read)
	if err != nil {
		return p, err
	}
	p.Card, p.From, p.To = idm[len(idm)-4:], rows[0].when.Format(time.DateOnly), rows[len(rows)-1].when.Format(time.DateOnly)
	cats, err := s.Categories(ctx, userID)
	if err != nil {
		return p, err
	}
	lang := walletLanguage(cats)
	if p.Accounts, err = s.icAccounts(ctx, userID, idm, rows, lang); err != nil {
		return p, err
	}
	account := map[string]*int64{}
	for _, a := range p.Accounts {
		account[a.Role] = a.AccountID
	}
	learned, err := s.icPayeeRules(ctx, userID)
	if err != nil {
		return p, err
	}

	tx, err := s.db.BeginTx(ctx, &sql.TxOptions{ReadOnly: true})
	if err != nil {
		return p, err
	}
	defer tx.Rollback()
	m := icMatcher{ctx: ctx, tx: tx, userID: userID, matched: []int64{}}
	var card int64
	if account[ICCard] != nil {
		card = *account[ICCard]
	}
	index := map[string]int{}
	var added int64
	for _, r := range rows {
		rec := classifyIC(r)
		out := describeIC(r, rec, lang)
		if err := m.status(&out, r, rec, card); err != nil {
			return p, err
		}
		p.Rows = append(p.Rows, out)
		if out.Status == "none" || out.Status == "start" {
			continue
		}
		p.Records++
		if out.Status != "new" {
			continue
		}
		p.New++
		added += r.change
		if rec.kind == "transfer" {
			continue
		}
		k := groupKey(rec.kind, out.Group)
		if _, ok := index[k]; !ok {
			index[k] = len(p.Categories)
			p.Categories = append(p.Categories, suggestIC(cats, learned, rec, out.Group))
		}
		g := &p.Categories[index[k]]
		g.Count++
		g.Total += rec.amount
	}
	sortGroups(p.Categories)
	p.NewCategories = missingDefaults(cats)
	if p.Gap, err = icGap(ctx, tx, userID, idm, p.Rows); err != nil {
		return p, err
	}
	p.Balance, err = icBalance(ctx, tx, userID, account[ICCard], rows, added)
	return p, err
}

// icName is a kind of entry in the wallet's language.
func icName(group, lang string) string {
	if j, ok := icNamesJa[group]; ok && lang == "ja" {
		return j
	}
	return group
}

func describeIC(r icRow, rec icRecord, lang string) ICRow {
	out := ICRow{Date: r.when.Format(time.DateOnly), Seq: r.seq, Balance: r.balance, Amount: r.change, Kind: rec.kind}
	if rec.group != "" {
		out.Group = icName(rec.group, lang)
		out.Payee = out.Group
	}
	if r.entry != nil {
		e := icStationAt(*r.entry)
		out.Entry = &e
	}
	if r.exit != nil {
		e := icStationAt(*r.exit)
		out.Exit = &e
	}
	if route := icRoute(out.Entry, out.Exit); route != "" && rec.kind != "transfer" {
		out.Payee = clip(route, maxName)
	}
	if r.busCompany != 0 {
		out.Company = icBusCompanies()[r.busCompany]
		if out.Company != "" && rec.kind != "transfer" {
			out.Payee = clip(out.Company, maxName)
		}
	}
	if r.when.Hour() != 12 || r.when.Minute() != 0 || r.when.Second() != 0 {
		out.Time = r.when.Format("15:04")
	}
	return out
}

// icRoute names a fare's stations, "新宿 → 渋谷", with ? for one not in
// the list; empty when neither is known.
func icRoute(entry, exit *ICStation) string {
	name := func(s *ICStation) string {
		if s == nil || s.Name == "" {
			return "?"
		}
		return s.Name
	}
	if name(entry) == "?" && name(exit) == "?" {
		return ""
	}
	if exit == nil {
		return name(entry)
	}
	return name(entry) + " → " + name(exit)
}

// icNote is a record's note: the card, and for a train fare its lines and
// station codes, which keep what the list doesn't know.
func icNote(card string, out ICRow) string {
	note := "IC ••" + card
	if out.Entry == nil && out.Exit == nil {
		return note
	}
	var lines, codes []string
	for _, s := range []*ICStation{out.Entry, out.Exit} {
		if s != nil {
			lines = append(lines, cmp.Or(s.Line, "?"))
			codes = append(codes, s.Code)
		}
	}
	if slices.ContainsFunc(lines, func(l string) bool { return l != "?" && l != "-" }) {
		note += " · " + strings.Join(lines, " → ")
	}
	return clip(note+" · "+strings.Join(codes, " → "), maxText)
}

// suggestIC suggests a category for a kind of entry: the one chosen for it
// in an earlier import, else Transport for fares.
func suggestIC(cats []Category, learned map[string]int64, rec icRecord, name string) ImportCategory {
	g := ImportCategory{Name: name, Kind: rec.kind}
	if id, ok := learned[rec.kind+":"+rec.group]; ok && kindOf(cats, id) == rec.kind {
		g.CategoryID, g.Learned = &id, true
		return g
	}
	if rec.group == icTrain || rec.group == icBus {
		suggestNamed(cats, &g, "Public transport")
	}
	return g
}

// icMatcher finds entries already recorded: by an earlier read (the same
// key), or another way. Each existing record matches one entry at most.
type icMatcher struct {
	ctx     context.Context
	tx      *sql.Tx
	userID  int64
	matched []int64 // never nil: pq sends a nil slice as NULL, which matches nothing
}

// status sets out.Status, and the matched record. card is the card's
// account (0 if new). A record is the same payment when it is of the same
// kind and amount, within a day either side of the entry's, in the card's
// account (for a top-up, into it).
func (m *icMatcher) status(out *ICRow, r icRow, rec icRecord, card int64) error {
	var exists bool
	if err := m.tx.QueryRowContext(m.ctx,
		`SELECT EXISTS (SELECT 1 FROM transactions WHERE user_id = $1 AND import_key = $2)`,
		m.userID, r.key).Scan(&exists); err != nil {
		return err
	}
	switch {
	case exists:
		out.Status = "imported"
		return nil
	case r.start:
		out.Status = "start"
		return nil
	case r.change == 0:
		out.Status = "none"
		return nil
	}
	out.Status = "new"
	if card == 0 {
		return nil
	}
	side := "account_id"
	if rec.kind == "transfer" {
		side = "to_account_id"
	}
	var id int64
	err := m.tx.QueryRowContext(m.ctx,
		`SELECT id FROM transactions
		 WHERE user_id = $1 AND kind = $2 AND amount = $3 AND `+side+` = $4
		   AND occurred_at BETWEEN $5::timestamptz - interval '1 day' AND $5::timestamptz + interval '1 day'
		   AND (import_key IS NULL OR import_key NOT LIKE 'ic:%')
		   AND NOT (id = ANY($6::bigint[]))
		 ORDER BY abs(extract(epoch FROM occurred_at - $5::timestamptz)), id LIMIT 1`,
		m.userID, rec.kind, rec.amount, card, r.when, pq.Array(m.matched)).Scan(&id)
	if errors.Is(err, sql.ErrNoRows) {
		return nil
	}
	if err != nil {
		return err
	}
	m.matched = append(m.matched, id)
	out.Status, out.MatchedID = "matched", &id
	return nil
}

// icGap: the card was imported before, yet none of the entries it has now
// were, so entries between the two reads are missing.
func icGap(ctx context.Context, tx *sql.Tx, userID int64, idm string, rows []ICRow) (bool, error) {
	if slices.ContainsFunc(rows, func(r ICRow) bool { return r.Status == "imported" }) {
		return false, nil
	}
	var before bool
	err := tx.QueryRowContext(ctx,
		`SELECT EXISTS (SELECT 1 FROM transactions WHERE user_id = $1 AND import_key LIKE $2)`,
		userID, icKeyPrefix+idm+":%").Scan(&before)
	return before, err
}

// icBalance compares the card's account (nil if new) with the card: its
// balance now, plus added, what the import still adds (0 after it).
func icBalance(ctx context.Context, tx *sql.Tx, userID int64, card *int64, rows []icRow, added int64) (ICBalance, error) {
	b := ICBalance{AccountID: card, Card: rows[len(rows)-1].balance}
	if card != nil {
		err := tx.QueryRowContext(ctx,
			`SELECT a.initial_balance, a.initial_balance + COALESCE((
				SELECT sum(CASE
					WHEN t.kind = 'income' THEN t.amount
					WHEN t.kind = 'expense' THEN -t.amount
					WHEN t.kind = 'transfer' AND t.account_id = a.id THEN -t.amount
					ELSE t.amount END)
				FROM transactions t
				WHERE t.user_id = a.user_id AND t.status = 'confirmed' AND (t.account_id = a.id OR t.to_account_id = a.id)), 0)
			 FROM wallet_accounts a WHERE a.user_id = $1 AND a.id = $2`,
			userID, *card).Scan(&b.InitialBalance, &b.App)
		if err != nil {
			return b, err
		}
	}
	b.App += added
	b.Difference = b.Card - b.App
	b.SuggestedInitialBalance = b.InitialBalance + b.Difference
	return b, nil
}

// icAccounts suggests an account for the card, and for top-ups if there
// are any: the one chosen last time, else the user's account that fits by
// kind and name, else a new one.
func (s *Store) icAccounts(ctx context.Context, userID int64, idm string, rows []icRow, lang string) ([]ICAccount, error) {
	counts := map[string]int{}
	for _, r := range rows {
		if !r.start && r.change != 0 {
			counts[ICCard]++
			if classifyIC(r).kind == "transfer" {
				counts[ICTopUp]++
			}
		}
	}
	all, err := s.Accounts(ctx, userID)
	if err != nil {
		return nil, err
	}
	learned := map[string]int64{}
	q, err := s.db.QueryContext(ctx,
		`SELECT source, account_id FROM source_rules WHERE user_id = $1 AND source LIKE $2 || '%'`, userID, icRulePrefix)
	if err != nil {
		return nil, err
	}
	defer q.Close()
	for q.Next() {
		var src string
		var id int64
		if err := q.Scan(&src, &id); err != nil {
			return nil, err
		}
		learned[strings.TrimPrefix(src, icRulePrefix)] = id
	}
	if err := q.Err(); err != nil {
		return nil, err
	}
	like := func(kind string, words ...string) *int64 {
		for _, a := range all {
			if a.Archived || a.Kind != kind {
				continue
			}
			name := normalizeMUFG(a.Name)
			if len(words) == 0 || slices.ContainsFunc(words, func(w string) bool { return strings.Contains(name, normalizeMUFG(w)) }) {
				return &a.ID
			}
		}
		return nil
	}
	card := ICAccount{Role: ICCard, Count: counts[ICCard], NewName: "IC card " + idm[len(idm)-4:], NewKind: "e_money"}
	if lang == "ja" {
		card.NewName = "交通系IC " + idm[len(idm)-4:]
	}
	if id, ok := learned[idm]; ok && slices.ContainsFunc(all, func(a Account) bool { return a.ID == id }) {
		card.AccountID = &id
	} else {
		card.AccountID = like("e_money", "Suica", "PASMO", "ICOCA", "Kitaca", "TOICA", "manaca", "SUGOCA", "nimoca", "はやかけん", "交通系", "IC card", "ICカード")
	}
	out := []ICAccount{card}
	if counts[ICTopUp] > 0 {
		top := ICAccount{Role: ICTopUp, Count: counts[ICTopUp], NewName: localName("Cash", lang), NewKind: "cash"}
		if id, ok := learned[ICTopUp]; ok && slices.ContainsFunc(all, func(a Account) bool { return a.ID == id }) {
			top.AccountID = &id
		} else {
			top.AccountID = like("cash")
		}
		out = append(out, top)
	}
	return out, nil
}

// icPayeeRules are the categories chosen for kinds of entry in earlier
// imports, by kind and group ("expense:Train").
func (s *Store) icPayeeRules(ctx context.Context, userID int64) (map[string]int64, error) {
	out := map[string]int64{}
	rows, err := s.db.QueryContext(ctx,
		`SELECT payee_key, category_id FROM payee_rules WHERE user_id = $1 AND payee_key LIKE $2 || '%'`, userID, icRulePrefix)
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
		out[strings.TrimPrefix(key, icRulePrefix)] = id
	}
	return out, rows.Err()
}

func (s *Store) readICCardFor(ctx context.Context, userID int64, read ICCardRead) (string, []icRow, error) {
	st, err := s.Settings(ctx, userID)
	if err != nil {
		return "", nil, err
	}
	if st.Currency != "JPY" {
		return "", nil, invalid("IC card amounts are in yen, and your main currency is %s", st.Currency)
	}
	return readICCard(read)
}

// ---------- Import ----------

// ImportICCard records a card's history. accounts maps a role to the
// account for it; one with a NewName goes to the account by that name
// (created if new), and a role left out, or mapped to 0, gets the suggested
// account (created if new). categories (groups from PreviewICCard) give
// payments and refunds a category; a group left out gets its suggested
// one, if the user has it. Both are remembered for the next read.
//
// Entries already imported are skipped, and one already recorded another
// way is matched (icMatcher.status) and not recorded again. With
// matchBalance, the card's account then gets the starting balance that
// makes its balance the card's.
func (s *Store) ImportICCard(ctx context.Context, userID int64, read ICCardRead, accounts map[string]AccountPick, categories []CategoryChoice, matchBalance bool) (ICResult, error) {
	res := ICResult{ImportResult: ImportResult{Problems: []string{}}, Rows: []ICRow{}}
	idm, rows, err := s.readICCardFor(ctx, userID, read)
	if err != nil {
		return res, err
	}
	cats, err := s.Categories(ctx, userID)
	if err != nil {
		return res, err
	}
	lang := walletLanguage(cats)
	suggested, err := s.icAccounts(ctx, userID, idm, rows, lang)
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
	if top, ok := account[ICTopUp]; ok && top == account[ICCard] {
		return res, invalid("the card and the account top-ups come from must be different")
	}
	for source, id := range map[string]int64{idm: account[ICCard], ICTopUp: account[ICTopUp]} {
		if id == 0 {
			continue
		}
		if _, err := tx.ExecContext(ctx,
			`INSERT INTO source_rules (user_id, source, account_id) VALUES ($1, $2, $3)
			 ON CONFLICT (user_id, source) DO UPDATE SET account_id = EXCLUDED.account_id`,
			userID, icRulePrefix+source, id); err != nil {
			return res, err
		}
	}

	ic, err := newImportCategories(ctx, tx, s, userID)
	if err != nil {
		return res, err
	}
	learned, err := s.icPayeeRules(ctx, userID)
	if err != nil {
		return res, err
	}
	chosen := map[string]CategoryChoice{}
	for _, ch := range categories {
		chosen[groupKey(ch.Kind, ch.Name)] = ch
	}
	resolved := map[string]*int64{}
	category := func(rec icRecord, name string) (*int64, error) {
		k := groupKey(rec.kind, name)
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
			// Remember the choice, or forget one undone.
			key := icRulePrefix + rec.kind + ":" + rec.group
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
			id = suggestIC(ic.cats, learned, rec, name).CategoryID
		}
		resolved[k] = id
		return id, nil
	}

	m := icMatcher{ctx: ctx, tx: tx, userID: userID, matched: []int64{}}
	for _, r := range rows {
		rec := classifyIC(r)
		out := describeIC(r, rec, lang)
		if err := m.status(&out, r, rec, account[ICCard]); err != nil {
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
		case "start", "none":
			continue
		}
		t := Transaction{Kind: rec.kind, Amount: rec.amount, AccountID: account[ICCard], OccurredAt: r.when, Payee: out.Payee, Note: icNote(idm[len(idm)-4:], out)}
		if rec.kind == "transfer" {
			card := account[ICCard]
			t.AccountID, t.ToAccountID = account[ICTopUp], &card
		} else if t.CategoryID, err = category(rec, out.Group); err != nil {
			return res, err
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
	card := account[ICCard]
	if res.Balance, err = icBalance(ctx, tx, userID, &card, rows, 0); err != nil {
		return res, err
	}
	if adjust := res.Balance.Difference; matchBalance && adjust != 0 {
		if _, err := tx.ExecContext(ctx,
			`UPDATE wallet_accounts SET initial_balance = initial_balance + $3 WHERE user_id = $1 AND id = $2`,
			userID, card, adjust); err != nil {
			return res, err
		}
		b := &res.Balance
		b.Adjusted, b.InitialBalance, b.App, b.Difference = adjust, b.InitialBalance+adjust, b.App+adjust, 0
	}
	res.CategoriesCreated = ic.created
	return res, tx.Commit()
}
