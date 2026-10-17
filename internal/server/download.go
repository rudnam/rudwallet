package server

import (
	"errors"
	"io/fs"
	"log/slog"
	"net/http"
	"os"
	"sync"
	"time"

	"github.com/rudnam/wallet-app/internal/httpx"
)

// apkPath is where the Android app's APK is downloaded from.
const apkPath = "/download/wallet.apk"

// downloads tells the introduction page what it can offer: the APK, while
// the file at file exists, so a new build can be dropped in without a
// restart. Otherwise android is null. The Android app also reads it to
// learn that a newer version is out: version is the APK's versionCode,
// left out if it can't be read.
func downloads(file string) http.Handler {
	type download struct {
		URL         string    `json:"url"`
		Size        int64     `json:"size"`
		Updated     time.Time `json:"updated"`
		Version     int64     `json:"version,omitempty"`
		VersionName string    `json:"version_name,omitempty"`
	}
	var versions apkVersions
	return httpx.HandlerFunc(func(w http.ResponseWriter, r *http.Request) error {
		var android *download
		if info, err := statAPK(file); err != nil {
			return err
		} else if info != nil {
			v := versions.get(file, info)
			android = &download{URL: apkPath, Size: info.Size(), Updated: info.ModTime().UTC(), Version: v.Code, VersionName: v.Name}
		}
		httpx.WriteJSON(w, http.StatusOK, map[string]any{"android": android})
		return nil
	})
}

// apk serves the Android app's APK, or 404 if there is none.
func apk(file string) http.Handler {
	return httpx.HandlerFunc(func(w http.ResponseWriter, r *http.Request) error {
		info, err := statAPK(file)
		if err != nil {
			return err
		}
		if info == nil {
			http.NotFound(w, r)
			return nil
		}
		f, err := os.Open(file)
		if err != nil {
			return err
		}
		defer f.Close()
		w.Header().Set("Content-Type", "application/vnd.android.package-archive")
		w.Header().Set("Content-Disposition", `attachment; filename="wallet.apk"`)
		w.Header().Set("Cache-Control", "no-cache")
		http.ServeContent(w, r, "", info.ModTime(), f)
		return nil
	})
}

// statAPK describes the APK, or returns nil if none is configured or the
// file isn't there.
func statAPK(file string) (fs.FileInfo, error) {
	if file == "" {
		return nil, nil
	}
	info, err := os.Stat(file)
	if errors.Is(err, fs.ErrNotExist) || (err == nil && !info.Mode().IsRegular()) {
		return nil, nil
	}
	return info, err
}

// apkVersions remembers the version read from the APK until the file changes.
type apkVersions struct {
	mu      sync.Mutex
	size    int64
	modTime time.Time
	v       apkVersion
}

func (c *apkVersions) get(file string, info fs.FileInfo) apkVersion {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.size == info.Size() && c.modTime.Equal(info.ModTime()) {
		return c.v
	}
	v, err := readAPKVersion(file)
	if err != nil {
		slog.Warn("reading the APK's version", "file", file, "err", err)
	}
	c.size, c.modTime, c.v = info.Size(), info.ModTime(), v
	return v
}
