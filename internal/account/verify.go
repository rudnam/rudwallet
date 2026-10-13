package account

// Confirming email addresses: after sign-up we email a link; opening it
// marks the address confirmed. The app keeps working meanwhile and shows a
// reminder. Signing in with Google confirms the address too.

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"net/http"
	"time"

	"github.com/rudnam/wallet-app/internal/db"
	"github.com/rudnam/wallet-app/internal/httpx"
	"github.com/rudnam/wallet-app/internal/mailer"
)

const confirmationTTL = 48 * time.Hour

func (h *Handler) sendConfirmation(ctx context.Context, userID int64, email string) error {
	token, hash, err := newSessionToken() // same shape: random token, stored hashed
	if err != nil {
		return err
	}
	if err := h.store.createEmailToken(ctx, userID, email, hash, h.now().Add(confirmationTTL)); err != nil {
		return err
	}
	link := h.cfg.PublicOrigin + "/api/verify-email?token=" + token
	return h.cfg.Mail.Send(ctx, mailer.Message{
		To:      email,
		Subject: "Confirm your email for Wallet",
		Body: fmt.Sprintf("Hello,\n\nPlease confirm that this is your email address by opening this link:\n\n%s\n\n"+
			"The link works for %d hours. If you didn't create a Wallet account, you can ignore this email.\n",
			link, int(confirmationTTL.Hours())),
	})
}

// resendConfirmation emails a fresh link to the signed-in person.
func (h *Handler) resendConfirmation(w http.ResponseWriter, r *http.Request) error {
	u := UserFrom(r)
	if h.cfg.Mail == nil {
		return httpx.Errorf(http.StatusNotFound, "this server doesn't send email")
	}
	if u.EmailVerified {
		return httpx.Errorf(http.StatusConflict, "your email is already confirmed")
	}
	if err := h.sendConfirmation(r.Context(), u.ID, u.Email); err != nil {
		slog.ErrorContext(r.Context(), "send confirmation email", "user", u.ID, "err", err)
		return httpx.Errorf(http.StatusBadGateway, "the email could not be sent; try again in a few minutes")
	}
	w.WriteHeader(http.StatusNoContent)
	return nil
}

// verifyEmail is the link in the email. It sends the browser back to the
// app with ?email=confirmed, or ?email=expired if the link no longer works.
func (h *Handler) verifyEmail(w http.ResponseWriter, r *http.Request) {
	result := "confirmed"
	err := h.store.verifyEmail(r.Context(), hashToken(r.URL.Query().Get("token")))
	if errors.Is(err, db.ErrNotFound) {
		result = "expired"
	} else if err != nil {
		slog.ErrorContext(r.Context(), "verify email", "err", err)
		result = "failed"
	}
	http.Redirect(w, r, "/app/?email="+result, http.StatusFound)
}
