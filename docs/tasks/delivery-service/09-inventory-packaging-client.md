---
status: TODO
service: delivery-service
---
# Inventory Packaging Client

## Context
Before calling the carrier, Delivery fetches each product's packaging from Inventory's internal endpoint EP-INV-05. The client must tell the caller whether a failure is **retryable** (Inventory down, timeout, `5xx`: the TTN job tries again later) or **not** (`404` / `422`: the data is missing, and only a worker can fix it). The job and the manual retry act on that difference.
*References:* [Delivery Spec — Automatic TTN generation, Configuration](../../design/services-specs/delivery-service-spec.md), [Inventory EP-INV-05](../../design/services-specs/inventory-service-spec.md), [Order declarative HTTP client task](../order-service/declarative-http-client.md), [Catalog's Inventory client](../catalog-service/21-inventory-readiness-client.md)

## Acceptance Criteria
- [ ] A declarative HTTP client (`@HttpExchange`) for `GET /inventory/stock/{productId}/packaging`, on a `RestClient` with the internal-token and correlation-id interceptors (task 04).
- [ ] `INVENTORY_SERVICE_URL` (defaults in the spec), connect timeout 2 s and read timeout 3 s (configurable). **No retry inside the call**: the TTN job's attempts are the retries.
- [ ] A `PackagingProvider` (or similar) that takes the shipment items, calls Inventory once per distinct `productId`, and returns `ItemPackaging` values for the parcel calculation (task 08).
- [ ] Result mapping:
  - `200` → packaging
  - `404` → non-retryable, `reason = stock_item_not_found:{productId}`
  - `422` with `reason = packaging_data_missing` → non-retryable, `reason = packaging_data_missing:{productId}`
  - connection error, timeout, `5xx` → retryable, `reason = inventory_unavailable`
  - other `4xx` (e.g. `401`/`403`, a configuration problem) → retryable, logged at `ERROR`
- [ ] Tests with WireMock: the token header is sent and user headers are not; each mapping above; a timeout is retryable.

## Technical Notes / Constraints
- Read the response as a tolerant reader (ignore unknown fields).
- The token is never logged.
- Add `INVENTORY_SERVICE_URL` to `.env.example`, `docker-compose.yml` and `k8s/delivery/app-config.yaml` (Compose: container port 8080; Kubernetes: service port 8084).
