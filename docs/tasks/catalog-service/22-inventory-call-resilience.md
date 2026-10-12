---
status: DONE
service: catalog-service
---
# Resilience Policy for the Inventory Call

## Context
The spec leaves the behaviour when Inventory is unavailable open: "propagate as `503` or handle with a circuit breaker (implementation TBD)". After [task 21](21-inventory-readiness-client.md) the call has timeouts and maps failures to `503`. This task decides whether that is enough and, if not, adds the minimal resilience mechanism. It is a learning task: the outcome is a documented decision with tests, not necessarily a library.
*References:* [Catalog Spec — EP-CAT-03 edge cases](../../design/services-specs/catalog-service-spec.md), [Inventory readiness client](21-inventory-readiness-client.md)

## Acceptance Criteria
- [x] Write a short decision record (in the task file or `docs/`) comparing at least: (a) timeouts only (current), (b) timeouts + bounded retry for idempotent `GET`, (c) circuit breaker (e.g. Resilience4j via Spring Cloud CircuitBreaker). State which one is chosen and why, given that activation is a rare, manager-triggered action.
- [x] The chosen mechanism is implemented around the `StockReadinessChecker` adapter, not scattered across controllers.
- [x] Failure behaviour is verified with tests: Inventory slow, down, flapping. Business outcomes (`422`) are never retried and never count as failures for a breaker.
- [x] The client-visible result stays `503` with a stable, non-leaking message and an optional `Retry-After` hint if retries/breaker are used.
- [x] Failures are logged once per attempt at an appropriate level without the internal token.
- [x] If no extra library is chosen, the decision record says so and lists what would trigger a revisit.

## Technical Notes / Constraints
- Retrying a `GET` readiness check is safe (idempotent). Retrying blindly multiplies load on a struggling service; if retries are added, bound them (attempts, backoff, jitter) and keep the total time below the gateway's timeout.
- A circuit breaker pays off when many callers hit a failing dependency. Here one manager clicks "activate"; discuss honestly whether the extra moving part is worth it, in the spirit of the project (it is acceptable that the answer is "no, timeouts are enough").
- Libraries are deliberately not fixed upfront by the project (see AGENTS.md). Check the current Spring Boot 4.x compatibility before picking one.

## Decision Record — Resilience of the Inventory Readiness Call

**Context.** One call site: a Catalog Manager activates a product, a few times a day. The answer must be correct (never activate an unready product), and a failure only means "try again later". Activation is not on any customer path.

| Option | What it adds | Pros | Cons |
|---|---|---|---|
| (a) Timeouts only | connect 2 s / read 3 s (task 21) | simplest; a dead Inventory costs at most ~3 s per click | a single dropped packet or a pod restart fails the click, and the manager has to retry by hand |
| (b) Timeouts + bounded retry | 1 retry after 300 ms (+ up to 150 ms jitter), only for connection errors, timeouts and `5xx` | hides short blips (rolling restart of the 2 Inventory replicas); the `GET` is idempotent, so retrying is safe; no extra library (Spring Framework 7 `RetryTemplate`) | worst case about 6.5 s (2 × 3 s + delay); doubles the calls to a struggling Inventory (but only for this rare action) |
| (c) Circuit breaker (Resilience4j, already used in order-service) | fail fast after repeated failures | protects a dependency from many callers and frees threads quickly | here there is one occasional caller: the breaker would rarely see enough calls to open, needs tuning and state per instance (2 replicas), and adds a library and configuration for no real benefit |

**Decision: (b).** Timeouts plus one retry with jitter, implemented with Spring Framework 7's built-in `org.springframework.core.retry.RetryTemplate` inside the `InventoryStockReadinessChecker` adapter (nothing in the controller or the product service). No new dependency.

- **Retried:** connection errors, timeouts, `5xx`. **Never retried and never failures:** `204`, `404`, `422` (business answers). **Not retried:** other `4xx` such as `401`/`403`, which signal a configuration error that a retry cannot fix.
- **Bounded:** `clients.inventory.max-retries` = 1, `retry-delay` = 300 ms with 50% jitter. Worst case about 2 × 3 s + 0.45 s, well under typical gateway timeouts (30–60 s).
- **Client view:** `503` with a fixed message ("Stock readiness cannot be checked right now. Please try again later.") and `Retry-After: 10`; no hosts, statuses or causes leak.
- **Logging:** each failed attempt is logged once at `WARN` (transient) or `ERROR` (unexpected status), and the final `503` once at `ERROR`; the internal token is never logged.
- **Tests:** slow (read timeout → `503`), down (`500` twice → `503`, connection refused → `503`), flapping (`503` then `204` → activated after the retry), `422` and `404` answered after exactly one call.

**Revisit (and consider the order-service Resilience4j setup) when:** Catalog calls Inventory on a frequent or customer-facing path (e.g. on every product view); several Catalog features depend on Inventory at once; or monitoring shows Inventory outages long enough that every activation click waits the full timeout.
