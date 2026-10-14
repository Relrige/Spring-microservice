CREATE TABLE IF NOT EXISTS stock_item_reservations (
    id UUID PRIMARY KEY,
    order_id UUID NOT NULL,
    product_id UUID NOT NULL REFERENCES stock_items (product_id),
    quantity INTEGER NOT NULL CHECK(quantity >= 1),
    status VARCHAR(16) NOT NULL CHECK(status IN ('ACTIVE', 'RELEASED', 'CONSUMED')),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

-- Reserve, release and ship look up an order's reservations by status (usually ACTIVE)
CREATE INDEX IF NOT EXISTS idx_stock_item_reservations_order_status
    ON stock_item_reservations (order_id, status);

-- At most one ACTIVE reservation per order and product. This is the database guard against reserving
-- the same order twice under concurrent requests; RELEASED and CONSUMED rows are history and may repeat.
CREATE UNIQUE INDEX IF NOT EXISTS uq_stock_item_reservations_active_order_product
    ON stock_item_reservations (order_id, product_id)
    WHERE status = 'ACTIVE';
