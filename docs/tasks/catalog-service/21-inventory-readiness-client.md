---
status: DONE
service: catalog-service
---
# Inventory Readiness Check on Activation (Sync Inter-Service Call)

## Context
Until now, [task 14](14-change-product-status.md) activates a product without any check, deliberately deviating from the spec. This task introduces the first synchronous inter-service call in Catalog Service and brings activation in line with EP-CAT-03: before setting `ACTIVE`, call Inventory Service `GET /inventory/stock/{productId}/readiness`. The call carries no user and is authenticated with the shared internal token. Inventory's answer is either ready, or not ready with a reason code.
*References:* [Catalog Spec — EP-CAT-03](../../design/services-specs/catalog-service-spec.md), [Inventory Spec — EP-INV-04](../../design/services-specs/inventory-service-spec.md), [Declarative HTTP Client (order-service)](../order-service/declarative-http-client.md), [Internal token sender (order-service)](../order-service/internal-token-sender-and-security.md)

## Acceptance Criteria
- [x] Declarative `@HttpExchange` interface `InventoryClient` with `@GetExchange("/inventory/stock/{productId}/readiness")`, built on `RestClient` + `HttpServiceProxyFactory` (no `RestTemplate`).
- [x] Inventory answers `204 No Content` (ready), `404` (no `StockItem`) or `422 { reason }` (e.g. `packaging_data_missing`). The `422` error DTO uses `@JsonIgnoreProperties(ignoreUnknown = true)` (tolerant reader).
- [x] The activation path in the product service asks a small interface (suggested name `StockReadinessChecker`) whether the product is ready; an adapter implements it on top of the client. Result "ready" → set `ACTIVE`, `200`. Result "not ready, reason" → `422` with the reason code (`stock_item_not_found` for Inventory's `404`, `packaging_data_missing`, …) via `ProductActivationRejectedException`; status unchanged. Unknown reason codes are passed through.
- [x] Inventory unreachable: connection error, timeout, `5xx`, or an unexpected `4xx` such as `401`/`403` → `InventoryUnavailableException` → `503`; status unchanged. An unexpected `4xx` is logged as an error because it signals a configuration problem, not a business result.
- [x] Deactivation still makes no external call. Activating an already `ACTIVE` product: decide and document whether the check is re-run (suggested: yes) and test it.
- [x] `InternalTokenInterceptor` (`ClientHttpRequestInterceptor`) adds `X-Internal-Token` from `INTERNAL_TOKEN`; registered only on the `RestClient` that targets Inventory. The token is never logged. `X-User-Id` / `X-User-Role` are **not** forwarded.
- [x] A correlation-ID interceptor is added if the order-service client already has one, with the same header name.
- [x] Base URL is configurable (`INVENTORY_SERVICE_URL`; check the Service name and port in `k8s/inventory/` and `docker-compose.yml`) and present in `.env.example`, Compose, and `k8s/catalog/app-config.yaml`.
- [x] Connect timeout 2s and read timeout 3s via `JdkClientHttpRequestFactory` (same as the order-service client), both configurable.
- [x] The temporary-deviation comment and test from task 14 are replaced by real behaviour and tests.
- [x] Tests with WireMock: ready (`204`), `422` with `packaging_data_missing`, `404`, `500`, timeout (delay above the read timeout), connection refused, and assertions that `X-Internal-Token` is sent and `X-User-*` is not.
- [x] End-to-end controller test: `PATCH …/status` with `ACTIVE` against WireMock returns `200`, `422` and `503` accordingly, and the stored status is unchanged on `422`/`503`.

## Technical Notes / Constraints
- `204`, `404` and `422` from Inventory are all valid business answers. Make sure the `RestClient` does not throw for `404`/`422` before the adapter can read the body (look at `onStatus` handling or catch `RestClientResponseException`).
- Model the checker's result as a small value type (ready / not ready with reason); use exceptions only for the exceptional "Inventory is unavailable" case.
- Inventory may not be implemented when this is built; WireMock stands in for it. Agree on the reason codes with the Inventory spec.
- Inventory returns `404` for a missing `StockItem` (agreed deviation, see [Inventory task 10](../inventory-service/10-check-stock-readiness-internal.md)); map it to the business result "not ready, `stock_item_not_found`", as the Catalog spec already describes.
- Keep the readiness call outside a long database transaction (do not hold a DB connection while waiting for another service); note where the transaction boundary is.
- Do not retry on `422`. Retries and circuit breaking for transport errors belong to [task 22](22-inventory-call-resilience.md).
- **Implemented:** `clients/InventoryClient` (`@GetExchange`, `RestClient` + `HttpServiceProxyFactory`), built in `clients/ClientsConfiguration` with `JdkClientHttpRequestFactory` (connect 2 s, read 3 s; `clients.inventory.*`). Two interceptors on this client only: `InternalTokenInterceptor` (`X-Internal-Token` from `AUTH_INTERNAL_TOKEN`, never logged) and `CorrelationIdInterceptor` (`X-Correlation-Id`, same header as order-service; a new `filters/CorrelationIdFilter` puts the incoming ID into the MDC). User headers are never forwarded.
- **Port and adapter:** `services/StockReadinessChecker` returns `StockReadiness` (ready / not ready + reason); `clients/InventoryStockReadinessChecker` implements it. The `RestClient`'s default status handling throws `RestClientResponseException` for `404`/`422`; the adapter catches it and reads `{ reason }` with a tolerant DTO (`ReadinessProblem`, unknown fields ignored). `404` → `stock_item_not_found`; a `422` without a readable reason → `unknown`; unknown codes are passed through. Other `4xx` (e.g. `401`/`403`) are logged as errors and mapped to `503`.
- **Errors:** `ProductActivationRejectedException` → `422` with `reason`; `InventoryUnavailableException` → `503` with a fixed message and `Retry-After: 10`, the cause only logged.
- **Re-activation (decision):** activating an already `ACTIVE` product re-runs the check ("activate" always means "ready now"); a "not ready" answer gives `422` and the product stays `ACTIVE`.
- **Transaction boundary:** `ProductService.changeStatus` is no longer `@Transactional`. The existence check runs in its own short read transaction, the Inventory call runs without any transaction or held connection, and the status is written in a second short transaction (`TransactionOperations`). A status change by another manager between the two is last-write-wins, as before.
- **Config:** `INVENTORY_SERVICE_URL`: `.env`/`.env.example` → `http://inventory-service:8080` (Compose, container port), `k8s/catalog/app-config.yaml` → `http://inventory-service:8084` (Service port), default for the IDE `http://localhost:8084`.
- **Tests:** `InventoryStockReadinessCheckerTest` (WireMock, no Spring context: `204`, `422` with known, unknown and missing reason, `404`, `500`, timeout, connection refused, `403`, headers) and `ProductActivationIntegrationTest` (`PATCH …/status` end to end against WireMock: `200`, `422`, `503`, stored status unchanged on `422`/`503`, deactivation makes no call). `CatalogReadAndStatusIntegrationTest` mocks the checker, since it tests visibility, not the call.
