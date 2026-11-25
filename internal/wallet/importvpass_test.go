package wallet

import (
	"strings"
	"testing"
	"time"
)

// vpassStatement is a statement as Vpass writes it: a line naming each
// card, purchases in full-width text, and lines about the bill at the end.
var vpassStatement = []string{
	`ヤマダ　タロウ　様,5334-91**-****-****,Ａｍａｚｏｎマスター`,
	`2026/10/11,ＡＭＡＺＯＮ．ＣＯ．ＪＰ,318,１,１,318,`,
	`2026/10/13,ＧＯＯＧＬＥ　ＹＯＵＴＵＢＥＰＲＥＭＩＵ,1280,１,１,1280,`,
	`2026/10/27,ＡＭＡＺＯＮ．ＣＯ．ＪＰ,2690,１,１,2690,`,
	`2026/10/27,ＡＭＡＺＯＮ．ＣＯ．ＪＰ,2690,１,１,2690,`,
	`2026/10/28,ＡＭＡＺＯＮ．ＣＯ．ＪＰ,-1309,１,１,-1309,`,
	`2026/10/29,ビックカメラ,"60,000",分割,1/3,"20,000",`,
	`2026/10/30,ＯＰＥＮＡＩ,1580,１,１,1580,10.00　USD　158.000　10　31`,
	`ヤマダ　ハナコ　様,4980-00**-****-****,三井住友カード（ＮＬ）`,
	`2026/10/15,ローソン,550,１,１,550,`,
	`,※リボ払いお支払い額（弁済金）※,,,,5523,残高などはお支払予定明細欄をご覧ください`,
	`,本明細書作成までのご返済情報を反映した金額となります。,,,,,`,
	`,,,,,33259,`,
}

func TestReadVpass(t *testing.T) {
	res := ImportResult{}
	rows, err := readVpass(shiftJIS(t, vpassStatement), &res)
	if err != nil {
		t.Fatal(err)
	}
	if len(res.Problems) != 0 {
		t.Errorf("problems: %v", res.Problems)
	}
	if len(rows) != 8 {
		t.Fatalf("%d rows, want 8 (the bill's lines left out)", len(rows))
	}
	r := rows[0]
	if r.card != "Amazonマスター" || r.payee != "AMAZON.CO.JP" || r.kind != "expense" || r.amount != 318 ||
		!r.when.Equal(time.Date(2026, 10, 11, 12, 0, 0, 0, jst)) || r.note != "" || r.foreign {
		t.Errorf("first row %+v", r)
	}
	if rows[1].payee != "GOOGLE YOUTUBEPREMIU" {
		t.Errorf("spaces: %q", rows[1].payee)
	}
	if rows[2].key == rows[3].key {
		t.Error("two equal purchases on a day must have different keys")
	}
	if r := rows[4]; r.kind != "income" || r.amount != 1309 {
		t.Errorf("a refund is income: %+v", r)
	}
	if r := rows[5]; r.amount != 60000 || r.note != "支払区分 分割 (1/3)" {
		t.Errorf("an instalment purchase is recorded whole: %+v", r)
	}
	if r := rows[6]; !r.foreign || !strings.Contains(r.note, "USD") {
		t.Errorf("a payment in dollars: %+v", r)
	}
	if r := rows[7]; r.card != "三井住友カード(NL)" || r.payee != "ローソン" {
		t.Errorf("the second card's row: %+v", r)
	}

	// The next month's statement lists the instalment again: the same key.
	again, err := readVpass(shiftJIS(t, []string{
		`ヤマダ　タロウ　様,5334-91**-****-****,Ａｍａｚｏｎマスター`,
		`2026/10/29,ビックカメラ,"60,000",分割,2/3,"20,000",`,
	}), &res)
	if err != nil {
		t.Fatal(err)
	}
	if again[0].key != rows[5].key {
		t.Error("an instalment purchase must keep its key from month to month")
	}
}

func TestReadVpassRejectsOtherFiles(t *testing.T) {
	for name, lines := range map[string][]string{
		"MUFG":   mufgStatement,
		"PayPay": {`取引日,出金金額（円）,入金金額（円）`, `2024/06/03 11:28,1000,-`},
		"empty":  {``},
	} {
		if _, err := readVpass(shiftJIS(t, lines), &ImportResult{}); err == nil {
			t.Errorf("%s: read as a Vpass statement", name)
		}
	}
}
