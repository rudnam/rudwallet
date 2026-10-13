package ai

import (
	"bytes"
	"testing"
)

func TestSeal(t *testing.T) {
	s, err := newSealer("a test secret that is long enough to use")
	if err != nil {
		t.Fatal(err)
	}
	const key = "sk-ant-api03-secret-value"
	sealed := s.seal(7, key)
	if bytes.Contains(sealed, []byte("secret-value")) {
		t.Fatal("plain text in the sealed key")
	}
	if got, err := s.open(7, sealed); err != nil || got != key {
		t.Fatalf("open: %q %v", got, err)
	}
	if bytes.Equal(sealed, s.seal(7, key)) {
		t.Error("sealing twice gave the same bytes: the nonce isn't random")
	}

	// Another user's row, another secret, or tampering: nothing opens.
	if _, err := s.open(8, sealed); err == nil {
		t.Error("opened for another user")
	}
	other, _ := newSealer("a different secret, also long enough to use")
	if _, err := other.open(7, sealed); err == nil {
		t.Error("opened with another secret")
	}
	bad := bytes.Clone(sealed)
	bad[len(bad)-1] ^= 1
	if _, err := s.open(7, bad); err == nil {
		t.Error("opened a tampered key")
	}
	if _, err := s.open(7, []byte{1, 2}); err == nil {
		t.Error("opened a short value")
	}
}
