---
status: DONE
service: payment-service
---
# Gateway Call Resilience and Uncertain Outcomes

## Context
Catalog and Inventory protect their calls to each other with timeouts and retries ([Catalog task 22](../catalog-service/22-inventory-call-resilience.md), [Inventory task 20](../inventory-service/20-catalog-call-resilience.md)). For payments the same tools are dangerous: a **timeout does not mean the charge failed**. The provider may have taken the money and only the answer was lost. Retrying blindly can charge twice; recording `FAILED` (as the spec says) can make the customer pay twice when they retry with the same card. This task implements the team's decision on how Payment Service handles that uncertainty.
*References:* [Payment Spec — EP-PAY-01 step 5, EP-PAY-02](../../design/services-specs/payment-service-spec.md), [Gateway ACL](05-payment-gateway-acl-and-mock.md), [Charge task](06-charge-card.md)

## Acceptance Criteria
- [x] Gateway calls have an explicit time limit (configurable, for example `payment.gateway.timeout`), shorter than Order Service's read timeout for `/payments/charge`, so Payment can record its own outcome before Order gives up.
- [x] On an **uncertain** outcome (timeout or gateway unavailable), `charge` and `refund` are retried with the **same idempotency key** (configurable, for example 1 retry with a short backoff, using Spring's `RetryTemplate` as in the other services). A definitive decline is never retried.
- [x] If the retries are used up, the row is saved as `FAILED` with `gateway_timeout` / `gateway_unavailable` and the answer is `504` / `502`. Because an uncertain row does not end the attempt ([task 06](06-charge-card.md)), Order's later retry reuses the key and can still settle it.
- [x] Test with the mock's `5000` card ("charged, answer lost"): the first call times out, the retry with the same key gets the stored approval → `200`, one `SUCCESS` row, and the mock has charged **once**.
- [x] Test: retries exhausted → `504`; a later request for the same order and card reuses the key and returns the stored approval → `200`; the mock still charged once.
- [x] The total time of all attempts stays under Order Service's read timeout for `/payments/charge`; the numbers are written in `application.yml` comments.
- [x] The Payment spec (EP-PAY-01 step 5, EP-PAY-02) and `entities.md` are updated to match: idempotency key, retry with the same key, uncertain vs definitive failure reasons.

## Decision
Agreed on 2026-10-15: **use the idempotency key from [task 06](06-charge-card.md) so that a charge can be retried safely.** A timeout no longer forces the choice between "maybe charge twice" (retrying blindly) and "maybe tell the customer it failed when it didn't" (the spec's `FAILED` + `504`). A retry with the same key either gets the stored result or does the charge for the first time.

Rejected: keeping the spec as is (double-charge risk), and an `UNKNOWN` status with a reconciliation job (closer to real practice, but more moving parts; the key makes it unnecessary while the provider keeps its keys).

Remaining limits, to be written into the spec:
- Real providers keep keys for a limited time (Stripe: 24 hours). An order still uncertain after that would need reconciliation by hand. Order Service's payment window (reservation TTL) is much shorter, so this is accepted.
- The mock's key store is in memory ([task 05](05-payment-gateway-acl-and-mock.md)): a Payment Service restart between a lost answer and its retry loses the protection. Accepted for the mock.
- A circuit breaker is not added: there is one caller and low volume, and the retry is already bounded.

## Implementation
Done on 2026-10-15:
- `gateway/GatewayProperties` (`payment.gateway.*`): `provider` (`PAYMENT_GATEWAY_PROVIDER`, `mock`), `timeout` (`PAYMENT_GATEWAY_TIMEOUT`, `2s`), `max-retries` (`PAYMENT_GATEWAY_MAX_RETRIES`, `1`), `retry-delay` (`PAYMENT_GATEWAY_RETRY_DELAY`, `300ms`, plus up to half of it as jitter). It replaces task 05's `payment.gateway.mock.timeout-delay`: the mock now reports its "lost answer" after the gateway time limit, as a real adapter's read timeout would.
- `PaymentService.callGateway` wraps every charge and refund call in Spring Framework 7's `RetryTemplate`, retrying **only** `PaymentGatewayTimeoutException` / `PaymentGatewayUnavailableException` and always with the same key; approvals, declines and key mismatches are never repeated. Each uncertain attempt is logged at `WARN`; only the final outcome is stored (one `FAILED` row with `GATEWAY_TIMEOUT` / `GATEWAY_UNAVAILABLE` if every attempt stays unanswered).
- Time budget, written in `application.yml`, `.env.example` and `k8s/payment/app-config.yaml`: 2 × 2 s + 0.3–0.45 s ≈ 4.5 s worst case, so Order Service's read timeout for `/payments/**` must be 6 s or more.
- Tests: `PaymentServiceTest` (timeout then approval → one repeat with the same key, only `SUCCESS` stored; same for unavailable; timeout on every attempt → one `FAILED` row and `504`; decline and refund rejection are not repeated; refund timeout then approval), `ChargeIntegrationTest#lostAnswer_isSettledByTheInternalRepeat...` (card `5000` → `200`, one charge at the mock, only the `SUCCESS` row), `ChargeRetriesExhaustedIntegrationTest` (with `max-retries=0`: `504`, then a later repeat → `200` with one charge; another card meanwhile → `409`).
- Spec EP-PAY-01 step 6, EP-PAY-02 step 8 and the *Known limits* paragraph updated. `entities.md` already had the uncertain/definitive split (task 02).
- Seen live in Compose ([task 09](09-containerization-and-deployment.md)): card `5000` answered `200` after 2.6 s; the log shows the approval, the `outcome unknown` warning and the `(replayed)` approval with the same key.
