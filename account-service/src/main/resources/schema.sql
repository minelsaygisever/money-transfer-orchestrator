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