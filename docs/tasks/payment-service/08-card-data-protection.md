---
status: TODO
service: payment-service
---
# Card Data Protection

## Context
EP-PAY-01 receives a full card number and CVV. In the real world this puts the system in the scope of PCI DSS, whose basic rules are: never store the CVV, never store or log the full card number, keep card data in memory only as long as needed. The project does not need certification, but it should show that the team knows where card data flows and that it does not leak into logs, error bodies, the database or the traces of other services.
*References:* [Payment Spec — EP-PAY-01](../../design/services-specs/payment-service-spec.md), [Charge task](06-charge-card.md), [Error handling task](03-error-handling-and-validation.md)

## Acceptance Criteria
- [ ] `CardDetails` (request DTO) and the command passed to the gateway override `toString()` so that only the masked number is shown (`**** **** **** 4242`), never the CVV or expiry. A Java `record`'s default `toString()` prints every field, so it must be overridden.
- [ ] No card field is persisted: `payment_transactions` has no card column, and no other table is added for it.
- [ ] Validation errors for card fields do not echo the rejected value ([task 03](03-error-handling-and-validation.md)).
- [ ] Spring's request logging (if enabled, for example `CommonsRequestLoggingFilter` or `logging.level.org.springframework.web=DEBUG`) is not configured to log request bodies for this service.
- [ ] A test captures the log output (for example with `OutputCaptureExtension`) of a charge with each mock card and of a failed validation, and asserts that neither the full card number nor the CVV appears.
- [ ] A short section *Card data* in the Payment spec lists where card data exists (request body in memory → gateway adapter) and where it must never appear.

## Technical Notes / Constraints
- Order Service also handles `cardDetails` (it forwards them from the customer); the same rules apply there. Add a line to the Order Service payment client task rather than fixing it here.
- The API Gateway's access logs record paths and status codes only; check that this is still true once `/orders/{id}/pay` is routed.
- Use a `char[]`/`byte[]` only if the team wants to discuss memory hygiene; for this project a `String` with a masking `toString()` is enough.
