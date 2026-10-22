---
status: TODO
service: order-service
---
# EP-ORD-JOB-05: Purge Idempotency Keys

## Context
[Task 19](19-idempotent-order-submission.md) stores one `idempotency_keys` row per submit that carries an `Idempotency-Key`. Without cleanup the table grows forever, and a claim left `IN_PROGRESS` by an instance that died mid-request blocks that key with `409 request-in-progress` for good. This job removes both (decision D10, agreed 2026-10-22). **Prerequisite:** task 19 is `DONE`.
*References:* [Order Spec — EP-ORD-JOB-05, EP-ORD-02 (*Idempotency-Key*), D10, Configuration](../../design/services-specs/order-service-spec.md), [task 17 (recovery jobs)](17-review-fixes.md)

## Acceptance Criteria
- [ ] `jobs/IdempotencyKeyPurgeJob` runs every `order.recovery.interval` (`ORDER_RECOVERY_INTERVAL`, default `1m`), like `OrphanReservationJob` and `PendingRefundJob`.
- [ ] Rule 1: deletes `IN_PROGRESS` rows whose `created_at` is older than `order.recovery.reservation-settle` (`2m`, the same settle time as EP-ORD-JOB-03).
- [ ] Rule 2: deletes `COMPLETED` rows whose `created_at` is older than `order.recovery.idempotency-key-ttl` (`ORDER_IDEMPOTENCY_KEY_TTL`, default `24h`). The orders stay.
- [ ] Each rule is one bulk `DELETE` (a repository `@Modifying` query); the job logs the number of deleted rows at `DEBUG`, a failure at `WARN`, and a failure in rule 1 does not stop rule 2.
- [ ] Configuration: `idempotencyKeyTtl` (`@NotNull Duration`) added to `RecoveryProperties`; `order.recovery.idempotency-key-ttl: ${ORDER_IDEMPOTENCY_KEY_TTL:24h}` in `application.yml`; `ORDER_IDEMPOTENCY_KEY_TTL` in `.env.example` and `k8s/order/app-config.yaml` next to `ORDER_RECOVERY_INTERVAL`. The test properties keep the interval at 1 h, so the job never runs in the background of tests.
- [ ] Unit tests (`RecoveryJobsTest`, fixed `Clock`): an `IN_PROGRESS` row 1 s younger than the settle time stays and one 1 s older is deleted; the same for `COMPLETED` around the TTL; a `COMPLETED` row older than the settle time but younger than the TTL stays; a failing rule 1 still runs rule 2.
- [ ] Integration test (`CheckoutIntegrationTest`, Testcontainers PostgreSQL + WireMock): a stranded `IN_PROGRESS` row (inserted directly, older than the settle time) makes a submit with its key answer `409`; after one job run the same submit creates the order.
- [ ] All Order tests pass (`mvnw.cmd verify` in `order-service/`).

## Technical Notes / Constraints
- **Settled (D10), do not change:** the settle time is the existing 2-minute `reservation-settle`, not a new setting; the TTL default is 24 h; stale claims are cleaned up by this job, not taken over by the claim statement in EP-ORD-02.
- No claim between replicas: deleting the same rows twice is harmless.
- The job only deletes key rows. A reservation of a request whose instance died is released by `OrphanReservationJob` through its own attempt row; do not touch `reservation_attempts` here.
- No batch limit is needed: the rows are small and the `(status, created_at)` index from task 19's migration serves both deletes.
