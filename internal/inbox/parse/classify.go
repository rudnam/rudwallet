package parse

import (
	"regexp"
	"strconv"
	"strings"
	"time"
)

// Kind says what an email turned out to be.
type Kind string

const (
	// KindPayment: money left (or, for a refund, came back to) the user.
	KindPayment Kind = "payment"
	// KindIgnored: a known sender's email that is not a payment, e.g. a
	// monthly statement, which would double-count the payments it lists.
	KindIgnored Kind = "ignored"
	// KindForwarding: Gmail asking to confirm forwarding to this address.
	KindForwarding Kind = "forwarding"
	// KindUnknown: no rule matched; the AI fallback or the user decides.
	KindUnknown Kind = "unknown"
)

// Payment is a payment read from an email.
type Payment struct {
	// Amount in minor units of Currency.
	Amount   int64
	Currency string
	// Refund is money coming back (recorded as income).
	Refund     bool
	Merchant   string
	OccurredAt time.Time
	// Source names what paid, as the email puts it (e.g. the card), so the
	// account can be learned once and reused.
	Source string
	Note   string
}

// Result is the outcome of classifying one email.
type Result struct {
	Kind    Kind
	Parser  string
	Payment Payment
	// Detail explains an ignored email, or carries Gmail's confirmation code.
	Detail     string
	ConfirmURL string
}

// Classify runs the built-in rules. Senders it doesn't know are KindUnknown.
func Classify(m Message) Result {
	switch {
	case m.From == "forwarding-noreply@google.com":
		return gmailForwarding(m)
	case strings.HasSuffix(m.From, "paypay-card.co.jp"):
		return payPayCard(m)
	}
	return Result{Kind: KindUnknown}
}

// ---------- PayPay Card (paypaycard-info@mail.paypay-card.co.jp) ----------

// jst is Japan time, which PayPay's timestamps are in. A fixed zone, because
// Japan has no daylight saving and the runtime image has no tz database.
var jst = time.FixedZone("JST", 9*60*60)

var (
	payPayDate   = regexp.MustCompile(`^(\d{4})年(\d{1,2})月(\d{1,2})日 (\d{1,2}):(\d{2})$`)
	payPayAmount = regexp.MustCompile(`^([\d,]+)円$`)
)

// payPayCard reads the 利用速報 (card usage alert) the card sends for every
// payment, in person or online. Its body is:
//
//	PayPayカード ゴールド（Visa）利用速報
//	IKEAオンライン
//	2026年10月11日 13:21
//	4,994円
func payPayCard(m Message) Result {
	const parser = "paypay-card"
	switch {
	case strings.Contains(m.Subject, "取消") || strings.Contains(m.Subject, "返品"):
		// A cancellation: let the AI fallback or the user look at it.
		return Result{Kind: KindUnknown}
	case strings.Contains(m.Subject, "本人認証"):
		return Result{Kind: KindIgnored, Parser: parser,
			Detail: "Purchase verification, not a payment. The usage alert that follows is recorded instead."}
	case strings.Contains(m.Subject, "請求"):
		return Result{Kind: KindIgnored, Parser: parser,
			Detail: "Monthly statement. Its payments were already recorded one by one."}
	case !strings.Contains(m.Subject, "利用速報"):
		return Result{Kind: KindIgnored, Parser: parser, Detail: "Not a payment alert."}
	}

	lines := strings.Split(m.Text, "\n")
	for i, line := range lines {
		d := payPayDate.FindStringSubmatch(line)
		if d == nil || i == 0 || i+1 >= len(lines) {
			continue
		}
		a := payPayAmount.FindStringSubmatch(lines[i+1])
		if a == nil {
			continue
		}
		amount, err := strconv.ParseInt(strings.ReplaceAll(a[1], ",", ""), 10, 64)
		if err != nil {
			continue
		}
		n := func(s string) int { v, _ := strconv.Atoi(s); return v }
		p := Payment{
			Amount:     amount,
			Currency:   "JPY",
			Merchant:   lines[i-1],
			OccurredAt: time.Date(n(d[1]), time.Month(n(d[2])), n(d[3]), n(d[4]), n(d[5]), 0, 0, jst),
			Source:     strings.TrimSpace(strings.TrimSuffix(strings.TrimSpace(m.Subject), "利用速報")),
		}
		// "大戸屋 知立店／NFC": the card was tapped (e.g. through Google Wallet).
		if merchant, ok := strings.CutSuffix(p.Merchant, "／NFC"); ok {
			p.Merchant, p.Note = strings.TrimSpace(merchant), "Tap to pay"
		}
		return Result{Kind: KindPayment, Parser: parser, Payment: p}
	}
	// The layout changed: hand it on rather than guess.
	return Result{Kind: KindUnknown, Parser: parser}
}

// ---------- Gmail forwarding confirmation ----------

var (
	gmailCode = regexp.MustCompile(`\(#(\d+)\)`)
	gmailLink = regexp.MustCompile(`https://mail(?:-settings)?\.google\.com/\S+`)
)

func gmailForwarding(m Message) Result {
	r := Result{Kind: KindForwarding, Parser: "gmail-forwarding"}
	if c := gmailCode.FindStringSubmatch(m.Subject); c != nil {
		r.Detail = c[1]
	}
	r.ConfirmURL = strings.TrimRight(gmailLink.FindString(m.Text), ".,)>")
	return r
}
