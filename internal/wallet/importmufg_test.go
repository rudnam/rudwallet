package wallet

import (
	"strings"
	"testing"

	"golang.org/x/text/encoding/japanese"
)

// mufgStatement is a statement as MUFG writes it, with its mixed dashes and
// widths: カ－ド (U+FF0D) and カード (U+30FC), full-width letters and
// spaces, unpadded dates.
var mufgStatement = []string{
	`日付,摘要,摘要内容,支払い金額,預かり金額,差引残高,メモ,未資金化区分,入払区分`,
	`"2026/10/13","振込１","ペイペイ","","20,000","20,007","","",""`,
	`"2026/10/14","口座振替３","ＲＴＫ　ペイペイ","3,000","","17,007","","",""`,
	`"2026/10/14","カ－ド","","5,000","","12,007","","",""`,
	`"2026/10/14","カード","","5,000","","7,007","","",""`,
	`"2026/10/15","カードＣ１","","1,000","","6,007","","",""`,
	`"2026/10/15","手数料","ＡＴＭ","110","","5,897","","",""`,
	`"2026/10/25","振込１","シービーエステクノ（カ　","","250,000","255,897","","",""`,
	`"2026/10/27","口座振替４","ＰＡＹＰＡＹカード","100,000","","155,897","","",""`,
	`"2026/10/27","口座振替２","ミツイスミトモカ－ド","20,000","","135,897","","",""`,
	`"2026/10/28","Ｄ現金還元","","","12","135,909","","",""`,
	`"2026/10/30","振込２","ヤマダ　タロウ","2,500","","133,409","","",""`,
	`"2026/11/1","振込１","スズキ　ハナコ","","1,000","134,409","","",""`,
}

func shiftJIS(t *testing.T, lines []string) []byte {
	t.Helper()
	b, err := japanese.ShiftJIS.NewEncoder().Bytes([]byte(strings.Join(lines, "\r\n") + "\r\n"))
	if err != nil {
		t.Fatal(err)
	}
	return b
}

func TestNormalizeMUFG(t *testing.T) {
	for in, want := range map[string]string{
		"カ－ド":          "カード",
		"カード":          "カード",
		"ｶｰﾄﾞ":         "カード",
		"カードＣ１":        "カードC1",
		"ＲＴＫ　ペイペイ":     "RTKペイペイ",
		"ミツイスミトモカ－ド":   "ミツイスミトモカード",
		"ＰａｙＰａｙカード":    "PAYPAYカード",
		" シービー　エステクノ ": "シービーエステクノ",
	} {
		if got := normalizeMUFG(in); got != want {
			t.Errorf("normalizeMUFG(%q) = %q, want %q", in, got, want)
		}
	}
}

func TestReadMUFG(t *testing.T) {
	rows, err := readMUFG(shiftJIS(t, mufgStatement))
	if err != nil {
		t.Fatal(err)
	}
	if len(rows) != 12 {
		t.Fatalf("want 12 rows, got %d", len(rows))
	}
	if r := rows[0]; r.in != 20000 || r.out != 0 || r.balance != 20007 || r.day.Format("2006-01-02") != "2026-10-13" {
		t.Errorf("first row %+v", r)
	}
	if r := rows[11]; r.day.Format("2006-01-02") != "2026-11-01" || r.detail != "スズキ　ハナコ" {
		t.Errorf("last row %+v (unpadded date, text as written)", r)
	}
	keys := map[string]bool{}
	for _, r := range rows {
		if keys[r.key] {
			t.Errorf("line %d: key %s repeats; equal ATM withdrawals must stay apart", r.line, r.key)
		}
		keys[r.key] = true
	}

	// UTF-8 (converted by the browser) reads the same, with the same keys.
	utf, err := readMUFG([]byte(strings.Join(mufgStatement, "\n")))
	if err != nil {
		t.Fatal(err)
	}
	for i := range rows {
		if utf[i].key != rows[i].key {
			t.Errorf("line %d: UTF-8 key differs", rows[i].line)
		}
	}
}

// Re-exported with different padding, dashes or widths, a row keeps its key,
// so importing it again is a no-op.
func TestMUFGKeyStable(t *testing.T) {
	again := append([]string{}, mufgStatement...)
	again[3] = `"2026/10/14","カード ","","5,000","","12,007","","",""`
	again[7] = `"2026/10/25","振込1","シービーエステクノ(カ","","250,000","255,897","","",""`
	a, err := readMUFG([]byte(strings.Join(mufgStatement, "\n")))
	if err != nil {
		t.Fatal(err)
	}
	b, err := readMUFG([]byte(strings.Join(again, "\n")))
	if err != nil {
		t.Fatal(err)
	}
	for _, i := range []int{2, 6} {
		if a[i].key != b[i].key {
			t.Errorf("line %d: key changed with the spelling", a[i].line)
		}
	}
}

func TestMUFGNewestFirst(t *testing.T) {
	lines := []string{mufgStatement[0]}
	for i := len(mufgStatement) - 1; i > 0; i-- {
		lines = append(lines, mufgStatement[i])
	}
	rows, err := readMUFG([]byte(strings.Join(lines, "\n")))
	if err != nil {
		t.Fatal(err)
	}
	if rows[0].balance != 20007 {
		t.Errorf("rows should be put oldest first: %+v", rows[0])
	}
}

func TestMUFGBalancesMustAddUp(t *testing.T) {
	for name, edit := range map[string]func([]string) []string{
		"missing row": func(l []string) []string { return append(append([]string{}, l[:4]...), l[5:]...) },
		"edited amount": func(l []string) []string {
			l = append([]string{}, l...)
			l[6] = `"2026/10/15","手数料","ＡＴＭ","220","","5,897","","",""`
			return l
		},
	} {
		_, err := readMUFG([]byte(strings.Join(edit(mufgStatement), "\n")))
		if err == nil || !strings.Contains(err.Error(), "rows are missing or were changed") {
			t.Errorf("%s: want the import refused, got %v", name, err)
		}
	}
}

func TestReadMUFGRefuses(t *testing.T) {
	for name, csv := range map[string]string{
		"not MUFG":    "取引日,出金金額（円）\n2026/10/01,100",
		"both":        mufgStatement[0] + "\n" + `"2026/10/13","振込１","x","1","1","7","","",""`,
		"neither":     mufgStatement[0] + "\n" + `"2026/10/13","振込１","x","","","7","","",""`,
		"bad date":    mufgStatement[0] + "\n" + `"10/13","振込１","x","1","","7","","",""`,
		"header only": mufgStatement[0],
	} {
		if _, err := readMUFG([]byte(csv)); err == nil {
			t.Errorf("%s: want an error", name)
		}
	}
}

func TestClassifyMUFG(t *testing.T) {
	rows, err := readMUFG([]byte(strings.Join(mufgStatement, "\n")))
	if err != nil {
		t.Fatal(err)
	}
	want := []struct {
		kind, role, group string
		rule              bool
	}{
		{"transfer", MUFGPayPay, "", true},   // 振込 from PayPay
		{"transfer", MUFGPayPay, "", true},   // 口座振替 RTK ペイペイ: a charge
		{"transfer", MUFGCash, "", true},     // カ－ド
		{"transfer", MUFGCash, "", true},     // カード
		{"transfer", MUFGCash, "", true},     // カードC1: a convenience-store ATM
		{"expense", "", "ATM", true},         // 手数料
		{"income", "", "シービーエステクノ(カ", false}, // a 振込 in: only the user knows it is salary
		{"transfer", MUFGPayPayCard, "", true},
		{"transfer", MUFGSMBCCard, "", true}, // ミツイスミトモカ－ド, with U+FF0D
		{"income", "", "D現金還元", true},
		{"expense", "", "ヤマダ タロウ", false}, // to a person
		{"income", "", "スズキ ハナコ", false},  // from a person
	}
	for i, r := range rows {
		got := classifyMUFG(r)
		w := want[i]
		if got.kind != w.kind || got.role != w.role || got.group != w.group || got.rule != w.rule {
			t.Errorf("line %d (%s %s): got %+v, want %+v", r.line, r.summary, r.detail, got, w)
		}
	}
}

func TestParseMinor(t *testing.T) {
	for _, c := range []struct {
		in, cur string
		want    int64
		ok      bool
	}{
		{"4200", "JPY", 4200, true},
		{"1,200", "JPY", 1200, true},
		{"-300", "JPY", -300, true},
		{"12.50", "USD", 1250, true},
		{"12.5", "USD", 1250, true},
		{"12.5", "JPY", 0, false},
		{"abc", "JPY", 0, false},
		{"", "JPY", 0, false},
	} {
		got, ok := ParseMinor(c.in, c.cur)
		if got != c.want || ok != c.ok {
			t.Errorf("ParseMinor(%q, %s) = %d, %v; want %d, %v", c.in, c.cur, got, ok, c.want, c.ok)
		}
	}
}
