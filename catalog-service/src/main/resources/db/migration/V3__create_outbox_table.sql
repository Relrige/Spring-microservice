-- Transactional outbox: events are written here in the same transaction as the change that produced them,
-- and a relay publishes them to Kafka afterwards (at-least-once delivery)
CREATE TABLE outbox_events (
    -- The event's eventId, so consumers can deduplicate redelivered messages
    id UUID PRIMARY KEY,
    aggregate_type VARCHAR(50) NOT NULL,
    aggregate_id UUID NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    topic VARCHAR(255) NOT NULL,
    message_key VARCHAR(255) NOT NULL,
    payload JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    -- NULL until the broker acknowledged the event
    published_at TIMESTAMPTZ,
    -- Failed publish attempts; a failing event waits until next_attempt_at, so it never blocks newer events
    attempts INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL,
    last_error VARCHAR(1000)
);

-- The relay's query: unpublished events that are due, oldest first. Partial index: published rows
-- (the vast majority until cleanup) are not indexed at all.
CREATE INDEX idx_outbox_events_unpublished ON outbox_events (created_at) WHERE published_at IS NULL;

-- The cleanup job deletes published events older than the retention period
CREATE INDEX idx_outbox_events_published_at ON outbox_events (published_at) WHERE published_at IS NOT NULL;
