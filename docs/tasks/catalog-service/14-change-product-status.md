---
status: DONE
service: catalog-service
---
# EP-CAT-03: Change Product Status (Activate / Deactivate) — Without Readiness Check

## Context
A Catalog Manager publishes (`ACTIVE`) or hides (`NOT_ACTIVE`) a product. Deactivation is the only removal mechanism because hard deletes are forbidden.

According to the spec, activation must first call Inventory Service synchronously (`GET /inventory/stock/{productId}/readiness`). **This task deliberately makes no inter-service call and has no placeholder for one.** Activation here simply sets the product `ACTIVE`. The readiness check is added, together with its failure handling, in the last tasks ([task 21](21-inventory-readiness-client.md), [task 22](22-inventory-call-resilience.md)). Until then this endpoint intentionally deviates from the spec: a product can be activated without stock or packaging data. Record this in the PR so it is not forgotten.
*References:* [Catalog Spec — EP-CAT-03](../../design/services-specs/catalog-service-spec.md), [Catalog Manager Scenarios (SCN-CM02, SCN-CM03)](../../design/scenarios/catalog-manager-scenarios.md)

## Acceptance Criteria
- [x] `PATCH /catalog/products/{id}/status` accepts `{ status }` and returns `200 OK`.
- [x] Product `id` not found → `404`. `status` missing, not one of `ACTIVE` / `NOT_ACTIVE`, or an unreadable body → `400` (never `500`).
- [x] Setting `NOT_ACTIVE` and setting `ACTIVE` change only `Product.status`. Setting the status the product already has is an idempotent `200`.
- [x] No HTTP client, no readiness interface, and no placeholder bean for Inventory is introduced.
- [x] The endpoint publishes no event and does not touch `stockStatus`.
- [x] Route rule `PATCH /catalog/products/{id}/status` → `hasRole("CATALOG_MANAGER")`; `401`/`403` tests (this task comes after [task 12](12-trusted-header-security.md)).
- [x] The temporary spec deviation is marked in the code with a short comment pointing at task 21, and a test named so that it is obviously the one to change when the readiness check arrives.
- [x] Tests: service unit tests (activate, deactivate, idempotent repeat); controller tests for each status code above.

## Technical Notes / Constraints
- Write the service method so the activation branch is easy to extend later (a clearly separate `activate` path), but do not build abstractions for a call that does not exist yet.
- Activated products become visible through [task 15](15-get-product-details.md) and [task 16](16-browse-and-filter-products.md); this endpoint is what makes those testable end to end.
- Concurrency: a deactivation racing with an activation is last-write-wins. That is acceptable for this project; state it.
- **For the PR — temporary spec deviation:** activation does not call Inventory yet, so a product can be activated without stock or packaging data. The code says so in `ProductService.activate` (pointing at task 21); the test to change then is `ProductServiceTest.changeStatus_activate_setsActiveWithoutReadinessCheck_untilTask21`.
- **Implemented:** `200 OK` with no body (as in the spec). Checks follow the spec: `404`, then body validation (`400`, service-side as in EP-CAT-02); an unknown enum value or an unreadable body is rejected while the JSON is read (`400`). `@DynamicUpdate` means only `status` is written; an unchanged status issues no `UPDATE`. Concurrent activation/deactivation is last-write-wins.
- **Resolved in [task 21](21-inventory-readiness-client.md):** activation now calls Inventory's readiness check; the temporary comment and the `..._untilTask21` test were replaced by real behaviour and tests.
