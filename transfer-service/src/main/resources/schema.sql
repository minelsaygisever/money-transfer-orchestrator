CREATE TABLE IF NOT EXISTS transfers (
    id SERIAL PRIMARY KEY,
    idempotency_key VARCHAR(255) NOT NULL UNIQUE,
    transaction_id VARCHAR(255) NOT NULL UNIQUE,
    sender_account_id VARCHAR(255) NOT NULL,
    receiver_account_id VARCHAR(255) NOT NULL,
    amount DECIMAL(19, 2) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    state VARCHAR(20) NOT NULL,
    failure_reason VARCHAR(255),
    version BIGINT,
    created_at TIMESTAMP,
    updated_at TIMESTAMP
);

CREATE TABLE IF NOT EXISTS outbox (
    id SERIAL PRIMARY KEY,
    aggregate_type VARCHAR(255) NOT NULL, -- Ex: "TRANSFER"
    aggregate_id VARCHAR(255) NOT NULL,   -- Ex: Transfer Transaction ID
    type VARCHAR(255) NOT NULL,           -- Ex: "TRANSFER_INITIATED"
    payload VARCHAR(5000) NOT NULL,       -- JSON version of the event
    status VARCHAR(50) NOT NULL,          -- PENDING, COMPLETED, FAILED
    retry_count INT DEFAULT 0,
    created_at TIMESTAMP,
    next_attempt_time TIMESTAMP
);