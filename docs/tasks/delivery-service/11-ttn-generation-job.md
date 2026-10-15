---
status: DONE
service: delivery-service
---
# Automatic TTN Generation Job (Tier 1 and Tier 2)

## Context
This is the core of the service. A pending shipment must become `TTN_GENERATED`, or `TTN_FAILED` with a reason the worker can act on. Carrier failures are retried automatically (Tier 1: 3 retries after 5 s, 30 s, 2 min); when they run out, the shipment is flagged (Tier 2) for the worker's manual retry (Tier 3, [task 14](14-manual-ttn-retry.md)). A paid order is never cancelled because of a carrier failure.

Retries are **database-driven** (`attempts`, `next_attempt_at`) instead of happening inside the Kafka listener. Back-offs of minutes would block the partition, and in-memory retry state would be lost on a restart.
*References:* [Delivery Spec — Automatic TTN generation, Parcel calculation](../../design/services-specs/delivery-service-spec.md), [SCN-S04, SCN-S05](../../design/scenarios/system-scenarios.md)

## Acceptance Criteria
- [x] A `TtnRegistrationService` that runs **one attempt** for a shipment: fetch packaging (task 09) → calculate the parcel (task 08) → `CarrierGateway.register` (task 07). It returns success (TTN, parcel), a retryable failure, or a non-retryable failure with a `reason`. The manual retry reuses it.
- [x] A scheduled job (configurable interval, e.g. 1 s) claims due shipments (`TTN_PENDING`, `next_attempt_at <= now`, oldest first, batch size configurable) with `FOR UPDATE SKIP LOCKED`, moves their `next_attempt_at` forward by a lease (configurable, e.g. 2 min) and commits **before** any remote call.
- [x] Each attempt runs outside a transaction; its result is applied in a new transaction only if the shipment is still `TTN_PENDING` (optimistic lock):
  - success → `markGenerated`, append `TTNGenerated` to the outbox (task 06), same transaction;
  - retryable → `attempts + 1`, `failureReason`, `next_attempt_at = now + backoff[attempts]`; after the last back-off → `markFailed`;
  - non-retryable → `markFailed(reason)` immediately.
- [x] If the shipment was cancelled meanwhile, the result is discarded; a TTN obtained by the attempt is cancelled at the carrier (best-effort, logged).
- [x] Back-off list configurable (default `5s, 30s, 2m`).
- [x] Logging: every failed attempt at `WARN` (orderId, attempt, reason, next attempt); the transition to `TTN_FAILED` at `ERROR`.
- [x] Tests (Testcontainers PostgreSQL + Kafka, a controllable `Clock`, the mock carrier and WireMock for Inventory):
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

## Implementation Notes (2026-10-15)
- **`services/TtnRegistrationService.attempt(ShipmentSnapshot) → TtnAttemptResult`** (sealed: `Success(ttn, parcel)` / `Failure(reason, retryable)`): `PackagingProvider` → `ParcelCalculator` → `CarrierGateway.register`. Mapping: `PackagingUnavailableException` keeps its reason and retryability (task 09); a parcel that cannot be calculated → `parcel_calculation_failed`, not retryable; `CarrierException` → `carrier_error: <message>`, retryable. No database access, so it runs outside any transaction; the manual retry (task 14) reuses it. `cancelOrphanedTtn` is the best-effort carrier cancel for a discarded result.
- **`ShipmentSnapshot`:** an immutable copy (id, orderId, **version**, carrier, recipient, items, declared value, currency) taken in the claim transaction, because the items are a lazy collection and the attempt must not hold a persistence context.
- **`services/TtnGenerationJob`** (`@Scheduled(fixedDelay = delivery.ttn-job.interval)`, switchable with `delivery.ttn-job.enabled`):
  - *Claim* (one transaction): native `SELECT … WHERE status = 'TTN_PENDING' AND next_attempt_at <= :now ORDER BY next_attempt_at LIMIT :batch FOR UPDATE SKIP LOCKED`, then a native `UPDATE … SET next_attempt_at = now + lease`. The lease update is native on purpose: it does not bump `@Version`, so the version in the snapshot still means "nobody changed the shipment since the claim". Commit before any remote call.
  - *Attempt* outside a transaction, with a fresh correlation id in the MDC (sent to Inventory).
  - *Apply* (new transaction): reload; if status is not `TTN_PENDING` or the version differs → discard. Success → `markGenerated` + `TTNGenerated` via the outbox; retryable failure with back-offs left → `recordFailedAttempt(reason, now + backoff[attempts])`; otherwise → `markFailed(reason)`. A concurrent commit between reload and commit raises `ObjectOptimisticLockingFailureException`, also treated as "discard". A discarded success cancels its TTN at the carrier.
  - A run repeats while full batches are claimed. An unexpected exception for one shipment is logged; the lease then expires and the attempt is redone.
  - Logging: failed attempt `WARN` (orderId, attempt, reason, next attempt), transition to `TTN_FAILED` `ERROR`, success `INFO`.
- **Configuration** (`delivery.ttn-job`): `enabled: true`, `interval: 1s`, `batch-size: 20`, `lease: 2m`, `backoff: 5s, 30s, 2m` (validated `TtnJobProperties`). With three back-offs the shipment is `TTN_FAILED` after 4 failed attempts (`attempts = 4`).
- **Known gap** (spec, Open Issues) documented on the claim method: a pod dying after the carrier issued a TTN but before it was stored leads to a second TTN after the lease; a real carrier would need `orderId` as an idempotency key.
- **Resilience4j `Retry` vs. this job:** `Retry` fits short in-request retries (Catalog's Inventory client). Here the waits are minutes long and must survive restarts, so the state lives in the `shipments` table and `TTN_PENDING` stays visible to the fulfillment queue meanwhile. Also in the class Javadoc.
- **Verified (2026-10-15):** `TtnGenerationJobIntegrationTest` (Testcontainers PostgreSQL + Kafka, a spied mock carrier, WireMock as Inventory, `MutableClock` moved by the test; the scheduled run is pushed to `1h`): happy path (parcel stored, one `TTNGenerated` row); carrier fails twice → generated on attempt 3 with `next_attempt_at` = +5 s, then +30 s, not picked before it is due; carrier always fails → `TTN_FAILED` with `attempts = 4` and the last reason; Inventory `422` → `TTN_FAILED` after one attempt with `packaging_data_missing:{productId}`, carrier not called; Inventory `503` → retried; cancelled during the carrier call → stays `CANCELLED`, no event, the new TTN cancelled at the carrier; two job runs at once on 8 shipments → 8 registrations, 8 events; a shipment due in the future is not picked.
