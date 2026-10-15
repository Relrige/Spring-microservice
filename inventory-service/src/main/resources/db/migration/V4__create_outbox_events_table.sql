-- Transactional outbox: stock events are written here in the same transaction as the stock change that produced
-- them, and a relay publishes them to Kafka afterwards (at-least-once delivery)
CREATE TABLE outbox_events (
    -- The event's eventId, so consumers can deduplicate redelivered messages
    id UUID PRIMARY KEY,
    -- Publishing order. Events of one product are written while its stock row is locked, so for one product
    -- the sequence order is also the commit order; the relay publishes each product's events in this order
    sequence_number BIGINT GENERATED ALWAYS AS IDENTITY UNIQUE,
    -- The productId; also the Kafka message key
    aggregate_id UUID NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    topic VARCHAR(255) NOT NULL,
    message_key VARCHAR(255) NOT NULL,
    payload JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    -- NULL until the broker acknowledged the event
    published_at TIMESTAMPTZ,
    -- Failed publish attempts; a failing event and the later events of the same product wait until next_attempt_at
    attempts INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL,
    last_error VARCHAR(1000)
);

-- The relay's query: unpublished events, oldest first. Partial index: published rows (the vast majority until
-- cleanup) are not indexed at all.
CREATE INDEX idx_outbox_events_unpublished ON outbox_events (sequence_number) WHERE published_at IS NULL;

-- "Does this product have an earlier unpublished event that is waiting?" (per-product blocking)
CREATE INDEX idx_outbox_events_unpublished_aggregate ON outbox_events (aggregate_id, sequence_number)
    WHERE published_at IS NULL;

-- The cleanup job deletes published events older than the retention period
CREATE INDEX idx_outbox_events_published_at ON outbox_events (published_at) WHERE published_at IS NOT NULL;
