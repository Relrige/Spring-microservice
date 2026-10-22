---
status: TODO
service: order-service
---
# EP-ORD-02: Idempotency-Key on Submit Order

## Context
A repeated "Submit order" (double click, a retry after a lost answer, two tabs) creates a second order with its own reservation today; the unpaid one holds stock until it expires. `POST /orders` gets an optional `Idempotency-Key` header so a repeat with the same key and body returns the first order and reserves nothing (frontend backlog [BL-07](../../../frontend/docs/backlog/BL-07-order-submit-idempotency.md), agreed 2026-10-22). The cleanup of old and stranded keys is [task 20](20-purge-idempotency-keys-job.md), which builds on this one; this task works without it.
*References:* [Order Spec — EP-ORD-02 (*Idempotency-Key*), Responses and errors, Decisions D4–D10](../../design/services-specs/order-service-spec.md), [API Contracts — `POST /orders`](../../design/services-requirements/api-contracts.md), [OpenAPI Conventions — D5, D6](../../design/services-requirements/openapi-conventions.md), [task 08 (submit order)](08-submit-order.md), [task 17 (reservation attempts)](17-review-fixes.md)

## Acceptance Criteria
- [ ] **Migration `V4__create_idempotency_keys_table.sql`:** `idempotency_keys (customer_id UUID, idempotency_key UUID, request_hash VARCHAR(64), status VARCHAR(16) CHECK IN ('IN_PROGRESS','COMPLETED'), order_id UUID NULL, created_at TIMESTAMPTZ)`, primary key `(customer_id, idempotency_key)`, a check that `order_id` is set exactly when `status = 'COMPLETED'`, an index on `(status, created_at)` for task 20. A header comment in the style of `V3` explains what a row means.
- [ ] **Header:** `POST /orders` reads the optional `Idempotency-Key`. A non-UUID value → `400` type `validation` (the framework's type-mismatch answer, shaped by `handleExceptionInternal` like a non-UUID path variable; no `errors` map needed). Without the header nothing changes: every existing test passes unchanged.
- [ ] **Order of checks:** role check, body validation (`400`), the `X-User-Email` check (`401`), then the claim. A malformed request leaves no row.
- [ ] **Claim:** the hash of D9 is computed and the row inserted as `IN_PROGRESS` in its own transaction with `INSERT … ON CONFLICT (customer_id, idempotency_key) DO NOTHING`, before the Catalog call.
- [ ] **Existing row:** nothing is called and nothing is reserved. Different hash → `422` type `idempotency-key-mismatch` (whatever the status). `COMPLETED` → `201` with the body built from the order's current state (`PlacedOrderResponse.of(order)`), `Location: /orders/{orderId}` and `Idempotent-Replayed: true`. `IN_PROGRESS` → `409` type `request-in-progress` with `Retry-After: 1`.
- [ ] **Complete:** the row becomes `COMPLETED` with `order_id` inside the transaction that stores the order, writes `OrderPlaced` and deletes the reservation attempt. If the row is missing, the order is still stored and a warning is logged.
- [ ] **Release on failure:** every non-`201` outcome after the claim (`IneligibleItems`, `OrderTotalTooLarge`, `InsufficientStock`, `DependencyUnavailable`, a failed save) deletes the row in its own transaction, best-effort with a warning log; the original exception is still thrown. The reservation-attempt handling of steps 4–6 is unchanged.
- [ ] **Unit tests** (`RequestHashTest` or similar): the same request with items in another order or other JSON whitespace gives the same hash; a changed quantity, product, or any `deliveryDetails` field gives another hash.
- [ ] **Unit tests** (`OrderPlacementServiceTest`): owner path claims then completes; a replay of a `COMPLETED` key makes no Catalog or Inventory call; mismatch and in-progress answers; each failure type deletes the claim; no header → no row touched.
- [ ] **Controller tests** (`OrderControllerTest`): non-UUID key → `400`; replay → `201` with `Location` and `Idempotent-Replayed: true`; `422 idempotency-key-mismatch`; `409 request-in-progress` with `Retry-After: 1`; the problem `type` URIs.
- [ ] **Integration tests** (`CheckoutIntegrationTest`, Testcontainers PostgreSQL + WireMock):
  - two sequential requests with the same key and body → one order, one `POST /inventory/reservations` and one Catalog call, both answers carry the same `orderId`, the second with `Idempotent-Replayed: true`;
  - **two concurrent requests** with the same key (WireMock delays the Inventory answer) → one order and one reservation call; the other answer is `409 request-in-progress` or the replayed `201`;
  - same key, another body → `422`, one order only;
  - Inventory answers `503` first, then `204`: the retry with the same key creates the order (the claim was released);
  - the same key from two customers → two orders;
  - a replay after the order was paid shows `status: PAID`.
- [ ] **OpenAPI:** the header parameter `Idempotency-Key` (`in: header`, `format: uuid`, not required) on `POST /orders`; the `201` documents the response header `Idempotent-Replayed`; `409` (`Problem`) added; the `422` `oneOf` gets `Problem` for `idempotency-key-mismatch`. `order-service/openapi/public.json` regenerated with `-Dopenapi.update=true` and committed.
- [ ] **Through the gateway (manual, Compose):** two `POST http://localhost:8080/orders` calls with a customer token and the same `Idempotency-Key` return the same `orderId`; the second has `Idempotent-Replayed: true`. Both requests are added to the Order folder of `VoltStore_API_Tests.json`.
- [ ] All Order tests pass (`mvnw.cmd verify` in `order-service/`).

## Technical Notes / Constraints
- **Settled decisions (D4–D10), do not change:** a separate table with an `IN_PROGRESS` claim (not a column on `orders`, not an `orderId` derived from the key); optional header, UUID only, scoped per customer; only success is remembered; the replay is built from the current order, not a stored body; `422` mismatch before status, `409` in progress with `Retry-After: 1`; the hash of D9; cleanup only in task 20.
- **Hash (D9):** SHA-256 hex of the parsed `PlaceOrderRequest` serialised by the application's Jackson `JsonMapper` with `items` sorted by `productId`. Record components serialise in declaration order, so the JSON is stable; do not hash the raw request bytes.
- **Claim insert:** a native `@Modifying` query returning the row count (`1` = owner, `0` = existing row), then a plain `findById` for the existing row. A `save()` of a new entity followed by catching `DataIntegrityViolationException` is not enough: the violation surfaces at flush and poisons the transaction.
- **No transaction during remote calls** stays true: claim, release and the replay lookup are each their own short transaction; only completion joins the step-5 transaction.
- Problem types go through `GlobalExceptionHandler.problem(...)` like the others (new exceptions, e.g. `IdempotencyKeyMismatchException`, `RequestInProgressException`); `Retry-After` is set the way the `503` handler sets it.
- The header is read in `OrderController.place` with `@RequestHeader(name = "Idempotency-Key", required = false) UUID`; Spring's conversion failure already reaches `GlobalExceptionHandler.handleExceptionInternal`, which gives it the `validation` type and the shared fields. Verify with the controller test rather than adding a handler.
- Generated frontend types change with `public.json`; the frontend adapts on its side (its `check:api`), nothing in `frontend/` is edited here.
- The gateway needs no change: it strips only `Authorization`, `X-User-*` and `X-Internal-Token`.
