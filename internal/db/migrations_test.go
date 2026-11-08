package db

import (
	"io/fs"
	"strings"
	"testing"
)

// The migrations in this repository must pass the checks Migrate runs at startup.
func TestEmbeddedMigrations(t *testing.T) {
	names, err := fs.Glob(migrationFiles, "migrations/*.sql")
	if err != nil {
		t.Fatal(err)
	}
	ms, err := parseMigrations(names)
	if err != nil {
		t.Fatal(err)
	}
	if len(ms) == 0 || ms[0].version != 1 {
		t.Fatalf("want migrations starting at 001, got %v", ms)
	}
}

func TestParseMigrations(t *testing.T) {
	ms, err := parseMigrations([]string{"migrations/010_b.sql", "migrations/9_a.sql", "migrations/001_x.sql"})
	if err != nil {
		t.Fatal(err)
	}
	var got []int
	for _, m := range ms {
		got = append(got, m.version)
	}
	if len(got) != 3 || got[0] != 1 || got[1] != 9 || got[2] != 10 {
		t.Errorf("order: got %v, want [1 9 10]", got)
	}

	for _, tc := range []struct {
		names []string
		want  string
	}{
		{[]string{"migrations/019_a.sql", "migrations/019_b.sql"}, "share version 19"},
		{[]string{"migrations/002_again.sql"}, "deleted"},
		{[]string{"migrations/x_nonumber.sql"}, "must start with a number"},
	} {
		_, err := parseMigrations(tc.names)
		if err == nil || !strings.Contains(err.Error(), tc.want) {
			t.Errorf("%v: got error %v, want one containing %q", tc.names, err, tc.want)
		}
	}
}
