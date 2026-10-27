-- W3C traceparent of the request that wrote the event (observability task 03): the relay sends it on, so the
-- consumers continue that trace. 55 characters: 00-<32 hex trace id>-<16 hex span id>-<2 hex flags>. NULL for
-- events written outside a trace (scheduled jobs) and for events written before this migration
ALTER TABLE outbox_events ADD COLUMN traceparent VARCHAR(55);
