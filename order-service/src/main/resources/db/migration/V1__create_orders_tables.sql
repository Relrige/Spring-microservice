-- One row per order. Only registered customers can order, so customer_id and customer_email are always set.
CREATE TABLE IF NOT EXISTS orders (
    id UUID PRIMARY KEY,
    customer_id UUID NOT NULL,
    -- Snapshot of the account email at placement (X-User-Email); the order events are sent to it
    customer_email VARCHAR(256) NOT NULL,
    status VARCHAR(32) NOT NULL CHECK (status IN ('PENDING_PAYMENT', 'PAID', 'PENDING_SHIPPING', 'SHIPPED',
        'DELIVERED', 'CANCELLATION_REQUESTED', 'CANCELLED', 'EXPIRED')),
    total_amount NUMERIC(12, 2) NOT NULL CHECK (total_amount >= 0),
    currency VARCHAR(3) NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    delivery_name VARCHAR(200) NOT NULL,
    delivery_phone VARCHAR(32) NOT NULL,
    delivery_address VARCHAR(500) NOT NULL,
    delivery_carrier VARCHAR(32) NOT NULL,
    reservation_expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    placed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    paid_at TIMESTAMP WITH TIME ZONE,
    -- Set when cancellation starts; decides whether a refund is owed
    status_before_cancellation VARCHAR(32) CHECK (status_before_cancellation IN
        ('PENDING_PAYMENT', 'PAID', 'PENDING_SHIPPING')),
    -- Interrupted cancellation saga (EP-ORD-JOB-02): attempts, the last failure, when it last ran, and the flag
    -- that hands the order to a person after too many failures
    cancellation_attempts INTEGER NOT NULL DEFAULT 0 CHECK (cancellation_attempts >= 0),
    cancellation_failure VARCHAR(1000),
    cancellation_last_attempt_at TIMESTAMP WITH TIME ZONE,
    needs_manual_attention BOOLEAN NOT NULL DEFAULT FALSE,
    -- Optimistic locking: every status change is a check-and-set against this version
    version BIGINT NOT NULL,
    CONSTRAINT chk_orders_paid_at_once_paid
        CHECK (status IN ('PENDING_PAYMENT', 'EXPIRED', 'CANCELLATION_REQUESTED', 'CANCELLED') OR paid_at IS NOT NULL)
);

-- Item snapshots: price and title at order time, never changed afterwards. One line per product, as Inventory's
-- reservation requires.
CREATE TABLE IF NOT EXISTS order_items (
    id UUID PRIMARY KEY,
    order_id UUID NOT NULL REFERENCES orders (id),
    product_id UUID NOT NULL,
    product_title VARCHAR(255) NOT NULL,
    unit_price_at_order NUMERIC(12, 2) NOT NULL CHECK (unit_price_at_order >= 0),
    quantity INTEGER NOT NULL CHECK (quantity >= 1),
    CONSTRAINT uq_order_items_order_product UNIQUE (order_id, product_id)
);

-- Order history (EP-ORD-04): the customer's orders, newest first
CREATE INDEX IF NOT EXISTS idx_orders_customer_placed ON orders (customer_id, placed_at DESC);

-- Payment window expiry (EP-ORD-JOB-01): PENDING_PAYMENT orders by expiry time
CREATE INDEX IF NOT EXISTS idx_orders_status_expires ON orders (status, reservation_expires_at);

-- Fulfillment queue (EP-ORD-07): PAID / PENDING_SHIPPING orders, oldest payment first
CREATE INDEX IF NOT EXISTS idx_orders_status_paid ON orders (status, paid_at);

-- Interrupted cancellations (EP-ORD-JOB-02): few rows, so a partial index keeps the lookup cheap
CREATE INDEX IF NOT EXISTS idx_orders_cancellation_requested ON orders (cancellation_last_attempt_at)
    WHERE status = 'CANCELLATION_REQUESTED';

CREATE INDEX IF NOT EXISTS idx_order_items_order ON order_items (order_id);
