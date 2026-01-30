CREATE TABLE IF NOT EXISTS accounts (
    id SERIAL PRIMARY KEY,
    customer_id VARCHAR(255) NOT NULL,
    balance DECIMAL(19, 2) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    status VARCHAR(20) NOT NULL,
    daily_limit DECIMAL(19, 2),
    version BIGINT,
    created_at TIMESTAMP,
    updated_at TIMESTAMP
);

CREATE TABLE IF NOT EXISTS outbox (
    id SERIAL PRIMARY KEY,
    aggregate_type VARCHAR(255) NOT NULL,
    aggregate_id VARCHAR(255) NOT NULL,
    type VARCHAR(255) NOT NULL,
    payload VARCHAR(5000) NOT NULL,
    status VARCHAR(50) NOT NULL,
    retry_count INT DEFAULT 0,
    created_at TIMESTAMP,
    next_attempt_time TIMESTAMP
);