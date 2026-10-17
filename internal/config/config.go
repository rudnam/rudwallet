// Package config reads the server's settings from environment variables.
// README.md documents each one for whoever runs the app.
package config

import (
	"errors"
	"fmt"
	"log/slog"
	"net/mail"
	"strconv"
	"strings"

	"github.com/rudnam/wallet-app/internal/mailer"
)

// DefaultAnthropicModel is the Claude model used unless ANTHROPIC_MODEL says otherwise.
const DefaultAnthropicModel = "claude-opus-5-5"

type Config struct {
	// Addr is the listen address.
	Addr string
	// DatabaseURL is the PostgreSQL connection URL.
	DatabaseURL string
	LogLevel    slog.Level
	// SecureCookies sets the Secure flag on the session cookie. Turn it off
	// only for local development over plain http.
	SecureCookies bool
	// TrustProxy reads the client IP from the last X-Forwarded-For entry,
	// which the reverse proxy in front adds. Enable it only when the app is
	// reachable solely through that proxy.
	TrustProxy bool
	// PublicOrigin is the site's public address, e.g. https://app.example.com.
	// Requests from it pass the CSRF origin check even if a proxy rewrites Host.
	PublicOrigin string
	// AllowSignup lets new accounts be created.
	AllowSignup bool
	// AuthRateLimit is sign-in and sign-up attempts per minute per client IP.
	AuthRateLimit int
	// AndroidPackage and AndroidCertFingerprints, when set, publish
	// /.well-known/assetlinks.json so the Android app can open the site
	// full screen. See android/README.md.
	AndroidPackage          string
	AndroidCertFingerprints []string
	// AndroidAPK is the path of the Android app's APK, offered for download
	// on the introduction page while the file exists.
	AndroidAPK string
	// InboundDomain and InboundSecret turn on the email automation: each
	// user gets an address @InboundDomain, and the mail provider's hook
	// calls POST /api/inbound/email with InboundSecret. See docs/email.md.
	InboundDomain string
	InboundSecret string
	// AnthropicAPIKey, when set, lets Claude read payment emails that no
	// built-in rule knows. AnthropicModel picks the model.
	AnthropicAPIKey string
	AnthropicModel  string
	// SecretsKey encrypts secrets the app keeps for users, such as their
	// own Anthropic API keys. Without it, users can't save keys.
	SecretsKey string
	// GoogleClientID and GoogleClientSecret turn on Sign in with Google.
	// They need PublicOrigin, which gives the callback address.
	GoogleClientID     string
	GoogleClientSecret string
	// GoogleAuthURL and GoogleTokenURL are not read from the environment;
	// tests point them at a fake Google. Empty means Google's own.
	GoogleAuthURL, GoogleTokenURL string
	// SMTPHost and the rest, when set, send the app's own emails: a link
	// to confirm each new account's address. They need PublicOrigin.
	SMTPHost     string
	SMTPPort     int
	SMTPUsername string
	SMTPPassword string
	MailFrom     string
	// Mailer is not read from the environment; tests set a fake. When nil,
	// one is built from the SMTP settings.
	Mailer mailer.Sender
}

// Load reads the configuration through getenv (os.Getenv in production) and
// reports every invalid setting at once.
func Load(getenv func(string) string) (Config, error) {
	r := reader{getenv: getenv}
	c := Config{
		Addr:                    r.str("ADDR", ":8080"),
		DatabaseURL:             r.required("DATABASE_URL"),
		LogLevel:                r.level("LOG_LEVEL", slog.LevelInfo),
		SecureCookies:           r.bool("COOKIE_SECURE", true),
		TrustProxy:              r.bool("TRUST_PROXY", false),
		PublicOrigin:            strings.TrimSuffix(r.str("PUBLIC_ORIGIN", ""), "/"),
		AllowSignup:             r.bool("ALLOW_SIGNUP", true),
		AuthRateLimit:           r.positiveInt("AUTH_RATE_LIMIT", 10),
		AndroidPackage:          r.str("ANDROID_PACKAGE", ""),
		AndroidCertFingerprints: r.list("ANDROID_CERT_FINGERPRINTS"),
		AndroidAPK:              r.str("ANDROID_APK", ""),
		InboundDomain:           strings.ToLower(r.str("INBOUND_DOMAIN", "")),
		InboundSecret:           r.str("INBOUND_SECRET", ""),
		AnthropicAPIKey:         r.str("ANTHROPIC_API_KEY", ""),
		AnthropicModel:          r.str("ANTHROPIC_MODEL", DefaultAnthropicModel),
		SecretsKey:              r.str("SECRETS_KEY", ""),
		GoogleClientID:          r.str("GOOGLE_CLIENT_ID", ""),
		GoogleClientSecret:      r.str("GOOGLE_CLIENT_SECRET", ""),
		SMTPHost:                r.str("SMTP_HOST", ""),
		SMTPPort:                r.positiveInt("SMTP_PORT", 587),
		SMTPUsername:            r.str("SMTP_USERNAME", ""),
		SMTPPassword:            r.str("SMTP_PASSWORD", ""),
		MailFrom:                r.str("MAIL_FROM", ""),
	}
	if (c.AndroidPackage == "") != (len(c.AndroidCertFingerprints) == 0) {
		r.errs = append(r.errs, errors.New("set both ANDROID_PACKAGE and ANDROID_CERT_FINGERPRINTS, or neither"))
	}
	if c.InboundDomain != "" && c.InboundSecret == "" {
		r.errs = append(r.errs, errors.New("INBOUND_DOMAIN needs INBOUND_SECRET"))
	}
	if c.InboundSecret != "" && len(c.InboundSecret) < 24 {
		r.errs = append(r.errs, errors.New("INBOUND_SECRET must be at least 24 characters"))
	}
	if c.SecretsKey != "" && len(c.SecretsKey) < 32 {
		r.errs = append(r.errs, errors.New("SECRETS_KEY must be at least 32 characters, e.g. from: openssl rand -base64 32"))
	}
	if (c.GoogleClientID == "") != (c.GoogleClientSecret == "") {
		r.errs = append(r.errs, errors.New("set both GOOGLE_CLIENT_ID and GOOGLE_CLIENT_SECRET, or neither"))
	}
	if c.GoogleClientID != "" && c.PublicOrigin == "" {
		r.errs = append(r.errs, errors.New("GOOGLE_CLIENT_ID needs PUBLIC_ORIGIN, e.g. https://app.example.com"))
	}
	if c.SMTPHost != "" {
		if _, err := mail.ParseAddress(c.MailFrom); err != nil {
			r.errs = append(r.errs, errors.New("SMTP_HOST needs MAIL_FROM, e.g. RudWallet <no-reply@example.com>"))
		}
		if c.PublicOrigin == "" {
			r.errs = append(r.errs, errors.New("SMTP_HOST needs PUBLIC_ORIGIN, which the emailed links point to"))
		}
	}
	return c, errors.Join(r.errs...)
}

// reader collects parse errors so Load can report them all together.
type reader struct {
	getenv func(string) string
	errs   []error
}

func (r *reader) str(key, def string) string {
	if v := strings.TrimSpace(r.getenv(key)); v != "" {
		return v
	}
	return def
}

func (r *reader) required(key string) string {
	v := r.str(key, "")
	if v == "" {
		r.errs = append(r.errs, fmt.Errorf("%s is required", key))
	}
	return v
}

func (r *reader) bool(key string, def bool) bool {
	v := r.str(key, "")
	if v == "" {
		return def
	}
	b, err := strconv.ParseBool(v)
	if err != nil {
		r.errs = append(r.errs, fmt.Errorf("%s must be true or false, got %q", key, v))
	}
	return b
}

func (r *reader) positiveInt(key string, def int) int {
	v := r.str(key, "")
	if v == "" {
		return def
	}
	n, err := strconv.Atoi(v)
	if err != nil || n < 1 {
		r.errs = append(r.errs, fmt.Errorf("%s must be a positive whole number, got %q", key, v))
	}
	return n
}

func (r *reader) level(key string, def slog.Level) slog.Level {
	v := r.str(key, "")
	if v == "" {
		return def
	}
	var l slog.Level
	if err := l.UnmarshalText([]byte(v)); err != nil {
		r.errs = append(r.errs, fmt.Errorf("%s must be debug, info, warn or error, got %q", key, v))
	}
	return l
}

// list reads a comma-separated value, dropping empty entries.
func (r *reader) list(key string) []string {
	var out []string
	for _, part := range strings.Split(r.getenv(key), ",") {
		if part = strings.TrimSpace(part); part != "" {
			out = append(out, part)
		}
	}
	return out
}
