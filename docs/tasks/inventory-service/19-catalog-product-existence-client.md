---
status: TODO
service: inventory-service
---
# Catalog Product Existence Check (Sync Inter-Service Call)

## Context
Until now, adjust ([task 04](04-adjust-stock-quantity.md)) and set-packaging ([task 09](09-set-packaging-parameters.md)) create a missing `StockItem` on demand **without** checking that the product exists. The spec requires a synchronous Catalog call in exactly that case. This task adds it and brings both endpoints in line with the spec.

**Contract decision (agreed):** the spec names `GET /catalog/products/{productId}`, but Catalog's EP-CAT-05 returns `404` for `NOT_ACTIVE` products, and every new product starts `NOT_ACTIVE`. Restocking and setting packaging before activation is the main use case, so that endpoint would reject it. Inventory therefore calls Catalog's internal **`GET /catalog/products/batch-get?ids={productId}`**, which returns the product in any status and is authenticated with the internal token.
*References:* [Inventory Spec — EP-INV-02, EP-INV-03](../../design/services-specs/inventory-service-spec.md), [Catalog batch-get task](../catalog-service/17-batch-get-products-internal.md), [Declarative HTTP Client (order-service)](../order-service/declarative-http-client.md), [Internal token sender (order-service)](../order-service/internal-token-sender-and-security.md)

## Acceptance Criteria
- [ ] Declarative `@HttpExchange` interface `CatalogClient` with `@GetExchange("/catalog/products/batch-get")` taking `ids`, on `RestClient` + `HttpServiceProxyFactory` (no `RestTemplate`).
- [ ] Response DTO is a tolerant reader (`@JsonIgnoreProperties(ignoreUnknown = true)`): `[ { productId, title, basePrice, status } ]`; only the presence of the requested `productId` matters here. Empty array = product not found.
- [ ] A small interface (for example `ProductCatalog.exists(productId)`) hides the client from the services; the adapter returns `true`/`false` for found/not found.
- [ ] The existing get-or-create method from task 09 (shared with task 04) calls the check **only when no `StockItem` exists**. Existing items never trigger a call.
- [ ] Product not found → `404` ("product not found in Catalog") and no `StockItem` is created. Found → the `StockItem` is created idempotently (two concurrent first-time requests still produce one row) and the operation continues.
- [ ] Catalog unreachable, timeout, `5xx`, or an unexpected `4xx` such as `401`/`403` → `CatalogUnavailableException` → `503`; nothing is changed. An unexpected `4xx` is logged as an error (configuration problem).
- [ ] `InternalTokenInterceptor` adds `X-Internal-Token` from `INTERNAL_TOKEN`; registered only on the `RestClient` that targets Catalog; the token is never logged. `X-User-Id` / `X-User-Role` are not forwarded. A correlation-ID interceptor is added if the order-service client has one.
- [ ] Base URL configurable (`CATALOG_SERVICE_URL`; check the Service name and port in `k8s/catalog/` and `docker-compose.yml`) in `.env.example`, Compose and `k8s/inventory/app-config.yaml`. Connect timeout 2s, read timeout 3s, both configurable.
- [ ] The call is made **outside** the database transaction that locks stock rows (do not hold a lock while waiting for another service), and the `StockItem` creation afterwards is race-safe.
- [ ] The temporary-deviation comments and tests from tasks 04 and 09 are replaced by real behaviour and tests.
- [ ] Documentation: update EP-INV-02 and EP-INV-03 in the Inventory spec to name `batch-get` instead of `GET /catalog/products/{productId}`.
- [ ] Tests with WireMock: found, not found (empty array), `500`, timeout, connection refused, `401`; assertions that the token header is sent and user headers are not; end-to-end controller tests of adjust and packaging for `200`, `404`, `503`; and a test proving no call is made when the `StockItem` already exists.

## Technical Notes / Constraints
- Both services are ready for this: Catalog's `batch-get` is internal (`hasRole("SERVICE")`), which is why Inventory must send the internal token. Keep both ends of the contract (path, parameter name, response shape) in the same PR description.
- The event-driven path ([task 15](15-consume-product-created.md)) normally creates the `StockItem` first; the synchronous check is the fallback when that event was missed, as the spec says.
- Failure policy for transport errors (retries, circuit breaker) is the subject of [task 20](20-catalog-call-resilience.md); this task only maps failures to `503`.

## Starting Point (from tasks 01–14, collected 2026-10-12)
- **Where the check goes:** the private `StockService.getOrCreateForUpdate(productId)` (insert-if-absent, then row lock) is called by `adjustStock` and `setPackaging`, and holds the temporary-deviation comment. **But** it runs inside their `@Transactional` methods, so calling Catalog there would hold a database connection (and, after the insert, a row lock) while waiting for Catalog, against the criterion above. Restructure: check whether the `StockItem` exists and call Catalog **before** the locking transaction starts (for example in a thin non-transactional method or a separate bean), then run the existing transactional code. A `StockItem` created by someone else in between is harmless, because `insertIfAbsent` is race-safe.
- **Tests to replace:** `adjustStock_missingStockItem_isCreatedOnDemandWithoutCatalogCheck_changesInTask19` and the matching packaging tests from [task 09](09-set-packaging-parameters.md).
- **Error mapping:** `ProductNotFoundInCatalogException` → `404` and `CatalogUnavailableException` → `503` go into `GlobalExceptionHandler` with the shared `ProblemDetail` shape. The `404` uses the same `not-found` type as `StockItemNotFoundException`.
- **Packaging returns `204`** (not `200`) since [task 09](09-set-packaging-parameters.md); end-to-end tests should expect that.
- **Documentation criterion:** EP-INV-02/03 in the Inventory spec already name `batch-get` and the `503` (updated at sprint end).
