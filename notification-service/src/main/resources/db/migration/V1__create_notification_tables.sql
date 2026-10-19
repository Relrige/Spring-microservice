-- One row per email to send (Notification log, spec D2). event_id is the consumed event's ID: its UNIQUE constraint
-- is the Idempotent Consumer's dedup key, so a redelivered event inserts nothing and never sends a second email
CREATE TABLE notifications (
    id UUID PRIMARY KEY,
    event_id UUID NOT NULL,
    order_id UUID NOT NULL,
    type VARCHAR(32) NOT NULL
        CHECK (type IN ('ORDER_PLACED', 'PAYMENT_RECEIVED', 'ORDER_SHIPPED', 'ORDER_DELIVERED', 'ORDER_CANCELLED',
                        'ORDER_EXPIRED')),
    -- NULL until resolved from order_recipients (ORDER_DELIVERED rows; spec D1)
    recipient VARCHAR(256),
    -- Rendered when the event is consumed, so the sender job is generic and the row shows what was sent
    subject TEXT NOT NULL,
    body TEXT NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('PENDING', 'SENT', 'FAILED')),
    -- Technical columns of the sender job: failed attempts so far, and when the next one is due (NULL once the row
    -- left PENDING)
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    next_attempt_at TIMESTAMPTZ,
    failure_reason TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    sent_at TIMESTAMPTZ,
    CONSTRAINT uq_notifications_event_id UNIQUE (event_id),
    CONSTRAINT notifications_sent_has_recipient
        CHECK (status <> 'SENT' OR (recipient IS NOT NULL AND sent_at IS NOT NULL))
);

-- The sender job's claim: due PENDING rows, oldest first
CREATE INDEX idx_notifications_status_next_attempt_at ON notifications (status, next_attempt_at);

-- Read model orderId -> customerEmail, upserted by every order event (spec D1): Delivery's OrderDelivered has no
-- email address. No foreign key to notifications: order_id is a plain cross-service reference
CREATE TABLE order_recipients (
    order_id UUID PRIMARY KEY,
    customer_email VARCHAR(256) NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);
