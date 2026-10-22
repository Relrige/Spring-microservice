---
status: DONE
service: order-service
---
# EP-ORD-JOB-05: Purge Idempotency Keys

## Context
[Task 19](19-idempotent-order-submission.md) stores one `idempotency_keys` row per submit that carries an `Idempotency-Key`. Without cleanup the table grows forever, and a claim left `IN_PROGRESS` by an instance that died mid-request blocks that key with `409 request-in-progress` for good. This job removes both (decision D10, agreed 2026-10-22). **Prerequisite:** task 19 is `DONE`.
*References:* [Order Spec — EP-ORD-JOB-05, EP-ORD-02 (*Idempotency-Key*), D10, Configuration](../../design/services-specs/order-service-spec.md), [task 17 (recovery jobs)](17-review-fixes.md)

## Acceptance Criteria
- [x] `jobs/IdempotencyKeyPurgeJob` runs every `order.recovery.interval` (`ORDER_RECOVERY_INTERVAL`, default `1m`), like `OrphanReservationJob` and `PendingRefundJob`.
- [x] Rule 1: deletes `IN_PROGRESS` rows whose `created_at` is older than `order.recovery.reservation-settle` (`2m`, the same settle time as EP-ORD-JOB-03).
- [x] Rule 2: deletes `COMPLETED` rows whose `created_at` is older than `order.recovery.idempotency-key-ttl` (`ORDER_IDEMPOTENCY_KEY_TTL`, default `24h`). The orders stay.
- [x] Each rule is one bulk `DELETE` (a repository `@Modifying` query); the job logs the number of deleted rows at `DEBUG`, a failure at `WARN`, and a failure in rule 1 does not stop rule 2.
- [x] Configuration: `idempotencyKeyTtl` (`@NotNull Duration`) added to `RecoveryProperties`; `order.recovery.idempotency-key-ttl: ${ORDER_IDEMPOTENCY_KEY_TTL:24h}` in `application.yml`; `ORDER_IDEMPOTENCY_KEY_TTL` in `.env.example` and `k8s/order/app-config.yaml` next to `ORDER_RECOVERY_INTERVAL`. The test properties keep the interval at 1 h, so the job never runs in the background of tests.
- [x] Unit tests (`RecoveryJobsTest`, fixed `Clock`): an `IN_PROGRESS` row 1 s younger than the settle time stays and one 1 s older is deleted; the same for `COMPLETED` around the TTL; a `COMPLETED` row older than the settle time but younger than the TTL stays; a failing rule 1 still runs rule 2.
- [x] Integration test (`CheckoutIntegrationTest`, Testcontainers PostgreSQL + WireMock): a stranded `IN_PROGRESS` row (inserted directly, older than the settle time) makes a submit with its key answer `409`; after one job run the same submit creates the order.
- [x] All Order tests pass (`mvnw.cmd verify` in `order-service/`).

## Technical Notes / Constraints
- **Settled (D10), do not change:** the settle time is the existing 2-minute `reservation-settle`, not a new setting; the TTL default is 24 h; stale claims are cleaned up by this job, not taken over by the claim statement in EP-ORD-02.
- No claim between replicas: deleting the same rows twice is harmless.
- The job only deletes key rows. A reservation of a request whose instance died is released by `OrphanReservationJob` through its own attempt row; do not touch `reservation_attempts` here.
- No batch limit is needed: the rows are small and the `(status, created_at)` index from task 19's migration serves both deletes.

## Decisions (2026-10-22, before implementation)
- **Boundary tests:** `RecoveryJobsTest` mocks the repository, so it checks the exact cutoff instants passed to both deletes (fixed `Clock`) and that a failing rule 1 still runs rule 2. The rows around each boundary (1 s younger / older than the settle time and the TTL, and a `COMPLETED` row between the two) are checked against PostgreSQL in a new `IdempotencyKeyRepositoryTest` (Testcontainers).
- **Cutoff:** a row is deleted when its `created_at` is strictly older than the cutoff (`<`).
- **Configuration files:** `.env.example` is `order-service/.env.example`.

## Implementation Notes (2026-10-22)
- **`jobs/IdempotencyKeyPurgeJob`:** `@Scheduled(fixedDelayString = "${order.recovery.interval}")` with the same initial delay, like `OrphanReservationJob` and `PendingRefundJob`. Rule 1 deletes `IN_PROGRESS` rows created before `now - reservation-settle`, rule 2 `COMPLETED` rows created before `now - idempotency-key-ttl`. Each rule runs in its own transaction inside its own `try`: the count is logged at `DEBUG`, a failure at `WARN`, and rule 2 runs whatever rule 1 did.
- **`IdempotencyKeyRepository.deleteByStatusCreatedBefore(status, createdBefore)`:** one native `@Modifying` bulk `DELETE … WHERE status = ? AND created_at < ?`, served by `idx_idempotency_keys_status_created`; used by both rules.
- **Configuration:** `RecoveryProperties.idempotencyKeyTtl` (`@NotNull Duration`); `order.recovery.idempotency-key-ttl: ${ORDER_IDEMPOTENCY_KEY_TTL:24h}` in `application.yml`; `ORDER_IDEMPOTENCY_KEY_TTL` in `order-service/.env.example` (commented, with the default) and `k8s/order/app-config.yaml` (`"24h"`), next to `ORDER_RECOVERY_INTERVAL`, whose comments now name the purge too. The test properties already keep `order.recovery.interval` at 1 h.
- **`IdempotencyKeys.hash` is now public,** so the integration test can store a claim with the hash its request will have.
- **Verified:**
  - `RecoveryJobsTest` (9, 2 new; fixed `Clock`): the two deletes get exactly `NOW - 2 min` for `IN_PROGRESS` and `NOW - 24 h` for `COMPLETED`, and nothing else is called; a failing rule 1 still runs rule 2.
  - `IdempotencyKeyRepositoryTest` (4, new; PostgreSQL in Testcontainers): an `IN_PROGRESS` row 1 s younger than the settle time stays, one 1 s older is deleted, and a `COMPLETED` row of the same age stays; a `COMPLETED` row 1 s younger than the TTL stays, one 1 s older is deleted, a `COMPLETED` row older than the settle time but inside the TTL stays, and an old `IN_PROGRESS` row is untouched by rule 2. Also task 19's statements: a second claim of the same key returns `0`, the same key of another customer `1`; complete and release do nothing to a `COMPLETED` row.
  - `CheckoutIntegrationTest` (15, 1 new): a stranded `IN_PROGRESS` row with the request's hash, 3 minutes old, makes the submit answer `409 request-in-progress` without calling Inventory; after one `purge()` the same submit places the order (`201`, not replayed).
  - Full suite `mvnw.cmd verify` in `order-service/`: 38 test classes, 371 tests, all green (after the task 19 test fix described there).

## Deviations from spec / task
- **Boundary tests split between two classes** (decided before implementation). The task puts the row-by-row boundary checks into `RecoveryJobsTest`, which mocks the repository and so cannot show which rows a `DELETE` removes. `RecoveryJobsTest` checks the exact cutoffs and the independence of the rules; `IdempotencyKeyRepositoryTest` checks the rows around each cutoff against PostgreSQL. Alternative: an in-memory fake repository, rejected because it would test the fake's comparison, not the SQL.
- **One delete method for both rules** (`deleteByStatusCreatedBefore`) instead of two named ones. The rules differ only in status and cutoff, which the job names in its log line.
- **`batch-size` is not used by the purge.** The task's "no batch limit" note: both deletes run unbounded; the `RecoveryProperties` Javadoc says so.
