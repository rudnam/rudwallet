// Package demo fills a visitor's throwaway demo wallet with months of
// sample data (internal/sample), so they can see the app as it looks in
// use without making an account. The account itself (no password, deleted
// after a day) is internal/account's; see its demo.go.
package demo

import (
	"context"
	"fmt"
	"time"

	"github.com/rudnam/wallet-app/internal/sample"
	"github.com/rudnam/wallet-app/internal/wallet"
)

// months of history before the current one.
const months = 6

// Filler returns the function that fills a new demo wallet, named in lang
// ("en" or "ja") and dated in the visitor's time zone.
func Filler(w *wallet.Store) func(ctx context.Context, userID int64, lang string, zone *time.Location) error {
	return func(ctx context.Context, userID int64, lang string, zone *time.Location) error {
		return fill(ctx, w, userID, lang, time.Now().In(zone))
	}
}

func fill(ctx context.Context, w *wallet.Store, userID int64, lang string, now time.Time) error {
	if err := w.SetUp(ctx, userID, lang); err != nil {
		return err
	}
	s := sample.New(now, months, lang)

	// The wallet's own accounts and categories, by the sample's keys.
	existing, err := w.Accounts(ctx, userID)
	if err != nil || len(existing) == 0 {
		return fmt.Errorf("demo wallet has no cash account: %v", err)
	}
	accounts := map[string]int64{sample.AccountCash: existing[0].ID}
	for _, a := range s.Accounts {
		created, err := w.CreateAccount(ctx, userID, wallet.Account{Name: a.Name, Kind: a.Kind, InitialBalance: a.Balance, Color: a.Color})
		if err != nil {
			return fmt.Errorf("account %s: %w", a.Key, err)
		}
		accounts[a.Key] = created.ID
	}
	// Emailed payments go to the card, as they would once the person picked it.
	settings, err := w.Settings(ctx, userID)
	if err != nil {
		return err
	}
	card := accounts[sample.AccountCard]
	settings.DefaultAccountID = &card
	if err := w.UpdateSettings(ctx, userID, settings); err != nil {
		return err
	}
	cats, err := w.Categories(ctx, userID)
	if err != nil {
		return err
	}
	byName := map[string]int64{}
	for _, c := range cats {
		byName[c.Name] = c.ID
	}
	english, local := wallet.DefaultCategoryNames("en"), wallet.DefaultCategoryNames(lang)
	category := func(name string) *int64 {
		for i, n := range english {
			if n == name {
				if id, ok := byName[local[i]]; ok {
					return &id
				}
			}
		}
		return nil
	}

	records := make([]wallet.Transaction, 0, len(s.Records))
	for _, r := range s.Records {
		t := wallet.Transaction{Kind: r.Kind, Amount: r.Amount, AccountID: accounts[r.Account], OccurredAt: r.At, Payee: r.Payee}
		if r.ToAccount != "" {
			to := accounts[r.ToAccount]
			t.ToAccountID = &to
		}
		if r.Category != "" {
			t.CategoryID = category(r.Category)
		}
		records = append(records, t)
	}
	if err := w.CreateMany(ctx, userID, records); err != nil {
		return err
	}
	for _, b := range s.Budgets {
		var cat *int64
		if b.Category != "" {
			cat = category(b.Category)
		}
		if _, err := w.CreateBudget(ctx, userID, wallet.Budget{Name: b.Name, CategoryID: cat, Amount: b.Amount}); err != nil {
			return fmt.Errorf("budget %s: %w", b.Name, err)
		}
	}
	// Payments from the card's emails, waiting under To review as real ones would.
	for _, p := range s.Review {
		if _, _, err := w.CreateFromEmail(ctx, userID, wallet.Payment{
			Amount: p.Amount, Currency: "JPY", Merchant: p.Merchant, OccurredAt: p.At, Source: p.Source,
		}); err != nil {
			return fmt.Errorf("payment %s: %w", p.Merchant, err)
		}
	}
	return nil
}
