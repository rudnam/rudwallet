-- Demo accounts: "Try the demo" makes each visitor a throwaway account full
-- of sample data, with no password. It ends at demo_expires_at, after which
-- it is deleted with everything in it. NULL for everyone else.
ALTER TABLE users ADD COLUMN demo_expires_at TIMESTAMPTZ;
CREATE INDEX users_demo_expires_at_idx ON users (demo_expires_at) WHERE demo_expires_at IS NOT NULL;
