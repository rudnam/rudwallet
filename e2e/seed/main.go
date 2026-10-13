// Command seed fills an account with about six months of believable records
// through the API, so a preview has something in Stats, Trends, Records and
// Budgets. preview.sh runs it against its fresh database:
//
//	go run ./e2e/seed -url http://server:8080 -email preview@example.com -password preview-password
//
// The same records come out every time (a fixed random seed), dated back
// from today in the TZ time zone.
package main

import (
	"bytes"
	"encoding/json"
	"flag"
	"fmt"
	"log"
	"maps"
	"math/rand/v2"
	"net/http"
	"net/http/cookiejar"
	"os"
	"time"
	_ "time/tzdata" // the container has no zone files; TZ picks the user's zone
)

func main() {
	base := flag.String("url", "http://localhost:8080", "the app's address")
	email := flag.String("email", "preview@example.com", "account to fill")
	password := flag.String("password", "preview-password", "its password")
	months := flag.Int("months", 6, "months of history before this one")
	flag.Parse()

	jar, _ := cookiejar.New(nil)
	c := &client{base: *base, http: &http.Client{Jar: jar, Timeout: 30 * time.Second}}
	c.must("POST", "/api/login", map[string]string{"email": *email, "password": *password}, nil)

	var w struct {
		Accounts   []struct{ ID int64 }
		Categories []struct {
			ID   int64
			Name string
		}
	}
	c.must("GET", "/api/wallet", nil, &w)
	if len(w.Accounts) > 1 {
		log.Printf("%s already has accounts; not seeding it again", *email)
		return
	}
	cat := map[string]int64{}
	for _, x := range w.Categories {
		cat[x.Name] = x.ID
	}
	cash := w.Accounts[0].ID
	account := func(name, kind string, balance int64, color string) int64 {
		var a struct{ ID int64 }
		c.must("POST", "/api/accounts", map[string]any{"name": name, "kind": kind, "initial_balance": balance, "color": color}, &a)
		return a.ID
	}
	bank := account("MUFG", "bank", 420000, "#3b82c4")
	card := account("PayPay Card", "credit_card", 0, "#d9534f")
	paypay := account("PayPay", "e_money", 3000, "#e8833a")

	r := rand.New(rand.NewPCG(7, 11))
	now := time.Now()
	start := time.Date(now.Year(), now.Month()-time.Month(*months), 1, 0, 0, 0, 0, time.Local)
	n := 0
	add := func(kind string, amount int64, acct int64, category string, at time.Time, payee string, extra ...map[string]any) {
		if at.After(now) {
			return
		}
		rec := map[string]any{"kind": kind, "amount": amount, "account_id": acct, "occurred_at": at, "payee": payee}
		if category != "" {
			rec["category_id"] = cat[category]
		}
		for _, e := range extra {
			maps.Copy(rec, e)
		}
		c.must("POST", "/api/transactions", rec, nil)
		n++
	}
	between := func(lo, hi int64) int64 { return (lo + r.Int64N(hi-lo+1)) / 10 * 10 }
	pick := func(list ...string) string { return list[r.IntN(len(list))] }

	for m := start; !m.After(now); m = m.AddDate(0, 1, 0) {
		day := func(d, h, min int) time.Time { return time.Date(m.Year(), m.Month(), d, h, min, 0, 0, time.Local) }
		days := time.Date(m.Year(), m.Month()+1, 0, 0, 0, 0, 0, time.Local).Day()
		// Each month drifts a little, and the month before this one ate out a lot.
		eatOut := 1.0
		if prev := time.Date(now.Year(), now.Month()-1, 1, 0, 0, 0, 0, time.Local); m.Equal(prev) {
			eatOut = 1.7
		}

		// Regular money in and out.
		add("income", 285000, bank, "Salary", day(25, 9, 0), "Employer")
		add("expense", 72000, bank, "Housing", day(27, 10, 0), "Rent")
		add("expense", between(4800, 9200), card, "Bills & Utilities", day(14, 8, 0), "Chubu Electric")
		add("expense", between(2600, 3800), card, "Bills & Utilities", day(18, 8, 0), "Toho Gas")
		add("expense", 2970, card, "Bills & Utilities", day(20, 8, 0), "ahamo")
		add("expense", 11680, card, "Transport", day(1, 7, 40), "JR Central commuter pass")
		add("transfer", between(86000, 104000), bank, "", day(10, 9, 0), "Card payment", map[string]any{"to_account_id": card})
		add("transfer", 12000, bank, "", day(26, 12, 0), "Charge", map[string]any{"to_account_id": paypay})
		add("transfer", 10000, bank, "", day(5, 18, 30), "ATM", map[string]any{"to_account_id": cash})
		if m.Month()%3 == 0 {
			add("income", between(3000, 12000), bank, "Other income", day(15, 20, 0), "Mercari sale")
		}

		for d := 1; d <= days; d++ {
			wd := day(d, 0, 0).Weekday()
			// Groceries twice a week.
			if wd == time.Wednesday || wd == time.Saturday {
				add("expense", between(1800, 6200), card, "Groceries", day(d, 17, r.IntN(60)), pick("アピタ知立店", "イオン刈谷店", "業務スーパー", "Aldi"))
			}
			// Coffee and lunch on workdays, now and then.
			if wd != time.Saturday && wd != time.Sunday && r.Float64() < 0.45*eatOut {
				wallet := paypay
				if r.Float64() < 0.3 {
					wallet = cash
				}
				add("expense", between(380, 1200), wallet, "Food & Drinks", day(d, 12, r.IntN(50)), pick("セブン－イレブン", "ファミリーマート", "すき家", "コメダ珈琲店", "スターバックス"))
			}
			// Dinner out at weekends.
			if (wd == time.Friday || wd == time.Saturday) && r.Float64() < 0.5*eatOut {
				add("expense", between(1400, 4800), card, "Food & Drinks", day(d, 19, r.IntN(60)), pick("大戸屋 知立店", "一蘭", "スシロー", "鳥貴族"))
			}
			if r.Float64() < 0.06 {
				add("expense", between(1200, 14000), card, "Shopping", day(d, 15, r.IntN(60)), pick("Amazon", "ユニクロ", "無印良品", "ニトリ"))
			}
			if r.Float64() < 0.04 {
				add("expense", between(1200, 4500), card, "Entertainment", day(d, 20, r.IntN(60)), pick("TOHOシネマズ", "Steam", "カラオケ まねきねこ"))
			}
			if r.Float64() < 0.03 {
				add("expense", between(600, 3500), cash, "Health", day(d, 11, r.IntN(60)), pick("スギ薬局 大曽根店", "クリニック"))
			}
			if wd == time.Sunday && r.Float64() < 0.2 {
				add("expense", between(380, 1520), paypay, "Transport", day(d, 10, r.IntN(60)), "名鉄")
			}
		}
	}
	// One trip, two months ago.
	trip := time.Date(now.Year(), now.Month()-2, 12, 8, 0, 0, 0, time.Local)
	add("expense", 28400, card, "Travel", trip, "JR 新幹線 東京")
	add("expense", 18600, card, "Travel", trip.Add(10*time.Hour), "東横イン 東京駅")

	for _, b := range []map[string]any{
		{"name": "Everything", "category_id": nil, "amount": 200000},
		{"name": "Eating out", "category_id": cat["Food & Drinks"], "amount": 20000},
		{"name": "Groceries", "category_id": cat["Groceries"], "amount": 32000},
	} {
		c.must("POST", "/api/budgets", b, nil)
	}
	log.Printf("Seeded %s: 4 accounts, %d records, 3 budgets", *email, n)
}

type client struct {
	base string
	http *http.Client
}

// must calls the API, exiting on any failure.
func (c *client) must(method, path string, body, out any) {
	var buf bytes.Buffer
	if body != nil {
		if err := json.NewEncoder(&buf).Encode(body); err != nil {
			log.Fatal(err)
		}
	}
	req, err := http.NewRequest(method, c.base+path, &buf)
	if err != nil {
		log.Fatal(err)
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Origin", c.base) // the app's CSRF check
	res, err := c.http.Do(req)
	if err != nil {
		log.Fatal(err)
	}
	defer res.Body.Close()
	if res.StatusCode >= 300 {
		var e struct{ Error string }
		json.NewDecoder(res.Body).Decode(&e)
		fmt.Fprintf(os.Stderr, "%s %s: %d %s\n", method, path, res.StatusCode, e.Error)
		os.Exit(1)
	}
	if out != nil {
		if err := json.NewDecoder(res.Body).Decode(out); err != nil {
			log.Fatal(err)
		}
	}
}
