package wallet

import (
	"encoding/hex"
	"slices"
	"testing"
	"time"
)

// icBlock is a history entry as a card stores it (see importic.go). A shop
// entry (terminal C7) gets the time hh:mm:ss.
func icBlock(terminal, process byte, day string, hhmmss string, balance, seq int) string {
	d, err := time.Parse(time.DateOnly, day)
	if err != nil {
		panic(err)
	}
	b := make([]byte, 16)
	b[0], b[1] = terminal, process
	y, m := d.Year()-2000, int(d.Month())
	b[4], b[5] = byte(y<<1|m>>3), byte((m&7)<<5|d.Day())
	if hhmmss != "" {
		t, err := time.Parse(time.TimeOnly, hhmmss)
		if err != nil {
			panic(err)
		}
		b[6], b[7] = byte(t.Hour()<<3|t.Minute()>>3), byte((t.Minute()&7)<<5|t.Second()/2)
	}
	b[10], b[11] = byte(balance), byte(balance>>8)
	b[13], b[14] = byte(seq>>8), byte(seq)
	return hex.EncodeToString(b)
}

// icHistory is a card's history, newest first as the card gives it: a fare
// to start, a top-up, a train fare, a bus fare, a shop payment, a refund,
// and a block never written.
var icHistory = []string{
	"00000000000000000000000000000000",
	icBlock(0x16, 0x04, "2026-10-04", "", 2480, 105),
	icBlock(0xc7, 0x46, "2026-10-03", "13:45:30", 2430, 104),
	icBlock(0x05, 0x0d, "2026-10-03", "", 2580, 103),
	icBlock(0x16, 0x01, "2026-10-02", "", 2790, 102),
	icBlock(0x08, 0x02, "2026-10-02", "", 3000, 101),
	icBlock(0x16, 0x01, "2026-10-01", "", 1000, 100),
}

func TestReadICCard(t *testing.T) {
	idm, rows, err := readICCard(ICCardRead{IDm: "01120312ab34cd56", History: icHistory})
	if err != nil {
		t.Fatal(err)
	}
	if idm != "01120312AB34CD56" {
		t.Errorf("idm = %s", idm)
	}
	type entry struct {
		when          string
		kind, group   string
		amount, saldo int64
	}
	var got []entry
	for _, r := range rows {
		rec := classifyIC(r)
		got = append(got, entry{r.when.Format("2006-01-02 15:04:05"), rec.kind, rec.group, rec.amount, r.balance})
	}
	want := []entry{
		{"2026-10-01 12:00:00", "expense", icTrain, 0, 1000}, // the start: no amount
		{"2026-10-02 12:00:00", "transfer", "", 2000, 3000},
		{"2026-10-02 12:00:00", "expense", icTrain, 210, 2790},
		{"2026-10-03 12:00:00", "expense", icBus, 210, 2580},
		{"2026-10-03 13:45:30", "expense", icShop, 150, 2430},
		{"2026-10-04 12:00:00", "income", icRefund, 50, 2480},
	}
	if !slices.Equal(got, want) {
		t.Errorf("entries:\n got %v\nwant %v", got, want)
	}
	if !rows[0].start || rows[1].start {
		t.Error("only the oldest entry starts the history")
	}
	if rows[2].key != "ic:01120312AB34CD56:2026-10-02:102:2790" {
		t.Errorf("key = %s", rows[2].key)
	}
	// The same entries read again have the same keys.
	_, again, _ := readICCard(ICCardRead{IDm: idm, History: icHistory[1:4]})
	if again[0].key != rows[3].key || again[2].key != rows[5].key {
		t.Error("keys change between reads")
	}
}

func TestReadICCardRejects(t *testing.T) {
	good := icBlock(0x16, 0x01, "2026-10-01", "", 1000, 1)
	for name, read := range map[string]ICCardRead{
		"no idm":      {History: []string{good}},
		"short idm":   {IDm: "0112", History: []string{good}},
		"no history":  {IDm: "0112031200000000"},
		"only blanks": {IDm: "0112031200000000", History: []string{"00000000000000000000000000000000"}},
		"short block": {IDm: "0112031200000000", History: []string{good[:30]}},
		"not hex":     {IDm: "0112031200000000", History: []string{"zz" + good[2:]}},
		"bad month":   {IDm: "0112031200000000", History: []string{"000000003400" + good[12:]}},
	} {
		if _, _, err := readICCard(read); err == nil {
			t.Errorf("%s: no error", name)
		}
	}
}
