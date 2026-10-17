package notify

import (
	"testing"
	"time"
)

const (
	gw = "com.google.android.apps.walletnfcrel"
	pp = "jp.ne.paypay.android.app"
)

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

		// PayPay, as the app words them (full-width colons and all).
		{name: "paypay card", pkg: pp, title: "支払いを受け付けました", text: "お店：ベルマート豊橋店\n金額：1,291円\n日時：2026年10月16日 20:19",
			status: "payment", amount: 1291, currency: "JPY", merchant: "ベルマート豊橋店", source: "PayPay Card"},
		{name: "paypay balance", pkg: pp, title: "PayPay", text: "取引が完了しました。\n金額：1,550円\n取引番号：05093837355250737154\n店舗名：GOアプリ",
			status: "payment", amount: 1550, currency: "JPY", merchant: "GOアプリ", source: "PayPay"},
		{name: "paypay online", pkg: pp, title: "PayPay", text: "Uber Eatsへの支払いが完了しました。\n金額: 3,994円\n取引番号: 05093583565634363396",
			status: "payment", amount: 3994, currency: "JPY", merchant: "Uber Eats", source: "PayPay"},
		{name: "paypay refund", pkg: pp, title: "支払いを受け付けました", text: "お店：スギ薬局グループ\n金額：7,480円\n返金されました",
			status: "payment", amount: 7480, currency: "JPY", merchant: "スギ薬局グループ", source: "PayPay Card", refund: true},
		{name: "paypay top-up", pkg: pp, title: "PayPay", text: "チャージが完了しました。\n金額：5,000円",
			status: "ignored"},
		{name: "paypay offer", pkg: pp, title: "PayPay", text: "今だけ最大10%戻ってくる！",
			status: "ignored"},
		{name: "paypay without the amount", pkg: pp, title: "PayPay", text: "取引が完了しました。",
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

// A PayPay Card payment carries its own time, in Japan time.
func TestParsePayPayTime(t *testing.T) {
	r := Parse(pp, "支払いを受け付けました", "お店：Visa国内加盟店\n金額：864円\n日時：2026年10月16日 18:20")
	if want := time.Date(2026, 10, 16, 9, 20, 0, 0, time.UTC); !r.Payment.OccurredAt.Equal(want) {
		t.Errorf("occurred at %v, want %v", r.Payment.OccurredAt, want)
	}
}
