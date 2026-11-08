// Package db opens the PostgreSQL connection pool, applies schema migrations,
// and holds the errors every store shares.
package db

import (
	"context"
	"database/sql"
	"embed"
	"errors"
	"fmt"
	"io/fs"
	"sort"
	"strconv"
	"strings"
	"time"

	"github.com/lib/pq" // also registers the "postgres" driver
)

// ErrNotFound is returned by stores when a row does not exist (or belongs to
// someone else, which callers must not be able to tell apart).
var ErrNotFound = errors.New("not found")

// IsUniqueViolation reports whether err is a PostgreSQL unique-constraint error.
func IsUniqueViolation(err error) bool {
	var pqErr *pq.Error
	return errors.As(err, &pqErr) && pqErr.Code == "23505"
}

//go:embed migrations/*.sql
var migrationFiles embed.FS

// Open connects to PostgreSQL and waits (up to timeout) for it to accept
// connections, which matters when the database container starts alongside the app.
func Open(ctx context.Context, url string, timeout time.Duration) (*sql.DB, error) {
	db, err := sql.Open("postgres", url)
	if err != nil {
		return nil, fmt.Errorf("open database: %w", err)
	}
	// Keep (instances × max open) under the server's max_connections
	// (100 by default) when you scale out.
	db.SetMaxOpenConns(20)
	db.SetMaxIdleConns(5)
	db.SetConnMaxLifetime(30 * time.Minute)

	deadline := time.Now().Add(timeout)
	for {
		pingCtx, cancel := context.WithTimeout(ctx, 3*time.Second)
		err = db.PingContext(pingCtx)
		cancel()
		if err == nil {
			return db, nil
		}
		if time.Now().After(deadline) {
			db.Close()
			return nil, fmt.Errorf("database not reachable after %s: %w", timeout, err)
		}
		select {
		case <-ctx.Done():
			db.Close()
			return nil, ctx.Err()
		case <-time.After(time.Second):
		}
	}
}

// Migrate applies every embedded migration that has not run yet, in file-name
// order, each in its own transaction. A Postgres advisory lock keeps several
// app instances starting at once from migrating at the same time.
//
// Migrations only go forward. While old and new versions of the app run side
// by side (a rolling deploy), the old one must still work on the new schema:
// add columns and tables first, and remove them in a later release.
func Migrate(ctx context.Context, db *sql.DB) error {
	conn, err := db.Conn(ctx)
	if err != nil {
		return err
	}
	defer conn.Close()

	const lockID = 727275 // arbitrary, app-wide constant
	if _, err := conn.ExecContext(ctx, `SELECT pg_advisory_lock($1)`, lockID); err != nil {
		return fmt.Errorf("acquire migration lock: %w", err)
	}
	defer func() { _, _ = conn.ExecContext(context.Background(), `SELECT pg_advisory_unlock($1)`, lockID) }()

	if _, err := conn.ExecContext(ctx, `CREATE TABLE IF NOT EXISTS schema_migrations (
		version    INTEGER PRIMARY KEY,
		applied_at TIMESTAMPTZ NOT NULL DEFAULT now())`); err != nil {
		return fmt.Errorf("create schema_migrations: %w", err)
	}

	names, err := fs.Glob(migrationFiles, "migrations/*.sql")
	if err != nil {
		return err
	}
	migrations, err := parseMigrations(names)
	if err != nil {
		return err
	}

	for _, m := range migrations {
		name, base, version := m.path, m.base, m.version
		var applied bool
		if err := conn.QueryRowContext(ctx,
			`SELECT EXISTS (SELECT 1 FROM schema_migrations WHERE version = $1)`, version).Scan(&applied); err != nil {
			return err
		}
		if applied {
			continue
		}
		body, err := migrationFiles.ReadFile(name)
		if err != nil {
			return err
		}
		tx, err := conn.BeginTx(ctx, nil)
		if err != nil {
			return err
		}
		if _, err := tx.ExecContext(ctx, string(body)); err != nil {
			tx.Rollback()
			return fmt.Errorf("migration %s: %w", base, err)
		}
		if _, err := tx.ExecContext(ctx, `INSERT INTO schema_migrations (version) VALUES ($1)`, version); err != nil {
			tx.Rollback()
			return err
		}
		if err := tx.Commit(); err != nil {
			return err
		}
	}
	return nil
}

// retiredVersions are migration numbers that were used and then deleted. A
// database that ran one has it in schema_migrations, so a new file with that
// number would be skipped there without a word.
var retiredVersions = map[int]bool{2: true}

type migration struct {
	path    string // in migrationFiles
	base    string // file name, for messages
	version int    // the number the file name starts with
}

// parseMigrations reads the version from each migration's file name and
// returns them in version order. It refuses a name without a number, a
// number two files share and a retired number: each would leave a migration
// silently unapplied on some database.
func parseMigrations(paths []string) ([]migration, error) {
	out := make([]migration, 0, len(paths))
	seen := map[int]string{}
	for _, p := range paths {
		base := p[strings.LastIndex(p, "/")+1:]
		version, err := strconv.Atoi(strings.SplitN(base, "_", 2)[0])
		if err != nil {
			return nil, fmt.Errorf("migration %s: file name must start with a number", base)
		}
		if other, ok := seen[version]; ok {
			return nil, fmt.Errorf("migrations %s and %s share version %d; renumber the newer one", other, base, version)
		}
		if retiredVersions[version] {
			return nil, fmt.Errorf("migration %s: version %d was used before and deleted; use a new number", base, version)
		}
		seen[version] = base
		out = append(out, migration{path: p, base: base, version: version})
	}
	sort.Slice(out, func(i, j int) bool { return out[i].version < out[j].version })
	return out, nil
}
