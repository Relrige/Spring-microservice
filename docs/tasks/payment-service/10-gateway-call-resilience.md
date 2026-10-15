---
status: TODO
service: payment-service
---
# Gateway Call Resilience and Uncertain Outcomes

## Context
Catalog and Inventory protect their calls to each other with timeouts and retries ([Catalog task 22](../catalog-service/22-inventory-call-resilience.md), [Inventory task 20](../inventory-service/20-catalog-call-resilience.md)). For payments the same tools are dangerous: a **timeout does not mean the charge failed**. The provider may have taken the money and only the answer was lost. Retrying blindly can charge twice; recording `FAILED` (as the spec says) can make the customer pay twice when they retry with the same card. This task implements the team's decision on how Payment Service handles that uncertainty.
*References:* [Payment Spec — EP-PAY-01 step 5, EP-PAY-02](../../design/services-specs/payment-service-spec.md), [Gateway ACL](05-payment-gateway-acl-and-mock.md), [Charge task](06-charge-card.md)

## Acceptance Criteria
- [ ] Gateway calls have an explicit time limit (configurable, for example `payment.gateway.timeout`), shorter than Order Service's read timeout for `/payments/charge`, so Payment can record its own outcome before Order gives up.
- [ ] On an **uncertain** outcome (timeout or gateway unavailable), `charge` and `refund` are retried with the **same idempotency key** (configurable, for example 1 retry with a short backoff, using Spring's `RetryTemplate` as in the other services). A definitive decline is never retried.
- [ ] If the retries are used up, the row is saved as `FAILED` with `gateway_timeout` / `gateway_unavailable` and the answer is `504` / `502`. Because an uncertain row does not end the attempt ([task 06](06-charge-card.md)), Order's later retry reuses the key and can still settle it.
- [ ] Test with the mock's `5000` card ("charged, answer lost"): the first call times out, the retry with the same key gets the stored approval → `200`, one `SUCCESS` row, and the mock has charged **once**.
- [ ] Test: retries exhausted → `504`; a later request for the same order and card reuses the key and returns the stored approval → `200`; the mock still charged once.
- [ ] The total time of all attempts stays under Order Service's read timeout for `/payments/charge`; the numbers are written in `application.yml` comments.
- [ ] The Payment spec (EP-PAY-01 step 5, EP-PAY-02) and `entities.md` are updated to match: idempotency key, retry with the same key, uncertain vs definitive failure reasons.

## Decision
Agreed on 2026-10-15: **use the idempotency key from [task 06](06-charge-card.md) so that a charge can be retried safely.** A timeout no longer forces the choice between "maybe charge twice" (retrying blindly) and "maybe tell the customer it failed when it didn't" (the spec's `FAILED` + `504`). A retry with the same key either gets the stored result or does the charge for the first time.

Rejected: keeping the spec as is (double-charge risk), and an `UNKNOWN` status with a reconciliation job (closer to real practice, but more moving parts; the key makes it unnecessary while the provider keeps its keys).

Remaining limits, to be written into the spec:
- Real providers keep keys for a limited time (Stripe: 24 hours). An order still uncertain after that would need reconciliation by hand. Order Service's payment window (reservation TTL) is much shorter, so this is accepted.
- The mock's key store is in memory ([task 05](05-payment-gateway-acl-and-mock.md)): a Payment Service restart between a lost answer and its retry loses the protection. Accepted for the mock.
- A circuit breaker is not added: there is one caller and low volume, and the retry is already bounded.
