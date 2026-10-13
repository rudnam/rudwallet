package ai

import (
	"crypto/aes"
	"crypto/cipher"
	"crypto/hkdf"
	"crypto/rand"
	"crypto/sha256"
	"errors"
	"strconv"
)

// sealer encrypts users' API keys at rest with AES-256-GCM, under a key
// derived from the server's SECRETS_KEY. Each key is bound to its user, so a
// row copied to another user doesn't decrypt. A database dump or backup
// alone reveals no keys.
type sealer struct{ aead cipher.AEAD }

// sealVersion is the first byte of every sealed key, so the format (or the
// secret it was made with) can change later.
const sealVersion = 1

func newSealer(secret string) (*sealer, error) {
	key, err := hkdf.Key(sha256.New, []byte(secret), nil, "wallet-app ai_keys v1", 32)
	if err != nil {
		return nil, err
	}
	block, err := aes.NewCipher(key)
	if err != nil {
		return nil, err
	}
	aead, err := cipher.NewGCM(block)
	if err != nil {
		return nil, err
	}
	return &sealer{aead: aead}, nil
}

func additionalData(userID int64) []byte { return []byte("ai_keys:" + strconv.FormatInt(userID, 10)) }

// seal returns version, nonce and ciphertext.
func (s *sealer) seal(userID int64, plain string) []byte {
	out := make([]byte, 1+s.aead.NonceSize(), 1+s.aead.NonceSize()+len(plain)+s.aead.Overhead())
	out[0] = sealVersion
	rand.Read(out[1:])
	return s.aead.Seal(out, out[1:], []byte(plain), additionalData(userID))
}

var errUnsealable = errors.New("sealed key can't be opened")

func (s *sealer) open(userID int64, sealed []byte) (string, error) {
	n := s.aead.NonceSize()
	if len(sealed) < 1+n || sealed[0] != sealVersion {
		return "", errUnsealable
	}
	plain, err := s.aead.Open(nil, sealed[1:1+n], sealed[1+n:], additionalData(userID))
	if err != nil {
		return "", errUnsealable
	}
	return string(plain), nil
}
