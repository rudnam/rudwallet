package parse

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func read(t *testing.T, name string) Message {
	t.Helper()
	raw, err := os.ReadFile(filepath.Join("testdata", name))
	if err != nil {
		t.Fatal(err)
	}
	m, err := Read(raw)
	if err != nil {
		t.Fatalf("%s: %v", name, err)
	}
	return m
}

func TestPayPayUsageAlert(t *testing.T) {
	m := read(t, "paypay-usage.eml")
	if m.From != "paypaycard-info@mail.paypay-card.co.jp" {
		t.Errorf("from = %q (the ISO-2022-JP display name must not break it)", m.From)
	}
	if m.Subject != "PayPayカード ゴールド（Visa）利用速報" || m.MessageID != "usage-1@ap-northeast-1.amazonses.com" {
		t.Errorf("subject %q, id %q", m.Subject, m.MessageID)
	}
	r := Classify(m)
	want := Payment{
		Amount: 3280, Currency: "JPY", Merchant: "テスト書店オンライン",
		OccurredAt: time.Date(2026, 10, 11, 4, 21, 0, 0, time.UTC),
		Source:     "PayPayカード ゴールド（Visa）",
	}
	if r.Kind != KindPayment || r.Parser != "paypay-card" {
		t.Fatalf("result %+v", r)
	}
	if !r.Payment.OccurredAt.Equal(want.OccurredAt) {
		t.Errorf("time %v, want %v (13:21 JST)", r.Payment.OccurredAt, want.OccurredAt)
	}
	r.Payment.OccurredAt = want.OccurredAt
	if r.Payment != want {
		t.Errorf("payment\n got %+v\nwant %+v", r.Payment, want)
	}
}

func TestPayPayTapToPay(t *testing.T) {
	r := Classify(read(t, "paypay-nfc.eml"))
	if r.Kind != KindPayment || r.Payment.Merchant != "定食屋 テスト店" || r.Payment.Note != "Tap to pay" || r.Payment.Amount != 1820 {
		t.Fatalf("%+v", r)
	}
	if h, m, _ := r.Payment.OccurredAt.In(jst).Clock(); h != 7 || m != 56 {
		t.Errorf("single-digit hour: %v", r.Payment.OccurredAt)
	}
}

func TestPayPayHTMLOnly(t *testing.T) {
	r := Classify(read(t, "paypay-html-only.eml"))
	if r.Kind != KindPayment || r.Payment.Merchant != "セブン－イレブン" || r.Payment.Amount != 429 || r.Payment.Source != "PayPayカード（Visa）" {
		t.Fatalf("%+v", r)
	}
}

func TestPayPayNonPayments(t *testing.T) {
	for _, name := range []string{"paypay-verify.eml", "paypay-statement.eml"} {
		if r := Classify(read(t, name)); r.Kind != KindIgnored || r.Detail == "" {
			t.Errorf("%s: %+v, want ignored with a reason", name, r)
		}
	}
}

func TestPayPayChangedLayoutIsNotGuessed(t *testing.T) {
	m := Message{From: "paypaycard-info@mail.paypay-card.co.jp", Subject: "PayPayカード 利用速報", Text: "something new\n1,000円"}
	if r := Classify(m); r.Kind != KindUnknown {
		t.Errorf("%+v, want unknown", r)
	}
}

func TestGmailForwardingConfirmation(t *testing.T) {
	r := Classify(read(t, "gmail-forwarding.eml"))
	if r.Kind != KindForwarding || r.Detail != "518432907" ||
		r.ConfirmURL != "https://mail-settings.google.com/mail/vf-%5BANGjdJ_example%5D-abc" {
		t.Fatalf("%+v", r)
	}
}

func TestForwardedByHand(t *testing.T) {
	m := read(t, "forwarded-by-hand.eml")
	if m.From != "paypaycard-info@mail.paypay-card.co.jp" || !strings.HasSuffix(m.Subject, "利用速報") {
		t.Fatalf("not unwrapped: from %q subject %q", m.From, m.Subject)
	}
	if r := Classify(m); r.Kind != KindPayment || r.Payment.Amount != 1025 || r.Payment.Merchant != "テスト薬局 駅前店" {
		t.Fatalf("%+v", r)
	}
}

func TestUnknownSender(t *testing.T) {
	m := read(t, "unknown-receipt.eml")
	if m.MessageID == "" || !strings.HasPrefix(m.MessageID, "sha256:") {
		t.Errorf("no Message-ID should give a content hash, got %q", m.MessageID)
	}
	if !strings.Contains(m.Text, "2,500 JPY") {
		t.Errorf("text %q", m.Text)
	}
	if r := Classify(m); r.Kind != KindUnknown {
		t.Errorf("%+v", r)
	}
}

func TestNotAnEmail(t *testing.T) {
	if _, err := Read([]byte("hello")); err != ErrNotEmail {
		t.Errorf("err = %v", err)
	}
}

func TestHTMLToText(t *testing.T) {
	got := normalize(HTMLToText(`<style>x{}</style><p>Total&nbsp;&yen;1,200</p><div>Shop &amp; Co</div><script>alert(1)</script>`))
	if got != "Total ¥1,200\nShop & Co" {
		t.Errorf("%q", got)
	}
}

// TestRealEmail checks a real email kept outside the repo, when one is given:
// PARSE_REAL_EMAIL=/path/to/message.eml go test ./internal/inbox/parse
func TestRealEmail(t *testing.T) {
	path := os.Getenv("PARSE_REAL_EMAIL")
	if path == "" {
		t.Skip("PARSE_REAL_EMAIL not set")
	}
	raw, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	m, err := Read(raw)
	if err != nil {
		t.Fatal(err)
	}
	t.Logf("from %s, subject %q -> %+v", m.From, m.Subject, Classify(m))
}
