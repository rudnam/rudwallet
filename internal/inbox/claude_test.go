package inbox

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/anthropics/anthropic-sdk-go/option"

	"github.com/rudnam/wallet-app/internal/inbox/parse"
)

// fakeClaude answers like the Messages API with the given JSON text, and
// keeps the request it got.
func fakeClaude(t *testing.T, answer string, stopReason string) (*httptest.Server, *map[string]any, *http.Header) {
	t.Helper()
	var body map[string]any
	var hdr http.Header
	ts := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/v1/messages" {
			t.Errorf("path %s", r.URL.Path)
		}
		hdr = r.Header.Clone()
		b, _ := io.ReadAll(r.Body)
		json.Unmarshal(b, &body)
		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(map[string]any{
			"id": "msg_1", "type": "message", "role": "assistant", "model": "claude-opus-5-5",
			"stop_reason": stopReason,
			"content":     []map[string]any{{"type": "text", "text": answer}},
			"usage":       map[string]any{"input_tokens": 100, "output_tokens": 20},
		})
	}))
	t.Cleanup(ts.Close)
	return ts, &body, &hdr
}

var receipt = parse.Message{
	From: "orders@shop.example", Subject: "Your receipt",
	Date: time.Date(2026, 10, 11, 7, 0, 0, 0, time.UTC),
	Text: "Total charged: 2,500 JPY",
}

func TestClaudeExtractorPayment(t *testing.T) {
	ts, body, hdr := fakeClaude(t,
		`{"is_payment":true,"refund":false,"amount":"2500","currency":"jpy","merchant":"Example Shop","occurred_at":"2026-10-11T15:00:00+09:00","payment_method":"Visa ending 1234"}`,
		"end_turn")
	x := NewClaudeExtractor("test-key", "claude-opus-5-5", option.WithBaseURL(ts.URL))
	r, err := x.Extract(context.Background(), receipt)
	if err != nil {
		t.Fatal(err)
	}
	want := parse.Payment{Amount: 2500, Currency: "JPY", Merchant: "Example Shop",
		OccurredAt: time.Date(2026, 10, 11, 6, 0, 0, 0, time.UTC), Source: "Visa ending 1234"}
	if r.Kind != parse.KindPayment || r.Parser != "ai" || !r.Payment.OccurredAt.Equal(want.OccurredAt) {
		t.Fatalf("%+v", r)
	}
	r.Payment.OccurredAt = want.OccurredAt
	if r.Payment != want {
		t.Errorf("got %+v\nwant %+v", r.Payment, want)
	}

	// The request: model, structured output, fallback, and the email as data.
	b := *body
	if b["model"] != "claude-opus-5-5" || b["fallbacks"] != "default" {
		t.Errorf("model %v, fallbacks %v", b["model"], b["fallbacks"])
	}
	oc, _ := b["output_config"].(map[string]any)
	format, _ := oc["format"].(map[string]any)
	if oc["effort"] != "low" || format["type"] != "json_schema" || format["schema"] == nil {
		t.Errorf("output_config %v", oc)
	}
	if !strings.Contains(hdr.Get("Anthropic-Beta"), "server-side-fallback-2026-07-01") || hdr.Get("X-Api-Key") != "test-key" {
		t.Errorf("headers %v", *hdr)
	}
	msgs, _ := b["messages"].([]any)
	if len(msgs) != 1 || !strings.Contains(mustJSON(msgs[0]), "2,500 JPY") {
		t.Errorf("messages %v", msgs)
	}
}

func TestClaudeExtractorNotPayment(t *testing.T) {
	ts, _, _ := fakeClaude(t, `{"is_payment":false,"refund":false,"amount":"","currency":"","merchant":"","occurred_at":"","payment_method":""}`, "end_turn")
	r, err := NewClaudeExtractor("k", "m", option.WithBaseURL(ts.URL)).Extract(context.Background(), receipt)
	if err != nil || r.Kind != parse.KindIgnored {
		t.Errorf("%+v %v", r, err)
	}
}

func TestClaudeExtractorFailures(t *testing.T) {
	for name, tc := range map[string]struct{ answer, stop string }{
		"refusal":    {"", "refusal"},
		"not json":   {"I think it's a payment", "end_turn"},
		"bad amount": {`{"is_payment":true,"refund":false,"amount":"lots","currency":"JPY","merchant":"x","occurred_at":"","payment_method":""}`, "end_turn"},
	} {
		ts, _, _ := fakeClaude(t, tc.answer, tc.stop)
		if _, err := NewClaudeExtractor("k", "m", option.WithBaseURL(ts.URL)).Extract(context.Background(), receipt); err == nil {
			t.Errorf("%s: no error", name)
		}
	}
}

func TestClaudeExtractorMissingDateUsesEmailDate(t *testing.T) {
	ts, _, _ := fakeClaude(t, `{"is_payment":true,"refund":true,"amount":"12.5","currency":"USD","merchant":"x","occurred_at":"","payment_method":""}`, "end_turn")
	r, err := NewClaudeExtractor("k", "m", option.WithBaseURL(ts.URL)).Extract(context.Background(), receipt)
	if err != nil || !r.Payment.OccurredAt.Equal(receipt.Date) || r.Payment.Amount != 1250 || !r.Payment.Refund {
		t.Errorf("%+v %v", r, err)
	}
}

func TestMinorUnits(t *testing.T) {
	for _, tc := range []struct {
		in, cur string
		want    int64
	}{
		{"4994", "JPY", 4994}, {"4,994", "JPY", 4994}, {"12.50", "USD", 1250}, {"12.505", "USD", 1251},
		{"0.1", "USD", 10}, {"1.2345", "KWD", 1235}, {"12.4", "JPY", 12},
	} {
		if got, err := MinorUnits(tc.in, tc.cur); err != nil || got != tc.want {
			t.Errorf("MinorUnits(%q, %s) = %d, %v; want %d", tc.in, tc.cur, got, err, tc.want)
		}
	}
	for _, bad := range []string{"", "abc", "-5"} {
		if _, err := MinorUnits(bad, "JPY"); err == nil {
			t.Errorf("MinorUnits(%q) accepted", bad)
		}
	}
}

func mustJSON(v any) string {
	b, _ := json.Marshal(v)
	return string(b)
}
