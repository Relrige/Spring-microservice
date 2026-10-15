---
status: TODO
service: delivery-service
---
# Automatic TTN Generation Job (Tier 1 and Tier 2)

## Context
This is the core of the service. A pending shipment must become `TTN_GENERATED`, or `TTN_FAILED` with a reason the worker can act on. Carrier failures are retried automatically (Tier 1: 3 retries after 5 s, 30 s, 2 min); when they run out, the shipment is flagged (Tier 2) for the worker's manual retry (Tier 3, [task 14](14-manual-ttn-retry.md)). A paid order is never cancelled because of a carrier failure.

Retries are **database-driven** (`attempts`, `next_attempt_at`) instead of happening inside the Kafka listener. Back-offs of minutes would block the partition, and in-memory retry state would be lost on a restart.
*References:* [Delivery Spec — Automatic TTN generation, Parcel calculation](../../design/services-specs/delivery-service-spec.md), [SCN-S04, SCN-S05](../../design/scenarios/system-scenarios.md)

## Acceptance Criteria
- [ ] A `TtnRegistrationService` that runs **one attempt** for a shipment: fetch packaging (task 09) → calculate the parcel (task 08) → `CarrierGateway.register` (task 07). It returns success (TTN, parcel), a retryable failure, or a non-retryable failure with a `reason`. The manual retry reuses it.
- [ ] A scheduled job (configurable interval, e.g. 1 s) claims due shipments (`TTN_PENDING`, `next_attempt_at <= now`, oldest first, batch size configurable) with `FOR UPDATE SKIP LOCKED`, moves their `next_attempt_at` forward by a lease (configurable, e.g. 2 min) and commits **before** any remote call.
- [ ] Each attempt runs outside a transaction; its result is applied in a new transaction only if the shipment is still `TTN_PENDING` (optimistic lock):
  - success → `markGenerated`, append `TTNGenerated` to the outbox (task 06), same transaction;
  - retryable → `attempts + 1`, `failureReason`, `next_attempt_at = now + backoff[attempts]`; after the last back-off → `markFailed`;
  - non-retryable → `markFailed(reason)` immediately.
- [ ] If the shipment was cancelled meanwhile, the result is discarded; a TTN obtained by the attempt is cancelled at the carrier (best-effort, logged).
- [ ] Back-off list configurable (default `5s, 30s, 2m`).
- [ ] Logging: every failed attempt at `WARN` (orderId, attempt, reason, next attempt); the transition to `TTN_FAILED` at `ERROR`.
- [ ] Tests (Testcontainers PostgreSQL + Kafka, a controllable `Clock`, the mock carrier and WireMock for Inventory):
  - happy path → `TTN_GENERATED`, parcel stored, `TTNGenerated` published once;
  - carrier fails twice then succeeds → generated on the third attempt with the expected `next_attempt_at` values;
  - carrier always fails → `TTN_FAILED` after 4 attempts with the last reason;
  - Inventory `422` → `TTN_FAILED` after one attempt with `packaging_data_missing:{productId}`;
  - cancelled during the attempt → stays `CANCELLED`, no event, carrier `cancel` called;
  - two job instances at once → each shipment registered once;
  - shipments whose `next_attempt_at` is in the future are not picked.

## Technical Notes / Constraints
- Never hold a database transaction or row lock while calling Inventory or the carrier.
- Use an injected `Clock` for `now`, so tests move time instead of sleeping.
- Known gap (spec *Open Issues*): if a pod dies after the carrier issued a TTN but before it was stored, the lease expires and a second TTN is registered. Harmless with the mock; document it in the code where the lease is taken.
- Compare with Resilience4j `Retry`: it fits short, in-request retries (as in Catalog's Inventory client) but not retries minutes apart that must survive restarts. Note this in the implementation notes.
