---
status: TODO
service: delivery-service
---
# Transactional Outbox for Shipment Events

## Context
`TTNGenerated` and `OrderDelivered` must be published if and only if the matching status change was committed. A `KafkaTemplate.send()` after commit can be lost (crash, broker down), and Order would then stay in `PAID` or never reach `DELIVERED` (dual-write problem). Catalog and Inventory already solved this with a transactional outbox; Delivery reuses that solution.

Events of one shipment must keep their order (`TTNGenerated` before `OrderDelivered`), so Inventory's relay is the template (per-aggregate blocking), not Catalog's (a failing event may be overtaken).
*References:* [Delivery Spec — Shipment Events](../../design/services-specs/delivery-service-spec.md), [Inventory outbox task](../inventory-service/17-transactional-outbox.md), [Catalog outbox task](../catalog-service/19-transactional-outbox.md)

## Acceptance Criteria
- [ ] Flyway migration creating `outbox_events` with the same structure as Inventory's (event id, aggregate id = `orderId`, type, topic, key, JSON payload, sequence, attempts, next attempt, published marker, timestamps, partial indexes).
- [ ] `ShipmentEventPublisher` interface with an outbox implementation that appends with `Propagation.MANDATORY` (fails outside a transaction).
- [ ] Relay copied from Inventory: single active relay through a PostgreSQL advisory lock, sends in sequence order, waits for each acknowledgment, back-off per failed row, later events of the same `orderId` held back, other orders keep flowing. Headers `eventType` and `eventId`; payload `{ eventId, orderId, ttn, timestamp }`.
- [ ] Cleanup of published rows after a configurable retention; a gauge for the age of the oldest unpublished event.
- [ ] Tests (Testcontainers PostgreSQL + Kafka): normal flow and wire format; broker down → row stays unpublished and is published later; rollback leaves no row; a failing event of one order holds back only that order's later events.

## Technical Notes / Constraints
- One JPA transaction manager and one `DataSource`; no Kafka or XA transactions.
- Copying instead of sharing a library follows the team's per-service rule (see the Security Model). Keep the copy close to Inventory's.
- Nothing publishes events yet; the first callers are tasks 11, 14 and 15.
