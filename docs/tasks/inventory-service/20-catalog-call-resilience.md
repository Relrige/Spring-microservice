---
status: DONE
service: inventory-service
---
# Resilience Policy for the Catalog Call

## Context
The Catalog existence check ([task 19](19-catalog-product-existence-client.md)) has timeouts and maps failures to `503`. This task decides whether that is enough and, if not, adds the minimal resilience mechanism. It is a learning task: the outcome is a documented decision with tests, not necessarily a library. It mirrors [Catalog's resilience task](../catalog-service/22-inventory-call-resilience.md), so the two services should reach a consistent decision.
*References:* [Inventory Spec — EP-INV-02, EP-INV-03](../../design/services-specs/inventory-service-spec.md), [Catalog existence check](19-catalog-product-existence-client.md)

## Acceptance Criteria
- [x] A short decision record (in the task file or `docs/`) compares at least: (a) timeouts only (current), (b) timeouts + bounded retry for the idempotent `GET`, (c) circuit breaker (for example Resilience4j via Spring Cloud CircuitBreaker). It states the choice and why, given that the call happens only the first time a worker touches a product that has no `StockItem` yet (the `ProductCreated` event normally creates it).
- [x] The chosen mechanism is applied around the Catalog adapter, not scattered across controllers or services.
- [x] Failure behaviour is verified with tests: Catalog slow, down, flapping. A "not found" answer is a business result: never retried, never a failure for a breaker.
- [x] Client-visible result stays `503` with a stable, non-leaking message (and an optional `Retry-After` hint when retries/breaker are used).
- [x] Failures are logged once per attempt without the internal token.
- [x] If no extra library is chosen, the record says so and lists what would trigger a revisit.

## Technical Notes / Constraints
- Retrying a `GET` is safe; blind retries multiply load on a struggling service. If added, bound attempts, use back-off and jitter, and keep the total time below the gateway timeout.
- Because the event path usually covers `StockItem` creation, the sync call is a rare fallback; discuss honestly whether a circuit breaker is worth the extra moving part.
- Libraries are deliberately not fixed upfront (see AGENTS.md); check Spring Boot 4.x compatibility before choosing.

## Decision Record — Resilience of the Catalog Existence Check (2026-10-14)

**Context.** One call site: a worker adjusts stock or sets packaging for a product that has **no StockItem yet**. Normally `ProductCreated` (task 15) has created the StockItem already, so Catalog is called only when that event was missed or is late (a rare fallback). The answer must be correct (never create a StockItem for an unknown product), and a failure only means "try again later": the worker repeats the action. The call is on a staff path, not a customer path.

| Option | What it adds | Pros | Cons |
|---|---|---|---|
| (a) Timeouts only | connect 2 s / read 3 s (task 19) | simplest; a dead Catalog costs at most ~3 s per action | a single dropped packet or a rolling restart of Catalog fails the worker's action, and the worker must retry by hand |
| (b) Timeouts + bounded retry | 1 retry after 300 ms (+ up to 150 ms jitter), only for connection errors, timeouts and `5xx` | hides short blips (e.g. a rolling restart of Catalog's 2 replicas); `batch-get` is a `GET` and idempotent, so retrying is safe; no extra library (Spring Framework 7 `RetryTemplate`) | worst case about 6.5 s (2 × 3 s + delay); doubles the calls to a struggling Catalog, but only on this rare path |
| (c) Circuit breaker (Resilience4j via Spring Cloud CircuitBreaker; order-service already uses Resilience4j) | fail fast after repeated failures | protects Catalog from many callers and frees request threads quickly | here the call is rare: a breaker would almost never see enough calls in its window to open, needs tuning and keeps per-replica state (2 replicas), and adds a library and configuration for no measurable benefit |

**Decision: (b)**, the same choice as Catalog's call to Inventory ([Catalog task 22](../catalog-service/22-inventory-call-resilience.md)), so both directions behave alike. Timeouts plus one retry with jitter, implemented with Spring Framework 7's built-in `org.springframework.core.retry.RetryTemplate` inside the `CatalogProductCatalog` adapter (nothing in the controller or `StockService`). No new dependency, no circuit breaker.

- **Retried:** connection errors, timeouts, `5xx`. **Never retried and never a failure:** a `200` answer, with or without the product ("not found" is a business result, an empty array). **Not retried:** other `4xx` such as `401`/`403`, which signal a configuration error (wrong token, route rule) that a retry cannot fix.
- **Bounded:** `clients.catalog.max-retries` = 1, `retry-delay` = 300 ms with 50% jitter (`CATALOG_MAX_RETRIES`, `CATALOG_RETRY_DELAY`). Worst case about 2 × 3 s + 0.45 s, well under typical gateway timeouts (30–60 s).
- **Client view:** `503` with the fixed message from task 19 and `Retry-After: 10`; no hosts, statuses or causes leak.
- **Logging:** each failed attempt is logged once by the adapter (`WARN` for transient failures, `ERROR` for an unexpected status), and the final `503` once by the exception handler at `ERROR`; the internal token is never logged.
- **Tests (WireMock):** slow (read timeout twice → `503` after exactly 2 calls), down (`500` twice → unavailable; connection refused → unavailable), flapping (`503` then `200` with the product → found after the retry), "not found" and `401` answered after exactly one call; end to end: `503` carries `Retry-After: 10`.

**Revisit (and consider Resilience4j as in order-service) when:** Inventory calls Catalog on a frequent or customer-facing path (e.g. during reservation); monitoring shows Catalog outages long enough that workers keep waiting the full timeout; or more Inventory features start depending on Catalog synchronously, so that fail-fast protection of request threads matters.

## Implementation Notes (2026-10-14)
- **Code:** `CatalogProductCatalog` wraps one attempt (`existsOnce`) in a Spring Framework 7 `RetryTemplate` (`RetryPolicy.builder().maxRetries(1).delay(300ms).jitter(150ms).includes(TransientFailure.class)`); only connection errors, timeouts and `5xx` become `TransientFailure`. `CatalogClientProperties` gained `maxRetries` / `retryDelay` (`clients.catalog.max-retries`, `retry-delay`; env `CATALOG_MAX_RETRIES`, `CATALOG_RETRY_DELAY`, documented as optional in `.env.example`).
- **503:** `GlobalExceptionHandler` now answers `CatalogUnavailableException` with `Retry-After: 10` next to the fixed message from task 19.
- **Tests:** `CatalogProductCatalogTest` (down: `500` → 2 calls then unavailable; slow: read timeout → 2 calls; flapping: `503` then found → `true` after 2 calls; empty answer and `401` → exactly 1 call); `CatalogExistenceCheckIntegrationTest` checks `Retry-After: 10` and the two calls end to end. The retry delay is shortened to 50 ms in tests.
- No new dependency; no circuit breaker (see the decision record above).
