-- Submit-order requests by Idempotency-Key (EP-ORD-02, decisions D4-D10 of 2026-10-22).

-- One row per (customer, key). Inserted as IN_PROGRESS before Catalog is called, so a concurrent repeat finds it and
-- never reaches Inventory; set to COMPLETED with the order in the transaction that stores it; deleted when the request
-- fails, so only success is remembered. request_hash tells a repeat of the same request (replayed) from another
-- request under the same key (refused). EP-ORD-JOB-05 deletes stale IN_PROGRESS rows and COMPLETED rows past their TTL.
CREATE TABLE IF NOT EXISTS idempotency_keys (
    customer_id UUID NOT NULL,
    idempotency_key UUID NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('IN_PROGRESS', 'COMPLETED')),
    order_id UUID,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (customer_id, idempotency_key),
    CONSTRAINT chk_idempotency_keys_order CHECK ((status = 'COMPLETED') = (order_id IS NOT NULL))
);

-- Both purges of EP-ORD-JOB-05 select by status and age
CREATE INDEX IF NOT EXISTS idx_idempotency_keys_status_created ON idempotency_keys (status, created_at);
