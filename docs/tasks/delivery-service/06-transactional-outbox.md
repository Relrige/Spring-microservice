---
status: DONE
service: delivery-service
---
# Transactional Outbox for Shipment Events

## Context
`TTNGenerated` and `OrderDelivered` must be published if and only if the matching status change was committed. A `KafkaTemplate.send()` after commit can be lost (crash, broker down), and Order would then stay in `PAID` or never reach `DELIVERED` (dual-write problem). Catalog and Inventory already solved this with a transactional outbox; Delivery reuses that solution.

Events of one shipment must keep their order (`TTNGenerated` before `OrderDelivered`), so Inventory's relay is the template (per-aggregate blocking), not Catalog's (a failing event may be overtaken).
*References:* [Delivery Spec — Shipment Events](../../design/services-specs/delivery-service-spec.md), [Inventory outbox task](../inventory-service/17-transactional-outbox.md), [Catalog outbox task](../catalog-service/19-transactional-outbox.md)

## Acceptance Criteria
- [x] Flyway migration creating `outbox_events` with the same structure as Inventory's (event id, aggregate id = `orderId`, type, topic, key, JSON payload, sequence, attempts, next attempt, published marker, timestamps, partial indexes).
- [x] `ShipmentEventPublisher` interface with an outbox implementation that appends with `Propagation.MANDATORY` (fails outside a transaction).
- [x] Relay copied from Inventory: single active relay through a PostgreSQL advisory lock, sends in sequence order, waits for each acknowledgment, back-off per failed row, later events of the same `orderId` held back, other orders keep flowing. Headers `eventType` and `eventId`; payload `{ eventId, orderId, ttn, timestamp }`.
- [x] Cleanup of published rows after a configurable retention; a gauge for the age of the oldest unpublished event.
- [x] Tests (Testcontainers PostgreSQL + Kafka): normal flow and wire format; broker down → row stays unpublished and is published later; rollback leaves no row; a failing event of one order holds back only that order's later events.

## Technical Notes / Constraints
- One JPA transaction manager and one `DataSource`; no Kafka or XA transactions.
- Copying instead of sharing a library follows the team's per-service rule (see the Security Model). Keep the copy close to Inventory's.
- Nothing publishes events yet; the first callers are tasks 11, 14 and 15.

## Implementation Notes (2026-10-15)
- **Copied from Inventory** (package and comments adapted from "product" to "order"): `outbox/OutboxEvent`, `OutboxRepository` (advisory-lock query, `findDue` with per-aggregate blocking), `OutboxRelay` (single active relay, sequence order, waits for each acknowledgment, exponential back-off per row capped at `max-retry-delay`, cleanup, gauge `delivery.outbox.oldest.unpublished.age`), `OutboxProperties` (prefix `delivery.outbox`), `config/SchedulingConfig`. Migration `V2__create_outbox_events_table.sql` is Inventory's V4 with the same columns and partial indexes; `aggregate_id` = `orderId`.
- **Events:** `events/ShipmentEvent` (sealed) with `TtnGeneratedEvent` (`TTNGenerated`) and `OrderDeliveredEvent` (`OrderDelivered`), payload `{ eventId, orderId, ttn, timestamp }`; `eventType()` goes to the header only. `ShipmentEventPublisher` is implemented by `outbox/OutboxShipmentEventPublisher` with `Propagation.MANDATORY`.
- **`KafkaConfig`:** now declares the `@Primary KafkaTemplate<String, Object>` explicitly (as Inventory does). Spring Boot's auto-configured template is typed `KafkaTemplate<?, ?>`, which cannot be spied on by its generic type in tests, and task 10 adds a second template anyway.
- **Per-order ordering:** `TTNGenerated` is written in the transaction that leaves `TTN_*` and `OrderDelivered` only after a later `IN_TRANSIT → DELIVERED` transaction, so the sequence order per order is the commit order; the relay keeps it.
- **Verified (2026-10-15):** `OutboxIntegrationTest` (Testcontainers PostgreSQL + Kafka): wire format (key, `eventType`/`eventId` headers, exactly the four payload fields), broker down → row stays unpublished with `attempts`/`last_error` and is published after recovery, rollback leaves no row, a failing order holds back only its own later event, `MANDATORY` outside a transaction, cleanup by retention.
