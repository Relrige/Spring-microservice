---
status: TODO
service: inventory-service
---
# EP-INV-EVT-01: Consume `ProductCreated`

## Context
When Catalog creates a product it emits `ProductCreated`; Inventory reacts by creating an empty `StockItem` so workers can immediately set packaging and quantities. Delivery is at-least-once, and a `StockItem` may already exist (created on demand by adjust/packaging), so the handler must be idempotent.

Catalog may not publish yet in a given environment; tests publish the event themselves using the payload from the catalogue.
*References:* [Inventory Spec — EP-INV-EVT-01](../../design/services-specs/inventory-service-spec.md), [Event Catalogue — `ProductCreated`](../../design/services-requirements/events-catalogue.md), [Catalog `ProductCreated` producer](../catalog-service/11-product-created-publish-and-listen.md), [StockItem persistence](02-stock-item-entity-and-persistence.md)

## Acceptance Criteria
- [ ] `@KafkaListener` on the topic that Catalog owns for `ProductCreated` (name from configuration, matching the Catalog task 10 decision), in a dedicated consumer group (`inventory-service`).
- [ ] The payload is read into Inventory's **own** record (`productId`, `timestamp`, optional `eventId`); unknown properties are ignored (tolerant reader), no shared event library.
- [ ] If no `StockItem` exists for `productId` → create one with zero quantities and no packaging. If it exists → no action, acknowledge.
- [ ] Duplicate delivery and a race with on-demand creation (adjust/packaging running at the same time) never fail the consumer and never create a second row: creation is race-safe at the database level (`INSERT … ON CONFLICT DO NOTHING` or catching the duplicate-key error and treating it as "already exists").
- [ ] Creating a `StockItem` emits no event.
- [ ] Malformed or undeserializable payloads do not block the partition: after a bounded number of attempts they go to a dead-letter topic (or are skipped and logged; document the choice). Transient database failures are retried with bounded back-off.
- [ ] The consumer starts and the application stays healthy when the Catalog topic does not exist yet (missing topics are not fatal for the listener container).
- [ ] The temporary demonstration listener from [task 06](06-stock-events-publish-and-listen.md) is removed.
- [ ] Tests (Testcontainers Kafka + PostgreSQL): a new product creates a `StockItem`; the same event twice creates one; an event for an existing `StockItem` (with quantities) changes nothing; a poison message does not stop later messages.

## Technical Notes / Constraints
- Offsets are committed only after the database transaction succeeded; state the acknowledgment mode used and why.
- Remember: a plain `save()` on an entity with an assigned ID does a select-then-insert, which is racy. See the persistence note in [task 02](02-stock-item-entity-and-persistence.md).
- The event may arrive before or after a worker's first action on the product; both orders must end in the same state.
