package notify

import (
	"regexp"
	"strconv"
	"strings"
	"time"

	"github.com/rudnam/wallet-app/internal/wallet"
)

// Apps whose notifications the server reads, by Android package. The app
// sends only these (PaymentListener.java); add a package there too.
var apps = map[string]string{
	"com.google.android.apps.walletnfcrel": "Google Wallet",
	payPayApp:                              "PayPay",
}

const payPayApp = "jp.ne.paypay.android.app"

// Read is what a notification turned out to be.
type Read struct {
	// Status is "payment", "ignored" or "unparsed".
	Status  string
	Detail  string
	Payment wallet.Payment
}

var (
	// An amount with its currency before or after it: ¥1,280, $4.50, 1,280円, 12.00 EUR.
	amountRe = regexp.MustCompile(`([¥￥$€£₩])\s?(\d[\d,]*(?:\.\d+)?)|(\d[\d,]*(?:\.\d+)?)\s?(円|(?:JPY|USD|EUR|GBP|KRW)\b)`)
	// The card, as Google Wallet abbreviates it: "Visa •••• 1234", "Mastercard ··5678".
	cardRe = regexp.MustCompile(`(?i)\b(visa|mastercard|master card|jcb|american express|amex|diners(?: club)?|discover)?\s*[•·*●]{2,}\s*(\d{4})`)
	// Notifications with an amount that aren't a payment made: a declined
	// card, a transit card's balance or top-up.
	notPaymentRe = regexp.MustCompile(`(?i)declin|fail|couldn.t|could not|wasn.t|not complete|balance|top.?up|reload|拒否|失敗|できません|できませんでした|残高|チャージ|入金`)
	refundRe     = regexp.MustCompile(`(?i)refund|返金|返品`)
	atRe         = regexp.MustCompile(`(?i)\b(?:at|@)\s+(.+)$`)
)

var symbols = map[string]string{"¥": "JPY", "￥": "JPY", "円": "JPY", "$": "USD", "€": "EUR", "£": "GBP", "₩": "KRW"}

// Parse reads a payment app's notification. Google Wallet's payment
// notifications name the shop in the title and the amount and card in the
// text, e.g. "Lawson" / "¥550 with Visa •••• 1234".
func Parse(pkg, title, text string) Read {
	app, ok := apps[pkg]
	if !ok {
		return Read{Status: "ignored", Detail: "Not from a payment app this server reads."}
	}
	title, text = halfWidth(strings.TrimSpace(title)), halfWidth(strings.TrimSpace(text))
	if pkg == payPayApp {
		return payPay(title, text)
	}
	all := title + "\n" + text
	m := amountRe.FindStringSubmatch(all)
	if m == nil {
		return Read{Status: "ignored", Detail: "No amount, so not a payment."}
	}
	if notPaymentRe.MatchString(all) {
		return Read{Status: "ignored", Detail: "Not a payment made (declined, a balance or a top-up)."}
	}
	symbol, number := m[1], m[2]
	if symbol == "" {
		symbol, number = m[4], m[3]
	}
	currency := symbols[symbol]
	if currency == "" {
		currency = symbol // an ISO code
	}
	amount, ok := minorUnits(number, wallet.CurrencyExponent(currency))
	if !ok || amount <= 0 {
		return Read{Status: "unparsed", Detail: "Couldn't read the amount " + m[0] + "."}
	}

	p := wallet.Payment{Amount: amount, Currency: currency, Refund: refundRe.MatchString(all), Source: app}
	if c := cardRe.FindStringSubmatch(all); c != nil {
		card := "••" + c[2]
		if c[1] != "" {
			card = c[1] + " " + card
		}
		p.Source = app + " · " + card
	}
	// The shop is the title, unless the title is the amount ("¥550 at Lawson").
	switch {
	case !amountRe.MatchString(title) && title != app:
		p.Merchant = title
	default:
		for _, s := range []string{title, text} {
			if at := atRe.FindStringSubmatch(s); at != nil {
				p.Merchant = strings.TrimSpace(cardRe.ReplaceAllString(at[1], ""))
				break
			}
		}
	}
	return Read{Status: "payment", Payment: p}
}

var (
	// A labelled line of a PayPay notification, e.g. "金額:1,291円" (after
	// halfWidth, so the colon is ASCII).
	payPayField = regexp.MustCompile(`(?m)^\s*(お店|店舗名|金額|日時)\s*:\s*(.+?)\s*$`)
	// "Uber Eatsへの支払いが完了しました。"
	payPayTo = regexp.MustCompile(`^(.+?)への支払いが完了しました`)
	// "2026年10月16日 20:19", or the date alone: "2026年10月15日".
	payPayTime = regexp.MustCompile(`^(\d{4})年(\d{1,2})月(\d{1,2})日(?:\s*(\d{1,2}):(\d{2}))?$`)
	// 金額's value: yen, then what was charged abroad, if anything:
	// "1,291円", or "3,477円(22 USD)" (after halfWidth).
	payPayAmount = regexp.MustCompile(`^(\d[\d,]*)\s*円\s*(?:\((.+?)\))?$`)
	// A payment that was cancelled is money back.
	payPayCancel = regexp.MustCompile(`取り?消`)
)

// jst is Japan time, which PayPay's times are in. Japan has no daylight saving.
var jst = time.FixedZone("JST", 9*60*60)

// payPay reads the PayPay app's payment notifications. A PayPay Card
// payment is titled 支払いを受け付けました, with お店, 金額 and 日時 lines;
// a billed one (a phone bill) is titled 支払いが完了しました, with the same
// lines and a 日時 without the time.
// A payment from the PayPay balance is titled PayPay and reads
// 取引が完了しました。 with 金額, 取引番号 and 店舗名 lines, or
// "Uber Eatsへの支払いが完了しました。" with 金額 and 取引番号. The two are
// told apart (Source), so each can go to its own account.
// A card payment abroad gives the amount in yen and what was charged:
// 金額:3,477円(22 USD). It is recorded in yen, noting the foreign amount.
func payPay(title, text string) Read {
	all := title + "\n" + text
	fields := map[string]string{}
	for _, m := range payPayField.FindAllStringSubmatch(all, -1) {
		fields[m[1]] = m[2]
	}
	p := wallet.Payment{Currency: "JPY", Refund: refundRe.MatchString(all) || payPayCancel.MatchString(all)}
	switch {
	case strings.Contains(title, "支払いを受け付けました"), strings.Contains(title, "支払いが完了しました"):
		p.Source, p.Merchant = "PayPay Card", fields["お店"]
		if m := payPayTime.FindStringSubmatch(fields["日時"]); m != nil {
			n := func(i int) int { v, _ := strconv.Atoi(m[i]); return v }
			if m[4] == "" { // the date alone: noon that day, unless the notification came that day
				p.Day = time.Date(n(1), time.Month(n(2)), n(3), 0, 0, 0, 0, jst)
				p.OccurredAt = p.Day.Add(12 * time.Hour)
			} else {
				p.OccurredAt = time.Date(n(1), time.Month(n(2)), n(3), n(4), n(5), 0, 0, jst)
			}
		}
	case strings.Contains(text, "取引が完了しました"), strings.Contains(text, "支払いが完了しました"):
		p.Source, p.Merchant = "PayPay", fields["店舗名"]
		if m := payPayTo.FindStringSubmatch(text); m != nil && p.Merchant == "" {
			p.Merchant = strings.TrimSpace(m[1])
		}
	default:
		return Read{Status: "ignored", Detail: "Not a PayPay payment."}
	}
	if notPaymentRe.MatchString(all) {
		return Read{Status: "ignored", Detail: "Not a payment made (declined, a balance or a top-up)."}
	}
	amount, ok := fields["金額"]
	if !ok {
		return Read{Status: "unparsed", Detail: "No amount in the notification."}
	}
	m := payPayAmount.FindStringSubmatch(amount)
	if m == nil {
		return Read{Status: "unparsed", Detail: "Couldn't read the amount " + amount + "."}
	}
	if p.Amount, ok = minorUnits(m[1], 0); !ok || p.Amount <= 0 {
		return Read{Status: "unparsed", Detail: "Couldn't read the amount " + amount + "."}
	}
	if m[2] != "" {
		p.Note = "Charged " + strings.TrimSpace(m[2]) + "."
	}
	return Read{Status: "payment", Payment: p}
}

// minorUnits turns "1,280" or "4.50" into an amount in minor units.
func minorUnits(s string, exponent int) (int64, bool) {
	whole, frac, _ := strings.Cut(strings.ReplaceAll(s, ",", ""), ".")
	if len(frac) > exponent {
		if strings.Trim(frac[exponent:], "0") != "" {
			return 0, false // more decimals than the currency has
		}
		frac = frac[:exponent]
	}
	frac += strings.Repeat("0", exponent-len(frac))
	n, err := strconv.ParseInt(whole+frac, 10, 64)
	return n, err == nil
}

// halfWidth turns full-width letters, digits and spaces into ASCII.
func halfWidth(s string) string {
	return strings.Map(func(r rune) rune {
		switch {
		case r >= 0xFF01 && r <= 0xFF5E:
			return r - 0xFEE0
		case r == 0x3000:
			return ' '
		}
		return r
	}, s)
}
