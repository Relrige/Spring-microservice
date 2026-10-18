-- Work Order owes another service, written down before acting so that a crash or an outage in between can always be
-- finished later by a job (decision 2026-10-18).

-- A reservation requested from Inventory for an order that is not stored yet. Written before the call and deleted
-- in the transaction that stores the order. A row that is older than the settle time therefore belongs to an order
-- that was never stored (the save failed, the instance died, or Inventory answered too late), and the job releases
-- its stock.
CREATE TABLE IF NOT EXISTS reservation_attempts (
    order_id UUID PRIMARY KEY,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    release_attempts INTEGER NOT NULL DEFAULT 0 CHECK (release_attempts >= 0),
    last_error VARCHAR(1000)
);

CREATE INDEX IF NOT EXISTS idx_reservation_attempts_created ON reservation_attempts (created_at);

-- A charge that succeeded for an order which was cancelled or expired during the charge (EP-ORD-03 step 5). The
-- money must go back; the row lives until Payment confirms the refund.
CREATE TABLE IF NOT EXISTS pending_refunds (
    order_id UUID PRIMARY KEY,
    amount NUMERIC(12, 2) NOT NULL CHECK (amount > 0),
    currency VARCHAR(3) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    last_attempt_at TIMESTAMP WITH TIME ZONE NOT NULL,
    last_error VARCHAR(1000),
    needs_manual_attention BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE INDEX IF NOT EXISTS idx_pending_refunds_last_attempt ON pending_refunds (last_attempt_at);
