// Package parse turns a raw email into plain text and, for senders it knows,
// into a payment. It has no I/O, so every format is covered by unit tests
// with fixtures in testdata/.
package parse

import (
	"bytes"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"errors"
	"fmt"
	"html"
	"io"
	"mime"
	"mime/multipart"
	"mime/quotedprintable"
	"net/mail"
	"regexp"
	"strings"
	"time"
	"unicode/utf8"
)

// Message is the part of an email the parsers need.
type Message struct {
	// MessageID identifies the email so a retried delivery is not counted
	// twice. Emails without one get a hash of their content.
	MessageID string
	// From is the sender's address, lower-cased, without the display name.
	From    string
	Subject string
	Date    time.Time
	// Text is the body as plain text (from text/plain, else from text/html).
	Text string
}

// ErrNotEmail means the input could not be read as an email at all.
var ErrNotEmail = errors.New("not an email message")

const maxDepth = 10

// Read parses a raw RFC 5322 message.
func Read(raw []byte) (Message, error) {
	m, err := mail.ReadMessage(bytes.NewReader(raw))
	if err != nil {
		return Message{}, ErrNotEmail
	}
	msg := Message{
		MessageID: strings.Trim(strings.TrimSpace(m.Header.Get("Message-ID")), "<>"),
		From:      address(m.Header.Get("From")),
		Subject:   decodeHeader(m.Header.Get("Subject")),
	}
	if msg.MessageID == "" {
		sum := sha256.Sum256(raw)
		msg.MessageID = "sha256:" + hex.EncodeToString(sum[:])
	}
	if d, err := m.Header.Date(); err == nil {
		msg.Date = d
	}
	plain, htmlText := "", ""
	walk(textproto(m.Header), m.Body, 0, &plain, &htmlText)
	if strings.TrimSpace(plain) != "" {
		msg.Text = normalize(plain)
	} else {
		msg.Text = normalize(HTMLToText(htmlText))
	}
	unwrapForward(&msg)
	return msg, nil
}

// header is the subset of a MIME header that walk needs.
type header interface{ Get(string) string }

func textproto(h mail.Header) header { return h }

// walk collects the first text/plain and text/html parts, descending into
// multiparts.
func walk(h header, body io.Reader, depth int, plain, htmlText *string) {
	if depth > maxDepth {
		return
	}
	ct := h.Get("Content-Type")
	if ct == "" {
		ct = "text/plain; charset=us-ascii"
	}
	mt, params, err := mime.ParseMediaType(ct)
	if err != nil {
		return
	}
	if strings.HasPrefix(mt, "multipart/") {
		mr := multipart.NewReader(body, params["boundary"])
		for {
			p, err := mr.NextRawPart()
			if err != nil {
				return
			}
			walk(p.Header, p, depth+1, plain, htmlText)
		}
	}
	if mt != "text/plain" && mt != "text/html" {
		return
	}
	if strings.EqualFold(h.Get("Content-Disposition"), "attachment") ||
		strings.HasPrefix(strings.ToLower(h.Get("Content-Disposition")), "attachment;") {
		return
	}
	data, err := io.ReadAll(decodeTransfer(h.Get("Content-Transfer-Encoding"), body))
	if err != nil {
		return
	}
	text, ok := toUTF8(data, params["charset"])
	if !ok {
		return
	}
	if mt == "text/plain" && *plain == "" {
		*plain = text
	} else if mt == "text/html" && *htmlText == "" {
		*htmlText = text
	}
}

func decodeTransfer(enc string, r io.Reader) io.Reader {
	switch strings.ToLower(strings.TrimSpace(enc)) {
	case "base64":
		// The decoder skips the line breaks base64 bodies are wrapped with.
		return base64.NewDecoder(base64.StdEncoding, r)
	case "quoted-printable":
		return quotedprintable.NewReader(r)
	default:
		return r
	}
}

// toUTF8 converts a part's bytes to a string. Only UTF-8, ASCII and Latin-1
// are understood (the standard library has no Japanese legacy charsets);
// anything else is accepted only if it happens to be valid UTF-8.
func toUTF8(data []byte, charset string) (string, bool) {
	switch strings.ToLower(strings.TrimSpace(charset)) {
	case "", "utf-8", "utf8", "us-ascii", "ascii":
		return strings.ToValidUTF8(string(data), "�"), true
	case "iso-8859-1", "latin1", "windows-1252":
		runes := make([]rune, len(data))
		for i, b := range data {
			runes[i] = rune(b)
		}
		return string(runes), true
	default:
		if utf8.Valid(data) {
			return string(data), true
		}
		return "", false
	}
}

var wordDecoder = &mime.WordDecoder{
	CharsetReader: func(charset string, input io.Reader) (io.Reader, error) {
		return nil, fmt.Errorf("unsupported charset %q", charset)
	},
}

// decodeHeader decodes RFC 2047 encoded words; with an unknown charset it
// returns the header as it is.
func decodeHeader(s string) string {
	if d, err := wordDecoder.DecodeHeader(s); err == nil {
		return strings.TrimSpace(d)
	}
	return strings.TrimSpace(s)
}

var angleAddr = regexp.MustCompile(`<([^<>\s]+@[^<>\s]+)>`)
var bareAddr = regexp.MustCompile(`[^\s<>"]+@[^\s<>"]+`)

// address returns just the email address. The display name is often in a
// charset Go can't decode (PayPay uses ISO-2022-JP), so it falls back to
// finding the address by pattern.
func address(s string) string {
	p := mail.AddressParser{WordDecoder: wordDecoder}
	if a, err := p.Parse(s); err == nil {
		return strings.ToLower(a.Address)
	}
	if m := angleAddr.FindStringSubmatch(s); m != nil {
		return strings.ToLower(m[1])
	}
	return strings.ToLower(bareAddr.FindString(s))
}

var (
	dropBlocks = regexp.MustCompile(`(?is)<(script|style|head)\b.*?</(script|style|head)\s*>`)
	breaks     = regexp.MustCompile(`(?i)<br\s*/?>|</(p|div|tr|li|h[1-6]|table|td|th)\s*>`)
	tags       = regexp.MustCompile(`(?s)<[^>]*>`)
)

// HTMLToText reduces an HTML body to its text, one block per line.
func HTMLToText(s string) string {
	s = dropBlocks.ReplaceAllString(s, "")
	s = breaks.ReplaceAllString(s, "\n")
	s = tags.ReplaceAllString(s, "")
	return html.UnescapeString(s)
}

var spaces = regexp.MustCompile(`[ \t\x{00A0}\x{3000}]+`)

// normalize trims every line, collapses runs of spaces, and drops blank lines.
func normalize(s string) string {
	s = strings.ReplaceAll(s, "\r\n", "\n")
	var out []string
	for _, line := range strings.Split(s, "\n") {
		if line = strings.TrimSpace(spaces.ReplaceAllString(line, " ")); line != "" {
			out = append(out, line)
		}
	}
	return strings.Join(out, "\n")
}

var forwardMarker = regexp.MustCompile(`(?m)^-{3,} ?(Forwarded message|転送メッセージ|Original Message) ?-{3,}$`)

// unwrapForward handles an email the user forwarded by hand: the payment's
// real sender and subject are in the quoted header block, not the envelope.
func unwrapForward(msg *Message) {
	loc := forwardMarker.FindStringIndex(msg.Text)
	if loc == nil {
		return
	}
	rest := strings.Split(msg.Text[loc[1]:], "\n")
	var from, subject string
	i := 0
	for ; i < len(rest) && i < 8; i++ {
		line := strings.TrimSpace(rest[i])
		if k, v, ok := strings.Cut(line, ":"); ok {
			switch strings.ToLower(strings.TrimSpace(k)) {
			case "from", "差出人", "送信者":
				from = address(v)
				continue
			case "subject", "件名":
				subject = strings.TrimSpace(v)
				continue
			case "date", "日付", "to", "宛先", "cc":
				continue
			}
		}
		if i > 0 {
			break
		}
	}
	if from == "" {
		return
	}
	msg.From = from
	if subject != "" {
		msg.Subject = subject
	}
	msg.Text = strings.Join(rest[i:], "\n")
}
