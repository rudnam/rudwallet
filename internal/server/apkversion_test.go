package server

import (
	"archive/zip"
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestDownloadsChangelog(t *testing.T) {
	manifest, err := os.ReadFile("testdata/AndroidManifest.xml")
	if err != nil {
		t.Fatal(err)
	}
	file := filepath.Join(t.TempDir(), "wallet.apk")
	writeAPK := func(changelogJSON string) {
		var buf bytes.Buffer
		zw := zip.NewWriter(&buf)
		w, err := zw.Create("AndroidManifest.xml")
		if err != nil {
			t.Fatal(err)
		}
		if _, err := w.Write(manifest); err != nil {
			t.Fatal(err)
		}
		if changelogJSON != "" {
			cw, err := zw.Create("assets/changelog.json")
			if err != nil {
				t.Fatal(err)
			}
			if _, err := cw.Write([]byte(changelogJSON)); err != nil {
				t.Fatal(err)
			}
		}
		if err := zw.Close(); err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(file, buf.Bytes(), 0o644); err != nil {
			t.Fatal(err)
		}
	}

	h := downloads(file)
	call := func(target string) string {
		req := httptest.NewRequest(http.MethodGet, target, nil)
		rec := httptest.NewRecorder()
		h.ServeHTTP(rec, req)
		if rec.Code != http.StatusOK {
			t.Fatalf("%s: status %d: %s", target, rec.Code, rec.Body.String())
		}
		return rec.Body.String()
	}

	// Without assets/changelog.json, version is returned and changelog is omitted.
	writeAPK("")
	if body := call("/api/downloads"); strings.Contains(body, `"changelog"`) || !strings.Contains(body, `"version":4,`) {
		t.Fatalf("without changelog: %s", body)
	}

	// With assets/changelog.json, ?since= filters to newer versions.
	writeAPK(`[
		{"version":4,"version_name":"2.1","en":["Show what's new"],"ja":["変更内容を表示"]},
		{"version":3,"version_name":"2.0.3","en":["Faster ride alarm"]},
		{"version":2,"version_name":"2.0.2","en":["Older fix"]}
	]`)
	type resp struct {
		Android struct {
			Version   int64            `json:"version"`
			Changelog []changelogEntry `json:"changelog"`
		} `json:"android"`
	}
	var all, since2, since4 resp
	if err := json.Unmarshal([]byte(call("/api/downloads")), &all); err != nil || len(all.Android.Changelog) != 3 {
		t.Fatalf("all entries: err=%v len=%d", err, len(all.Android.Changelog))
	}
	if err := json.Unmarshal([]byte(call("/api/downloads?since=2")), &since2); err != nil ||
		len(since2.Android.Changelog) != 2 ||
		since2.Android.Changelog[0].Version != 4 ||
		since2.Android.Changelog[1].Version != 3 ||
		since2.Android.Changelog[0].Ja[0] != "変更内容を表示" {
		t.Fatalf("since=2 entries: %+v (err=%v)", since2.Android.Changelog, err)
	}
	body4 := call("/api/downloads?since=4")
	if err := json.Unmarshal([]byte(body4), &since4); err != nil || len(since4.Android.Changelog) != 0 || strings.Contains(body4, `"changelog"`) {
		t.Fatalf("since=4 should omit changelog: %s", body4)
	}

	// Malformed changelog.json does not break version detection.
	writeAPK(`{not json`)
	var broken resp
	if err := json.Unmarshal([]byte(call("/api/downloads")), &broken); err != nil ||
		broken.Android.Version != 4 || len(broken.Android.Changelog) != 0 {
		t.Fatalf("malformed changelog: %+v (err=%v)", broken, err)
	}
}
