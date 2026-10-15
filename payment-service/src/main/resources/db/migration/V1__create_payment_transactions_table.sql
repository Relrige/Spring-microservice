-- Append-only history of what was asked of the payment gateway and what it answered. A charge and its refund are
-- two rows; rows are never updated, so there is no updated_at.
CREATE TABLE IF NOT EXISTS payment_transactions (
    id UUID PRIMARY KEY,
    order_id UUID NOT NULL,
    type VARCHAR(16) NOT NULL CHECK (type IN ('CHARGE', 'REFUND')),
    status VARCHAR(16) NOT NULL CHECK (status IN ('SUCCESS', 'FAILED')),
    amount NUMERIC(12, 2) NOT NULL CHECK (amount > 0),
    -- ISO 4217 code; VARCHAR rather than CHAR(3) so that Hibernate's schema validation matches a String field
    currency VARCHAR(3) NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    gateway_transaction_id VARCHAR(64),
    -- Definitive outcomes (the gateway answered "no") and uncertain ones (no answer: the money may have been taken)
    failure_reason VARCHAR(32) CHECK (failure_reason IN
        ('INSUFFICIENT_FUNDS', 'CARD_DECLINED', 'REFUND_REJECTED', 'GATEWAY_TIMEOUT', 'GATEWAY_UNAVAILABLE')),
    -- The key the request was sent to the gateway with; repeated requests with the same key are processed once
    idempotency_key VARCHAR(100) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    -- A success always carries the gateway's ID and no reason; a failure always carries a reason
    CONSTRAINT chk_payment_transactions_success_has_gateway_id
        CHECK (status <> 'SUCCESS' OR gateway_transaction_id IS NOT NULL),
    CONSTRAINT chk_payment_transactions_reason_only_on_failure
        CHECK ((status = 'FAILED') = (failure_reason IS NOT NULL))
);

-- Charge and refund look up an order's transactions by type (and status)
CREATE INDEX IF NOT EXISTS idx_payment_transactions_order_type
    ON payment_transactions (order_id, type, status);

-- Never charge or refund an order twice: at most one successful row of each type per order. Failed rows may repeat
-- (a customer retries with another card). Two concurrent requests with the same idempotency key both get the
-- gateway's single approval; the second insert fails here and is answered with 409.
CREATE UNIQUE INDEX IF NOT EXISTS uq_payment_transactions_successful_charge
    ON payment_transactions (order_id)
    WHERE type = 'CHARGE' AND status = 'SUCCESS';

CREATE UNIQUE INDEX IF NOT EXISTS uq_payment_transactions_successful_refund
    ON payment_transactions (order_id)
    WHERE type = 'REFUND' AND status = 'SUCCESS';
