// Command seed fills an account with about six months of believable records
// through the API, so a preview has something in Stats, Trends, Records and
// Budgets: the same sample data the demo uses (internal/sample). preview.sh
// runs it against its fresh database:
//
//	go run ./e2e/seed -url http://server:8080 -email preview@example.com -password preview-password [-lang ja]
//
// The same records come out every time (a fixed random seed), dated back
// from today in the TZ time zone. With -lang ja, a new wallet's categories
// and the sample's names are in Japanese.
package main

import (
	"bytes"
	"encoding/json"
	"flag"
	"fmt"
	"log"
	"net/http"
	"net/http/cookiejar"
	"os"
	"time"
	_ "time/tzdata" // the container has no zone files; TZ picks the user's zone

	"github.com/rudnam/wallet-app/internal/sample"
	"github.com/rudnam/wallet-app/internal/wallet"
)

func main() {
	base := flag.String("url", "http://localhost:8080", "the app's address")
	email := flag.String("email", "preview@example.com", "account to fill")
	password := flag.String("password", "preview-password", "its password")
	months := flag.Int("months", 6, "months of history before this one")
	lang := flag.String("lang", "en", "language of the names: en or ja")
	flag.Parse()

	jar, _ := cookiejar.New(nil)
	c := &client{base: *base, lang: *lang, http: &http.Client{Jar: jar, Timeout: 30 * time.Second}}
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
	byName := map[string]int64{}
	for _, x := range w.Categories {
		byName[x.Name] = x.ID
	}
	english, local := wallet.DefaultCategoryNames("en"), wallet.DefaultCategoryNames(*lang)
	category := func(name string) any {
		for i, n := range english {
			if n == name {
				if id, ok := byName[local[i]]; ok {
					return id
				}
			}
		}
		return nil
	}

	s := sample.New(time.Now(), *months, *lang)
	accounts := map[string]int64{sample.AccountCash: w.Accounts[0].ID}
	for _, a := range s.Accounts {
		var created struct{ ID int64 }
		c.must("POST", "/api/accounts", map[string]any{"name": a.Name, "kind": a.Kind, "initial_balance": a.Balance, "color": a.Color}, &created)
		accounts[a.Key] = created.ID
	}
	for _, r := range s.Records {
		rec := map[string]any{"kind": r.Kind, "amount": r.Amount, "account_id": accounts[r.Account], "occurred_at": r.At, "payee": r.Payee}
		if r.ToAccount != "" {
			rec["to_account_id"] = accounts[r.ToAccount]
		}
		if r.Category != "" {
			rec["category_id"] = category(r.Category)
		}
		c.must("POST", "/api/transactions", rec, nil)
	}
	for _, b := range s.Budgets {
		var cat any
		if b.Category != "" {
			cat = category(b.Category)
		}
		c.must("POST", "/api/budgets", map[string]any{"name": b.Name, "category_id": cat, "amount": b.Amount}, nil)
	}
	// The sample's emailed payments are left to preview.sh, which delivers
	// real sample emails through the inbound webhook.
	log.Printf("Seeded %s: %d accounts, %d records, %d budgets", *email, len(s.Accounts)+1, len(s.Records), len(s.Budgets))
}

type client struct {
	base string
	lang string // sent as Accept-Language, which names a new wallet's categories
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
	req.Header.Set("Accept-Language", c.lang)
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
