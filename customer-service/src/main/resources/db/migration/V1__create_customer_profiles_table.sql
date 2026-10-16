-- EP-CUST-01, EP-CUST-02. One row per customer, created lazily by the first PUT; customer_id is Auth's user ID
CREATE TABLE customer_profiles
(
    customer_id     UUID PRIMARY KEY,
    first_name      VARCHAR(100) NOT NULL,
    last_name       VARCHAR(100) NOT NULL,
    -- E.164, e.g. +380501234567 (at most 15 digits after the plus)
    phone           VARCHAR(16)  NOT NULL,
    default_address VARCHAR(500),
    created_at      TIMESTAMPTZ  NOT NULL,
    updated_at      TIMESTAMPTZ  NOT NULL
);
