-- Ways of paying in an import the user chose not to record (e.g. PayPay
-- points), keyed like source_rules (paypay-csv:<取引方法>), so the choice
-- is remembered for the next import. A source is in one table or the other.
CREATE TABLE import_skips (
    user_id BIGINT NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    source  TEXT NOT NULL,
    PRIMARY KEY (user_id, source)
);
