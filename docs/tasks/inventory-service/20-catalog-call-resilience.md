---
status: TODO
service: inventory-service
---
# Resilience Policy for the Catalog Call

## Context
The Catalog existence check ([task 19](19-catalog-product-existence-client.md)) has timeouts and maps failures to `503`. This task decides whether that is enough and, if not, adds the minimal resilience mechanism. It is a learning task: the outcome is a documented decision with tests, not necessarily a library. It mirrors [Catalog's resilience task](../catalog-service/22-inventory-call-resilience.md), so the two services should reach a consistent decision.
*References:* [Inventory Spec — EP-INV-02, EP-INV-03](../../design/services-specs/inventory-service-spec.md), [Catalog existence check](19-catalog-product-existence-client.md)

## Acceptance Criteria
- [ ] A short decision record (in the task file or `docs/`) compares at least: (a) timeouts only (current), (b) timeouts + bounded retry for the idempotent `GET`, (c) circuit breaker (for example Resilience4j via Spring Cloud CircuitBreaker). It states the choice and why, given that the call happens only the first time a worker touches a product that has no `StockItem` yet (the `ProductCreated` event normally creates it).
- [ ] The chosen mechanism is applied around the Catalog adapter, not scattered across controllers or services.
- [ ] Failure behaviour is verified with tests: Catalog slow, down, flapping. A "not found" answer is a business result: never retried, never a failure for a breaker.
- [ ] Client-visible result stays `503` with a stable, non-leaking message (and an optional `Retry-After` hint when retries/breaker are used).
- [ ] Failures are logged once per attempt without the internal token.
- [ ] If no extra library is chosen, the record says so and lists what would trigger a revisit.

## Technical Notes / Constraints
- Retrying a `GET` is safe; blind retries multiply load on a struggling service. If added, bound attempts, use back-off and jitter, and keep the total time below the gateway timeout.
- Because the event path usually covers `StockItem` creation, the sync call is a rare fallback; discuss honestly whether a circuit breaker is worth the extra moving part.
- Libraries are deliberately not fixed upfront (see AGENTS.md); check Spring Boot 4.x compatibility before choosing.
