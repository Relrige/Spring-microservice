---
status: TODO
service: catalog-service
---
# Resilience Policy for the Inventory Call

## Context
The spec leaves the behaviour when Inventory is unavailable open: "propagate as `503` or handle with a circuit breaker (implementation TBD)". After [task 21](21-inventory-readiness-client.md) the call has timeouts and maps failures to `503`. This task decides whether that is enough and, if not, adds the minimal resilience mechanism. It is a learning task: the outcome is a documented decision with tests, not necessarily a library.
*References:* [Catalog Spec — EP-CAT-03 edge cases](../../design/services-specs/catalog-service-spec.md), [Inventory readiness client](21-inventory-readiness-client.md)

## Acceptance Criteria
- [ ] Write a short decision record (in the task file or `docs/`) comparing at least: (a) timeouts only (current), (b) timeouts + bounded retry for idempotent `GET`, (c) circuit breaker (e.g. Resilience4j via Spring Cloud CircuitBreaker). State which one is chosen and why, given that activation is a rare, manager-triggered action.
- [ ] The chosen mechanism is implemented around the `StockReadinessChecker` adapter, not scattered across controllers.
- [ ] Failure behaviour is verified with tests: Inventory slow, down, flapping. Business outcomes (`422`) are never retried and never count as failures for a breaker.
- [ ] The client-visible result stays `503` with a stable, non-leaking message and an optional `Retry-After` hint if retries/breaker are used.
- [ ] Failures are logged once per attempt at an appropriate level without the internal token.
- [ ] If no extra library is chosen, the decision record says so and lists what would trigger a revisit.

## Technical Notes / Constraints
- Retrying a `GET` readiness check is safe (idempotent). Retrying blindly multiplies load on a struggling service; if retries are added, bound them (attempts, backoff, jitter) and keep the total time below the gateway's timeout.
- A circuit breaker pays off when many callers hit a failing dependency. Here one manager clicks "activate"; discuss honestly whether the extra moving part is worth it, in the spirit of the project (it is acceptable that the answer is "no, timeouts are enough").
- Libraries are deliberately not fixed upfront by the project (see AGENTS.md). Check the current Spring Boot 4.x compatibility before picking one.
