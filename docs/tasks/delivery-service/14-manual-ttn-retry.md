---
status: DONE
service: delivery-service
---
# EP-DEL-03: Manual TTN Generation Retry (Tier 3)

## Context
When automatic generation ends in `TTN_FAILED`, the fulfillment queue shows the reason and a "Retry" action. The worker first fixes the cause (usually missing packaging via EP-INV-03, or waits until the carrier is back), then retries. The retry makes one synchronous attempt and tells the worker the result immediately.
*References:* [Delivery Spec — EP-DEL-03](../../design/services-specs/delivery-service-spec.md), [SCN-INV05](../../design/scenarios/inventory-worker-scenarios.md), [SCN-S05](../../design/scenarios/system-scenarios.md), [TTN generation job](11-ttn-generation-job.md)

## Acceptance Criteria
- [x] `POST /delivery/orders/{orderId}/ttn/retry` (role `INVENTORY_WORKER`).
- [x] No shipment → `404`; status other than `TTN_FAILED` → `409`.
- [x] One attempt through the job's `TtnRegistrationService`; packaging is **always** re-fetched.
- [x] Success → `markGenerated`, `TTNGenerated` appended to the outbox in the same transaction, `failureReason` cleared; `200 OK { ttn }`.
- [x] Failure (retryable or not) → stays `TTN_FAILED` with the new `failureReason`; `424` with `reason`.
- [x] The result is applied only if the shipment is still `TTN_FAILED` (optimistic lock). A concurrent retry or cancel makes the loser answer `409`, and a TTN the loser obtained is cancelled at the carrier.
- [x] Tests: success after packaging was fixed in Inventory (WireMock); `424` for missing packaging; `424` for a carrier failure; `404`; `409` for each non-failed status; two concurrent retries → one `200`, one `409`, one TTN kept.

## Technical Notes / Constraints
- No Tier 1 back-off here: the worker is waiting for the answer.
- The attempt runs outside a transaction, as in the job.

## Implementation Notes (2026-10-15)
- **Endpoint:** `POST /delivery/orders/{orderId}/ttn/retry` in `ShipmentController` (role `INVENTORY_WORKER`, task 04), answering `200 { ttn }` (`dto/RetryTtnResponse`).
- **`services/ManualTtnRetryService.retry(orderId)`:**
  1. Read transaction: no shipment → `ShipmentNotFoundException` (`404`); status other than `TTN_FAILED` → `InvalidShipmentStateException` (`409`, "Retry is only allowed for shipments in TTN_FAILED status"); otherwise a `ShipmentSnapshot` with the current version.
  2. One attempt through task 11's `TtnRegistrationService` outside any transaction. The packaging is always fetched from Inventory again (nothing is cached).
  3. Apply transaction, only if the shipment is still `TTN_FAILED` with the snapshot's version: success → `markGenerated` (clears `failureReason`) + `TTNGenerated` via the outbox; failure (retryable or not) → `markFailed(reason)`, which keeps `TTN_FAILED` and replaces the reason. Then `200` or `TtnGenerationFailedException` (`424`, `reason`).
  4. Version changed or optimistic-lock failure at commit (a concurrent retry or cancel won) → `409`; a TTN the loser obtained is cancelled at the carrier (best-effort, `TtnRegistrationService.cancelOrphanedTtn`).
- No back-off here (the worker waits for the answer); `attempts` is still increased by a failed manual attempt, so it counts all failed attempts.
- **Verified (2026-10-15):** `ManualTtnRetryIntegrationTest` (Testcontainers, WireMock as Inventory, a spied mock carrier, TTN job off): success after the packaging was fixed (TTN, parcel, one `TTNGenerated`, packaging fetched once more); `424` with `packaging_data_missing:{productId}` (carrier not called, no event); `424` with `carrier_error: …`; `404`; `409` for each of the five other statuses; two concurrent retries → one `200`, one `409`, the winner's TTN stored and the loser's TTN cancelled at the carrier, one event; `401` anonymous, `403` for `ADMIN` and for an internal service. `RouteSecurityRulesTest` covers the new route.
