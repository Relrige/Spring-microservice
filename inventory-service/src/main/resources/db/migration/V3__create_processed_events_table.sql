-- Idempotent Consumer: the IDs of events a consumer has already applied. The key is recorded in the same
-- transaction as the business change, so a redelivered event (at-least-once delivery) is recognised and skipped.
CREATE TABLE processed_events (
    event_id UUID NOT NULL,
    -- Which handler processed it: the same event may be consumed by several handlers of this service
    consumer VARCHAR(100) NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (event_id, consumer)
);

-- Retention cleanup deletes old keys
CREATE INDEX idx_processed_events_processed_at ON processed_events (processed_at);
