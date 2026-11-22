---
status: DONE
service: order-service
---
# Circuit Breaker Test Through Toxiproxy

## Context
Order's calls to Catalog, Payment, Delivery and Inventory are protected by Resilience4j circuit breakers with fallbacks. The existing client tests slow down or fail the stub (WireMock), but none shows the breaker switching to OPEN, the fallback answering without a network call, and the breaker closing again. The course requires such a test, with an artificial network fault, and names Toxiproxy for fault-tolerance tests (Topic 10).
*References:* [Order Service Spec](../../design/services-specs/order-service-spec.md), [declarative HTTP client task](declarative-http-client.md)

## Acceptance Criteria
- [x] A test puts a Toxiproxy container between Order's Catalog client and a stub Catalog, so the fault is on a real TCP connection.
- [x] Added latency beyond the read timeout opens the `catalog` circuit breaker (state `OPEN` in the `CircuitBreakerRegistry`).
- [x] While OPEN, a call fails fast through the fallback (`DependencyUnavailableException`, answered as `503`) without reaching Catalog.
- [x] After the fault is removed and the open-state wait has passed, the breaker goes `HALF_OPEN` and then `CLOSED` on successful trial calls.
- [x] A second scenario cuts the connection instead of slowing it, with the same result.
- [x] The test runs in the normal `mvn test` with Testcontainers; nothing is started by hand.

## Decisions (2026-11-22)
1. **Toxiproxy through `org.testcontainers:testcontainers-toxiproxy`** (test scope, Order only), version managed by Spring Boot's Testcontainers BOM. The library choice was the user's, as the course names it.
2. **Catalog client as the subject:** it runs Retry → CircuitBreaker → Bulkhead, so the test also shows that each retried attempt counts against the breaker. The stub Catalog is the WireMock already used by the client tests, reached by the container as `host.testcontainers.internal`.
3. **Production settings, two shortened:** the breaker's thresholds stay as in `application.yml` (window 10, at least 4 calls, 50 %); the read timeout is 500 ms and the open-state wait 1 s in the test, so a run takes seconds.
4. **Branch `order-service/circuit-breaker-toxiproxy`** from `main`, this task file.

## Implementation Notes (2026-11-22)
- **`clients/CatalogCircuitBreakerToxiproxyTest`:** the client context of `ClientsTestBase` (configuration, adapters, Resilience4j aspects, no database), with `clients.catalog.url` pointing at the proxy's mapped port. The proxy forwards to WireMock on the host (`Testcontainers.exposeHostPorts`).
  - *Slow network:* a `latency` toxic of 2 s downstream. Two calls fail as `DependencyUnavailableException` (3 attempts each, every attempt a read timeout), and the breaker is `OPEN`. The next call fails in under 300 ms, WireMock receives nothing, and the breaker counts a not-permitted call. Without the toxic and after 1.1 s, the first call succeeds in `HALF_OPEN`, the second closes the breaker.
  - *Cut connection:* `proxy.disable()` refuses connections; same assertions; `proxy.enable()` for the recovery.
- **Image `shopify/toxiproxy:2.1.4`** from Docker Hub. The newer `ghcr.io/shopify/toxiproxy` images could not be pulled on the development machine (the GitHub registry answered "denied"); the Docker Hub image needs no registry credentials, locally or on CI. It supports the latency toxic and enable/disable used here.
- **Verified:** `CatalogCircuitBreakerToxiproxyTest`, 2 tests, green on two consecutive runs. Full Order suite `mvnw test`: 388 tests, all green.

## Deviations from spec / task
- **Each scenario gets a fresh Spring context (`@DirtiesContext`).** With one shared context, the second scenario failed: a connection pooled through the proxy in the first scenario stayed half-open after `disable()`/`enable()`, and the first trial call got headers and then a closed body stream. That is an artifact of Toxiproxy 2.1.4; a restarted Catalog pod closes the TCP connection, which the client sees as an I/O error that the Retry already covers. A fresh context gives each scenario a fresh HTTP client.
