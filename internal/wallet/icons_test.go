package wallet

import (
	"os"
	"regexp"
	"testing"
)

// Every default's icon is a glyph both apps can draw: the web's glyphs
// (web/src/components/Icon.tsx) and Android's (WalletIcons.glyphs). An icon
// either one lacks shows as the category's initial.
func TestDefaultIconsAreGlyphs(t *testing.T) {
	sources := map[string]*regexp.Regexp{
		"../../web/src/components/Icon.tsx": regexp.MustCompile(`(?m)^  '?(\w+)'?: \{\s*label:`),
		"../../android/core/designsystem/src/main/kotlin/com/rudnam/wallet/core/designsystem/icon/WalletIcons.kt": regexp.MustCompile(`(?m)^        "(\w+)" to `),
	}
	for path, re := range sources {
		src, err := os.ReadFile(path)
		if err != nil {
			t.Fatal(err)
		}
		have := map[string]bool{}
		for _, m := range re.FindAllStringSubmatch(string(src), -1) {
			have[m[1]] = true
		}
		for _, c := range defaultCategories {
			if !have[c.Icon] {
				t.Errorf("%s: no glyph %q for %q", path, c.Icon, c.Name)
			}
		}
	}
}
