-- One row per order: the registration of the order's parcel with the carrier. Rows are never deleted, so a
-- terminal row also guards against a duplicate or late OrderPaid
CREATE TABLE shipments (
    id UUID PRIMARY KEY,
    order_id UUID NOT NULL UNIQUE,
    status VARCHAR(20) NOT NULL
        CHECK (status IN ('TTN_PENDING', 'TTN_FAILED', 'TTN_GENERATED', 'IN_TRANSIT', 'DELIVERED', 'CANCELLED')),
    -- The carrier's waybill number; several NULLs are allowed by a UNIQUE constraint
    ttn VARCHAR(50) UNIQUE,
    failure_reason VARCHAR(1000),
    -- Snapshot from OrderPaid; NULL only on a CANCELLED tombstone (cancelled before OrderPaid was consumed)
    recipient_name VARCHAR(255),
    recipient_phone VARCHAR(50),
    recipient_address VARCHAR(500),
    carrier VARCHAR(100),
    declared_value NUMERIC(12, 2) CHECK (declared_value >= 0),
    currency VARCHAR(3),
    -- The computed parcel; NULL until a TTN was generated
    total_weight_grams INTEGER CHECK (total_weight_grams > 0),
    length_cm INTEGER CHECK (length_cm > 0),
    width_cm INTEGER CHECK (width_cm > 0),
    height_cm INTEGER CHECK (height_cm > 0),
    handed_over_at TIMESTAMPTZ,
    -- Technical columns of the TTN generation job (failed attempts, when the next attempt is due)
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    next_attempt_at TIMESTAMPTZ,
    -- Optimistic lock (JPA @Version)
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT shipments_snapshot_required CHECK (
        status = 'CANCELLED'
        OR (recipient_name IS NOT NULL AND recipient_phone IS NOT NULL AND recipient_address IS NOT NULL
            AND carrier IS NOT NULL AND declared_value IS NOT NULL AND currency IS NOT NULL))
);

-- The TTN generation job's claim: due TTN_PENDING rows, oldest first. Partial: other statuses are never scanned
CREATE INDEX idx_shipments_ttn_pending_due ON shipments (next_attempt_at) WHERE status = 'TTN_PENDING';

-- Order lines of a shipment (JPA element collection); packaging is fetched from Inventory, never stored here
CREATE TABLE shipment_items (
    shipment_id UUID NOT NULL REFERENCES shipments (id),
    product_id UUID NOT NULL,
    quantity INTEGER NOT NULL CHECK (quantity >= 1),
    PRIMARY KEY (shipment_id, product_id)
);
