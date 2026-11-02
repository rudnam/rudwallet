package server_test

import (
	"strings"
	"testing"
	"time"

	"github.com/rudnam/wallet-app/internal/wallet"
)

// A phone that was offline sends its new records later, and may send one
// again when an answer was lost: the client_id it names them with keeps
// each to one record.
func TestCreateWithClientIDIsSavedOnce(t *testing.T) {
	ts := newTestServer(t)
	alice, bob := newClient(t, ts), newClient(t, ts)
	alice.signup("alice@example.com")
	bob.signup("bob@example.com")
	cash := overview(t, alice).Accounts[0].ID
	body := map[string]any{"kind": "expense", "amount": 1200, "account_id": cash,
		"occurred_at": time.Now().Add(-time.Hour).UTC(), "payee": "Ramen Taro", "client_id": "3f2c9a4e-0d1b-4c5e-9a7f-1b2c3d4e5f60"}

	var first, again wallet.Transaction
	if code := alice.do("POST", "/api/transactions", body, &first); code != 201 {
		t.Fatalf("create: %d", code)
	}
	if code := alice.do("POST", "/api/transactions", body, &again); code != 201 {
		t.Fatalf("create again: %d", code)
	}
	if again.ID != first.ID || again.Payee != "Ramen Taro" {
		t.Errorf("sent again: got record %d (%q), want %d", again.ID, again.Payee, first.ID)
	}
	var list []wallet.Transaction
	alice.do("GET", "/api/transactions", nil, &list)
	if len(list) != 1 {
		t.Errorf("%d records, want 1", len(list))
	}
	if o := overview(t, alice); o.Accounts[0].Balance != -1200 {
		t.Errorf("balance %d, want -1200", o.Accounts[0].Balance)
	}

	// The same name from someone else is their own record.
	bobCash := overview(t, bob).Accounts[0].ID
	body["account_id"] = bobCash
	var theirs wallet.Transaction
	if code := bob.do("POST", "/api/transactions", body, &theirs); code != 201 || theirs.ID == first.ID {
		t.Errorf("bob's record: %d, id %d", code, theirs.ID)
	}

	// Without one, every send is a new record, as before.
	delete(body, "client_id")
	body["account_id"] = cash
	alice.do("POST", "/api/transactions", body, nil)
	alice.do("POST", "/api/transactions", body, nil)
	alice.do("GET", "/api/transactions", nil, &list)
	if len(list) != 3 {
		t.Errorf("%d records, want 3", len(list))
	}

	body["client_id"] = strings.Repeat("x", 65)
	if code := alice.do("POST", "/api/transactions", body, nil); code != 400 {
		t.Errorf("long client_id: %d, want 400", code)
	}
}
