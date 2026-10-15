---
status: TODO
service: delivery-service
---
# EP-DEL-03: Manual TTN Generation Retry (Tier 3)

## Context
When automatic generation ends in `TTN_FAILED`, the fulfillment queue shows the reason and a "Retry" action. The worker first fixes the cause (usually missing packaging via EP-INV-03, or waits until the carrier is back), then retries. The retry makes one synchronous attempt and tells the worker the result immediately.
*References:* [Delivery Spec — EP-DEL-03](../../design/services-specs/delivery-service-spec.md), [SCN-INV05](../../design/scenarios/inventory-worker-scenarios.md), [SCN-S05](../../design/scenarios/system-scenarios.md), [TTN generation job](11-ttn-generation-job.md)

## Acceptance Criteria
- [ ] `POST /delivery/orders/{orderId}/ttn/retry` (role `INVENTORY_WORKER`).
- [ ] No shipment → `404`; status other than `TTN_FAILED` → `409`.
- [ ] One attempt through the job's `TtnRegistrationService`; packaging is **always** re-fetched.
- [ ] Success → `markGenerated`, `TTNGenerated` appended to the outbox in the same transaction, `failureReason` cleared; `200 OK { ttn }`.
- [ ] Failure (retryable or not) → stays `TTN_FAILED` with the new `failureReason`; `424` with `reason`.
- [ ] The result is applied only if the shipment is still `TTN_FAILED` (optimistic lock). A concurrent retry or cancel makes the loser answer `409`, and a TTN the loser obtained is cancelled at the carrier.
- [ ] Tests: success after packaging was fixed in Inventory (WireMock); `424` for missing packaging; `424` for a carrier failure; `404`; `409` for each non-failed status; two concurrent retries → one `200`, one `409`, one TTN kept.

## Technical Notes / Constraints
- No Tier 1 back-off here: the worker is waiting for the answer.
- The attempt runs outside a transaction, as in the job.
