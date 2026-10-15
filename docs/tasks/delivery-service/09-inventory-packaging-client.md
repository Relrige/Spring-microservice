---
status: DONE
service: delivery-service
---
# Inventory Packaging Client

## Context
Before calling the carrier, Delivery fetches each product's packaging from Inventory's internal endpoint EP-INV-05. The client must tell the caller whether a failure is **retryable** (Inventory down, timeout, `5xx`: the TTN job tries again later) or **not** (`404` / `422`: the data is missing, and only a worker can fix it). The job and the manual retry act on that difference.
*References:* [Delivery Spec — Automatic TTN generation, Configuration](../../design/services-specs/delivery-service-spec.md), [Inventory EP-INV-05](../../design/services-specs/inventory-service-spec.md), [Order declarative HTTP client task](../order-service/declarative-http-client.md), [Catalog's Inventory client](../catalog-service/21-inventory-readiness-client.md)

## Acceptance Criteria
- [x] A declarative HTTP client (`@HttpExchange`) for `GET /inventory/stock/{productId}/packaging`, on a `RestClient` with the internal-token and correlation-id interceptors (task 04).
- [x] `INVENTORY_SERVICE_URL` (defaults in the spec), connect timeout 2 s and read timeout 3 s (configurable). **No retry inside the call**: the TTN job's attempts are the retries.
- [x] A `PackagingProvider` (or similar) that takes the shipment items, calls Inventory once per distinct `productId`, and returns `ItemPackaging` values for the parcel calculation (task 08).
- [x] Result mapping:
  - `200` → packaging
  - `404` → non-retryable, `reason = stock_item_not_found:{productId}`
  - `422` with `reason = packaging_data_missing` → non-retryable, `reason = packaging_data_missing:{productId}`
  - connection error, timeout, `5xx` → retryable, `reason = inventory_unavailable`
  - other `4xx` (e.g. `401`/`403`, a configuration problem) → retryable, logged at `ERROR`
- [x] Tests with WireMock: the token header is sent and user headers are not; each mapping above; a timeout is retryable.

## Technical Notes / Constraints
- Read the response as a tolerant reader (ignore unknown fields).
- The token is never logged.
- Add `INVENTORY_SERVICE_URL` to `.env.example`, `docker-compose.yml` and `k8s/delivery/app-config.yaml` (Compose: container port 8080; Kubernetes: service port 8084).

## Implementation Notes (2026-10-15)
- **Client:** `clients/InventoryClient` (`@GetExchange("/inventory/stock/{productId}/packaging")`) returning `InventoryPackaging` (`@JsonIgnoreProperties(ignoreUnknown = true)`); built in `clients/ClientsConfiguration` exactly like Inventory's Catalog client: JDK `HttpClient` with connect timeout, read timeout on the request factory, its own `RestClient` with `InternalTokenInterceptor` and `CorrelationIdInterceptor` (task 04). No retry.
- **`clients/InventoryPackagingProvider implements services.PackagingProvider`:** one call per distinct `productId`, result in item order as `ItemPackaging`. Failures become `exceptions.PackagingUnavailableException(reason, retryable)`: `404` → `stock_item_not_found:{productId}` (not retryable); `422` → `packaging_data_missing:{productId}` (not retryable); `5xx`, connection error, timeout, empty body → `inventory_unavailable` (retryable, `WARN`); other `4xx` → `inventory_unavailable` (retryable, `ERROR` "check the internal token and Inventory's route rules").
- **Simplification:** every `422` is mapped to `packaging_data_missing`, without parsing Inventory's problem body; `422` is used by EP-INV-05 for nothing else.
- **Configuration:** `clients.inventory.url` / `connect-timeout` / `read-timeout` from `INVENTORY_SERVICE_URL` (default `http://localhost:8084`), `INVENTORY_CONNECT_TIMEOUT` (`2s`), `INVENTORY_READ_TIMEOUT` (`3s`). Added to `.env.example` (`http://inventory-service:8080`) and `k8s/delivery/app-config.yaml` (`http://inventory-service:8084`). `docker-compose.yml` passes variables only through `env_file`, so it needs no change for the URL; the `depends_on` alignment is task 16.
- **Verified (2026-10-15):** `InventoryPackagingProviderTest` (WireMock, no Spring context): token and correlation id sent, user headers absent, one call per product, `404`, `422`, `503`, `403`, connection reset, read timeout, Inventory down; the token never appears in the exception message.
