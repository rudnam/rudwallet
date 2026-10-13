package notify

import "testing"

const gw = "com.google.android.apps.walletnfcrel"

func TestParse(t *testing.T) {
	tests := []struct {
		name, pkg, title, text string
		status                 string
		amount                 int64
		currency, merchant     string
		source                 string
		refund                 bool
	}{
		{name: "english", pkg: gw, title: "Lawson", text: "¥550 with Visa •••• 1234",
			status: "payment", amount: 550, currency: "JPY", merchant: "Lawson", source: "Google Wallet · Visa ••1234"},
		{name: "japanese", pkg: gw, title: "ファミリーマート", text: "Mastercard ••5678 で ¥1,280 のお支払い",
			status: "payment", amount: 1280, currency: "JPY", merchant: "ファミリーマート", source: "Google Wallet · Mastercard ••5678"},
		{name: "yen after, full width", pkg: gw, title: "セブン-イレブン", text: "１，０２０円",
			status: "payment", amount: 1020, currency: "JPY", merchant: "セブン-イレブン", source: "Google Wallet"},
		{name: "dollars", pkg: gw, title: "Blue Bottle Coffee", text: "$4.50 with Visa ••••1234",
			status: "payment", amount: 450, currency: "USD", merchant: "Blue Bottle Coffee", source: "Google Wallet · Visa ••1234"},
		{name: "amount in the title", pkg: gw, title: "¥680 at Tully's Coffee", text: "Visa •••• 1234",
			status: "payment", amount: 680, currency: "JPY", merchant: "Tully's Coffee", source: "Google Wallet · Visa ••1234"},
		{name: "refund", pkg: gw, title: "Uniqlo", text: "Refund of ¥2,990 to Visa •••• 1234",
			status: "payment", amount: 2990, currency: "JPY", merchant: "Uniqlo", source: "Google Wallet · Visa ••1234", refund: true},
		{name: "declined", pkg: gw, title: "Lawson", text: "Payment of ¥550 declined",
			status: "ignored"},
		{name: "transit balance", pkg: gw, title: "Suica", text: "残高 ¥1,234",
			status: "ignored"},
		{name: "no amount", pkg: gw, title: "Card added", text: "Visa •••• 1234 is ready to use",
			status: "ignored"},
		{name: "other app", pkg: "com.example.chat", title: "Bob", text: "Lunch was ¥1,200",
			status: "ignored"},
		{name: "too many decimals", pkg: gw, title: "Shop", text: "¥550.5",
			status: "unparsed"},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			r := Parse(tc.pkg, tc.title, tc.text)
			if r.Status != tc.status {
				t.Fatalf("status %q (%s), want %q", r.Status, r.Detail, tc.status)
			}
			if r.Status != "payment" {
				return
			}
			p := r.Payment
			if p.Amount != tc.amount || p.Currency != tc.currency || p.Merchant != tc.merchant || p.Source != tc.source || p.Refund != tc.refund {
				t.Errorf("payment %+v", p)
			}
		})
	}
}
