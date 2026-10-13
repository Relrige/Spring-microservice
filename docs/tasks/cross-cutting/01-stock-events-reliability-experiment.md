---
status: TODO
service: cross-cutting
---
# Stock Events Reliability: Failure Experiments and Demonstration

## Context
The course assignment on reliable inter-service messaging is implemented on the Inventory → Catalog stock-event flow:
- **Inventory** ([task 17](../inventory-service/17-transactional-outbox.md)): transactional outbox, one transaction manager, per-product `version`.
- **Catalog** ([task 23](../catalog-service/23-stock-event-version-guard.md)): version guard.
- **Catalog** ([task 24](../catalog-service/24-idempotent-stock-event-consumer.md)): idempotent consumer with a processed-events table.
- **Catalog** ([task 25](../catalog-service/25-stock-events-retryable-topic-and-dlt.md)): `@RetryableTopic` + DLT, including the DLT integration test.

Two assignment criteria cannot be shown by unit-level tests alone:
- "the dual-write problem is fully eliminated";
- "an experiment with an artificial consumer failure confirms that business effects are not duplicated".

This task runs those experiments against the real deployment (Compose, optionally minikube) and records the evidence.
*References:* [Manual e2e runbooks](../../../e2e/manual/README.md), [Inventory containerization](../inventory-service/18-containerization-and-deployment.md), [Catalog containerization](../catalog-service/20-containerization-and-deployment.md)

## Acceptance Criteria
- [ ] **Fault injection in Catalog's stock-event handler**, off by default and enabled only by an explicit property or profile (never in the default `application.yml` values for Compose/Kubernetes). It is limited to one configured `productId`, so other traffic is unaffected. Modes:
  - `FAIL_BEFORE_COMMIT`: throw inside the transaction → rollback → the record goes through the retry topics;
  - `CRASH_AFTER_COMMIT`: after the transaction has committed but **before** the listener returns (so before the offset commit), stop the JVM abruptly (`Runtime.getRuntime().halt(…)`).

  Explain in the notes why `CRASH_AFTER_COMMIT` cannot loop: on redelivery the event is recognised as a duplicate and skipped before the crash point.
- [ ] **Evidence for "no duplicated business effect"** is chosen and documented. Recommended:
  - one `processed_events` row per `eventId`;
  - exactly one "applied" log line per `eventId`, plus a "duplicate skipped" log line or counter for the redelivery;
  - the final `stock_status` and `stock_event_version` in Catalog.

  The team decides whether to also add an append-only effect that would visibly duplicate without deduplication (for example a `product_stock_status_changes` row per applied event). The status update itself is idempotent by nature, so state alone does not prove much.
- [ ] **Experiment 1: dual write eliminated (producer side).**
  1. Stop the Kafka broker.
  2. Adjust a product's stock 0 → 10 in Inventory → `200` (and quickly, without hanging on `max.block.ms`); the outbox row is unpublished; the relay logs the age of the oldest unpublished event.
  3. Start the broker → the event is published once.
  4. Catalog shows `IN_STOCK`.
- [ ] **Experiment 2: producer crash after commit.** Kill the Inventory container/pod right after a stock change, before the relay runs (for example with a long relay interval set for the experiment). After the restart the event is still published.
- [ ] **Experiment 3: consumer crash after commit (the duplicate case).**
  1. Enable `CRASH_AFTER_COMMIT` for one product, then adjust its stock.
  2. Catalog applies the event and dies before committing the offset.
  3. After the restart the record is redelivered and skipped as a duplicate. Evidence as chosen above: one `processed_events` row, one "applied", one "duplicate skipped", correct final state.
- [ ] **Experiment 4: consumer failure with retries and DLT.**
  - With `FAIL_BEFORE_COMMIT`, short outage: the record passes through the retry topics, the injection is turned off (or the outage ends) before the attempts run out, and the event is applied exactly once.
  - Long outage: the record ends in the DLT, is replayed manually after the fix, and is applied once (or discarded as stale if a newer event has already been applied).
- [ ] **Optional, experiment 5: relay republish.** Mark a published outbox row as unpublished in Inventory's database → the relay sends it again → Catalog skips it as a duplicate.
- [ ] Each experiment is written as a repeatable runbook in `e2e/manual/` (same conventions as the existing runbooks: Git Bash, IDs in shell variables) with the expected output. The `e2e/manual/README.md` table is updated.
- [ ] A results record (in this task's notes or next to the runbook): date, environment, what was observed (log excerpts, SQL query results, consumer-group lag), and any surprises.

## Technical Notes / Constraints
- **Depends on** Inventory task 17 and Catalog tasks 18, 23, 24 and 25, and on both services running in Compose (Inventory task 18, Catalog task 20).
- **Crash point placement:** the service method is `@Transactional`, so "after commit, before offset commit" means after the service call has returned, inside the listener method. Alternatively use a `TransactionSynchronization.afterCommit` callback. With ack mode `BATCH` or `RECORD`, the offset is committed only after the listener returns, which is exactly the gap this experiment exercises.
- `docker kill` or `kubectl delete pod --grace-period=0` is an alternative to `halt`, but the timing is hard to hit by hand. The injected crash makes the experiment deterministic.
- Compose restarts: check whether `catalog-service` has a `restart:` policy. Otherwise restart it manually in the runbook. In Kubernetes the Deployment restarts the pod by itself.
- Keep the fault-injection code small, in its own class, and clearly marked as an experiment tool. It must not change the normal code path when disabled.
- The automated counterparts (duplicate delivery, DLT routing, rollback) are the integration tests in tasks 17, 24 and 25. This task is about showing it end to end on the real deployment.
