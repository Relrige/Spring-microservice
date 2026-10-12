---
status: TODO
service: catalog-service
---
# Inventory Readiness Check on Activation (Sync Inter-Service Call)

## Context
Until now, [task 14](14-change-product-status.md) activates a product without any check, deliberately deviating from the spec. This task introduces the first synchronous inter-service call in Catalog Service and brings activation in line with EP-CAT-03: before setting `ACTIVE`, call Inventory Service `GET /inventory/stock/{productId}/readiness`. The call carries no user and is authenticated with the shared internal token. Inventory's answer is either ready, or not ready with a reason code.
*References:* [Catalog Spec — EP-CAT-03](../../design/services-specs/catalog-service-spec.md), [Inventory Spec — EP-INV-04](../../design/services-specs/inventory-service-spec.md), [Declarative HTTP Client (order-service)](../order-service/declarative-http-client.md), [Internal token sender (order-service)](../order-service/internal-token-sender-and-security.md)

## Acceptance Criteria
- [ ] Declarative `@HttpExchange` interface `InventoryClient` with `@GetExchange("/inventory/stock/{productId}/readiness")`, built on `RestClient` + `HttpServiceProxyFactory` (no `RestTemplate`).
- [ ] Inventory answers `204 No Content` (ready), `404` (no `StockItem`) or `422 { reason }` (e.g. `packaging_data_missing`). The `422` error DTO uses `@JsonIgnoreProperties(ignoreUnknown = true)` (tolerant reader).
- [ ] The activation path in the product service asks a small interface (suggested name `StockReadinessChecker`) whether the product is ready; an adapter implements it on top of the client. Result "ready" → set `ACTIVE`, `200`. Result "not ready, reason" → `422` with the reason code (`stock_item_not_found` for Inventory's `404`, `packaging_data_missing`, …) via `ProductActivationRejectedException`; status unchanged. Unknown reason codes are passed through.
- [ ] Inventory unreachable: connection error, timeout, `5xx`, or an unexpected `4xx` such as `401`/`403` → `InventoryUnavailableException` → `503`; status unchanged. An unexpected `4xx` is logged as an error because it signals a configuration problem, not a business result.
- [ ] Deactivation still makes no external call. Activating an already `ACTIVE` product: decide and document whether the check is re-run (suggested: yes) and test it.
- [ ] `InternalTokenInterceptor` (`ClientHttpRequestInterceptor`) adds `X-Internal-Token` from `INTERNAL_TOKEN`; registered only on the `RestClient` that targets Inventory. The token is never logged. `X-User-Id` / `X-User-Role` are **not** forwarded.
- [ ] A correlation-ID interceptor is added if the order-service client already has one, with the same header name.
- [ ] Base URL is configurable (`INVENTORY_SERVICE_URL`; check the Service name and port in `k8s/inventory/` and `docker-compose.yml`) and present in `.env.example`, Compose, and `k8s/catalog/app-config.yaml`.
- [ ] Connect timeout 2s and read timeout 3s via `JdkClientHttpRequestFactory` (same as the order-service client), both configurable.
- [ ] The temporary-deviation comment and test from task 14 are replaced by real behaviour and tests.
- [ ] Tests with WireMock: ready (`204`), `422` with `packaging_data_missing`, `404`, `500`, timeout (delay above the read timeout), connection refused, and assertions that `X-Internal-Token` is sent and `X-User-*` is not.
- [ ] End-to-end controller test: `PATCH …/status` with `ACTIVE` against WireMock returns `200`, `422` and `503` accordingly, and the stored status is unchanged on `422`/`503`.

## Technical Notes / Constraints
- `204`, `404` and `422` from Inventory are all valid business answers. Make sure the `RestClient` does not throw for `404`/`422` before the adapter can read the body (look at `onStatus` handling or catch `RestClientResponseException`).
- Model the checker's result as a small value type (ready / not ready with reason); use exceptions only for the exceptional "Inventory is unavailable" case.
- Inventory may not be implemented when this is built; WireMock stands in for it. Agree on the reason codes with the Inventory spec.
- Inventory returns `404` for a missing `StockItem` (agreed deviation, see [Inventory task 10](../inventory-service/10-check-stock-readiness-internal.md)); map it to the business result "not ready, `stock_item_not_found`", as the Catalog spec already describes.
- Keep the readiness call outside a long database transaction (do not hold a DB connection while waiting for another service); note where the transaction boundary is.
- Do not retry on `422`. Retries and circuit breaking for transport errors belong to [task 22](22-inventory-call-resilience.md).
