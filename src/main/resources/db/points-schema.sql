-- PostgreSQL-compatible points schema. The JDBC adapter also runs this script.
-- Balance may be negative: refund clawback is posted in full after points were spent.

CREATE TABLE IF NOT EXISTS points_account (
    account_id VARCHAR(64) PRIMARY KEY,
    balance BIGINT NOT NULL,
    version BIGINT NOT NULL
);

CREATE TABLE IF NOT EXISTS points_payment_event (
    payment_event_id VARCHAR(64) PRIMARY KEY,
    order_id VARCHAR(64) NOT NULL UNIQUE,
    account_id VARCHAR(64) NOT NULL,
    fingerprint VARCHAR(64) NOT NULL,
    points_granted BIGINT NOT NULL,
    created_at_ms BIGINT NOT NULL
);

CREATE TABLE IF NOT EXISTS points_issuance (
    issuance_id VARCHAR(64) PRIMARY KEY,
    payment_event_id VARCHAR(64) NOT NULL,
    order_id VARCHAR(64) NOT NULL,
    line_id VARCHAR(64) NOT NULL,
    account_id VARCHAR(64) NOT NULL,
    sku VARCHAR(128) NOT NULL,
    quantity INTEGER NOT NULL,
    pay_amount_in_cents BIGINT NOT NULL,
    points_granted BIGINT NOT NULL,
    rule_id VARCHAR(64) NOT NULL,
    rule_version VARCHAR(32) NOT NULL,
    rule_snapshot VARCHAR(512) NOT NULL,
    issued_at_ms BIGINT NOT NULL,
    CONSTRAINT uq_points_issuance_line UNIQUE (order_id, line_id),
    CONSTRAINT ck_points_issuance_nonnegative CHECK (
        quantity > 0 AND pay_amount_in_cents >= 0 AND points_granted >= 0
    )
);

CREATE TABLE IF NOT EXISTS points_refund_event (
    refund_id VARCHAR(64) PRIMARY KEY,
    order_id VARCHAR(64) NOT NULL,
    account_id VARCHAR(64) NOT NULL,
    fingerprint VARCHAR(64) NOT NULL,
    points_reversed BIGINT NOT NULL,
    created_at_ms BIGINT NOT NULL,
    CONSTRAINT ck_points_refund_nonnegative CHECK (points_reversed >= 0)
);

CREATE TABLE IF NOT EXISTS points_redeem_event (
    redeem_id VARCHAR(64) PRIMARY KEY,
    account_id VARCHAR(64) NOT NULL,
    fingerprint VARCHAR(64) NOT NULL,
    points_spent BIGINT NOT NULL,
    created_at_ms BIGINT NOT NULL,
    CONSTRAINT ck_points_redeem_positive CHECK (points_spent > 0)
);

CREATE TABLE IF NOT EXISTS points_ledger (
    entry_id VARCHAR(64) PRIMARY KEY,
    account_id VARCHAR(64) NOT NULL,
    order_id VARCHAR(64),
    line_id VARCHAR(64),
    entry_type VARCHAR(16) NOT NULL,
    points BIGINT NOT NULL,
    quantity INTEGER NOT NULL,
    correlation_id VARCHAR(64) NOT NULL,
    idempotency_key VARCHAR(160) NOT NULL UNIQUE,
    created_at_ms BIGINT NOT NULL,
    CONSTRAINT ck_points_ledger_sign CHECK (
        (entry_type = 'GRANT' AND points >= 0 AND quantity > 0)
        OR (entry_type = 'REVERSAL' AND points <= 0 AND quantity > 0)
        OR (entry_type = 'REDEEM' AND points < 0 AND quantity = 0)
    )
);

CREATE INDEX IF NOT EXISTS idx_points_ledger_account ON points_ledger (account_id);
CREATE INDEX IF NOT EXISTS idx_points_ledger_reversal ON points_ledger (account_id, order_id, line_id, entry_type);
CREATE INDEX IF NOT EXISTS idx_points_ledger_correlation ON points_ledger (correlation_id, entry_type);
CREATE INDEX IF NOT EXISTS idx_points_issuance_order ON points_issuance (order_id);
