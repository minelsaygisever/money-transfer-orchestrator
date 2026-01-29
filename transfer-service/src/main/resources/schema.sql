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