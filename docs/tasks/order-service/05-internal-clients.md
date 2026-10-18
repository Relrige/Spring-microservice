---
status: DONE
service: order-service
---
# Internal Clients for Catalog, Inventory, Payment and Delivery

## Context
Order orchestrates four services synchronously. Each call must carry `X-Internal-Token` and `X-Correlation-Id`, have explicit timeouts, and turn the other service's answers into Order's own types and exceptions, so the business code never sees HTTP. The existing Catalog client ([Declarative HTTP Client](declarative-http-client.md)) points at a path and fields that do not exist in Catalog and must be aligned. This is the sender side of the older [internal-token-sender-and-security](internal-token-sender-and-security.md) task.
*References:* [Order Spec — dependencies, Configuration](../../design/services-specs/order-service-spec.md), [Catalog EP-CAT-09](../../design/services-specs/catalog-service-spec.md), [Inventory EP-INV-06/07](../../design/services-specs/inventory-service-spec.md), [Payment EP-PAY-01/02](../../design/services-specs/payment-service-spec.md), [Delivery EP-DEL-01/02](../../design/services-specs/delivery-service-spec.md), [Catalog call resilience task](../catalog-service/22-inventory-call-resilience.md)

## Acceptance Criteria
- [x] One `ClientHttpRequestInterceptor` adds `X-Internal-Token` (from `INTERNAL_TOKEN`, never logged) and one forwards `X-Correlation-Id`; both are on every internal `RestClient`. User headers are never forwarded.
- [x] **Catalog:** `GET /catalog/products/batch-get?ids=…` → `List<CatalogProduct(productId, title, basePrice, status, stockStatus)>` (tolerant reader; `stockStatus` needs [Catalog task 24](../catalog-service/24-stock-status-in-batch-get.md)). Unavailability → `DependencyUnavailableException`, **not** an empty list: the draft's fallback would make every product look missing.
- [x] **Inventory:** `reserve(orderId, items)` → reserved for `204` **and** `409`, `InsufficientStock(insufficientItems)` for `422`; `release(orderId)` (`204`).
- [x] **Payment:** `charge(orderId, amount, currency, card)` and `refund(orderId, amount, currency)` mapped to a result type: `Paid`, `AlreadyCharged` (`409` "Order already charged"), `AttemptInProgress` (`409` "Payment attempt in progress", told apart by the problem `title`), `Declined(reason)` (`402`), `Unavailable` (`502` / `504` / timeout); refund `409` → `AlreadyRefunded`. Read timeout `PAYMENT_READ_TIMEOUT` of at least 6 s, and **no retry** by Order: Payment repeats uncertain calls itself.
- [x] **Delivery:** `shipments(orderIds)` (chunks of at most 100) → `{ orderId, status, ttn, failureReason }`; `cancelShipment(orderId)` (`204`).
- [x] URLs (`*_SERVICE_URL`), connect and read timeouts per client in configuration; retries only for idempotent calls (Catalog, Delivery batch get, release, Delivery cancel).
- [x] **Decide** whether the clients keep Resilience4j (the draft's Catalog client: retry, circuit breaker, bulkhead) or use Spring Framework 7's `RetryTemplate` like the other services; record the decision and use one approach for all four clients.
- [x] Tests with WireMock per client: the token and correlation headers are sent; each status maps to the expected result or exception; a slow server hits the timeout; the card number never appears in the logs.

## Technical Notes / Constraints
- Card data passes through the Payment client: mask it in every `toString()`, as Payment does.
- `CatalogClientWireMockTest` already uses a random WireMock port (task 01); its stubs still describe the draft's `/products/batch` contract and must be rewritten with the client.
- A reservation call that timed out may have succeeded; the submit flow ([task 08](08-submit-order.md)) releases it. The client only reports the timeout.

## Decisions
Taken on 2026-10-16 (proposed during implementation; the user asked for the task without settling it):
- **Resilience4j for all four clients**, not Spring's `RetryTemplate`. It was already in this service, written and tested by the team (retry, circuit breaker, bulkhead on the Catalog client), and an orchestrator that calls four services benefits from a **circuit breaker per downstream service**, which `RetryTemplate` does not offer: a Catalog that keeps failing is no longer called for 10 s, so checkout fails fast instead of waiting for timeouts. The other services use `RetryTemplate` because they call one service each.
- **Which calls are retried:** Catalog batch-get, Inventory reserve and release, Delivery batch get and cancel (3 attempts, about 300 ms and 600 ms apart, randomized). Retrying the reservation is safe: a repeat for the same order answers `409`, read as "reserved". **Payment is never retried** (no retry instance): Payment repeats an uncertain gateway call itself, with the same idempotency key, and a repeat of the whole request would only make the customer wait longer.
- **What counts as a failure:** only connection errors and timeouts (`ResourceAccessException`) and `5xx` answers, for both the retry and the circuit breaker. A `4xx` is an answer (or a bug) and is neither retried nor counted.
- **Ports.** The business code sees four interfaces in `ports` (`ProductCatalog`, `StockReservations`, `Payments`, `Shipments`) with Order's own result types; HTTP stays in `clients`. Payment's answers are **results**, not exceptions (`Paid`, `AlreadyCharged`, `AttemptInProgress`, `Declined`, `ChargeUnavailable`; `Refunded`, `AlreadyRefunded`, `RefundFailed`), because the pay endpoint and the cancellation saga act on every one of them. The other three throw `DependencyUnavailableException` when they cannot answer.
- **Payment's refund rejection** (`502` with `reason: refund_rejected`) is read as `RefundFailed("refund_rejected")` inside the adapter, so a business rejection does not open the circuit breaker for every other payment.

## Implementation
Done on 2026-10-16:
- Deleted the draft client (`CatalogClient` on `/products/batch`, `ProductDto`, the two status enums, `ResilientCatalogClient` with its empty-list fallback, `CatalogClientProperties`, `ClientsConfiguration`) and its two tests.
- `clients/HttpClients`: four `@HttpExchange` interfaces (`CatalogHttpClient`, `InventoryHttpClient`, `PaymentHttpClient`, `DeliveryHttpClient`) with their wire records. `ClientsConfiguration` builds one `RestClient` per service with its own connect and read timeout, `InternalTokenInterceptor` and `CorrelationIdInterceptor` (both copied from Inventory).
- `ClientsProperties` (`clients.{catalog,inventory,payment,delivery}.{url,connect-timeout,read-timeout}`, from `*_SERVICE_URL` etc.) **fails at startup** if the Payment read timeout is under 6 s. `InternalTokenProperties` (`auth.internal.token`, at least 32 characters, never printed).
- Adapters `CatalogClientAdapter` (retry, circuit breaker, bulkhead; deduplicates IDs, no call for an empty list, more than 100 IDs is an `IllegalArgumentException`), `InventoryClientAdapter`, `PaymentClientAdapter` (circuit breaker only), `DeliveryClientAdapter` (chunks of 100; an unknown delivery status becomes `null` with a warning). Fallbacks go through `ClientFailures`, which logs a `401`/`403` from another service as a configuration error (`INTERNAL_TOKEN` or route rules) and other failures as warnings, without bodies.
- `PaymentCard.toString()` masks the number (`**** **** **** 4242`); the charge request record therefore logs masked too.
- Configuration: `application.yml` (`clients.*`, `auth.internal.token`, `resilience4j` default configs and the four instances), `.env.example` and the local `.env` (`INTERNAL_TOKEN`, the four URLs with the Compose container port 8080, optional timeouts), `k8s/order/app-config.yaml` (the four URLs with the Kubernetes Service ports 8081/8084/8085/8083, `PAYMENT_READ_TIMEOUT`). The `INTERNAL_TOKEN` Secret for Kubernetes is task 16; until then the pod does not start.
- Tests (WireMock on one random port shared by the four test classes, in a small context with only the clients and the Resilience4j aspects): `CatalogClientAdapterTest` (9: headers and contract, no call for no IDs, more than 100 IDs, `503` retried, persistent `5xx` after 3 attempts, read timeout, `4xx` not retried, circuit opens and Catalog is no longer called, full bulkhead), `InventoryClientAdapterTest` (7: request shape, `409` = reserved, `422` list, retry, down, release path, release failing after retries), `PaymentClientAdapterTest` (10: request shape, `402` with reason, both `409`s by title, `504` not retried, unexpected `400`, the card never logged, refund `200`/`409`, `refund_rejected`, refund `503`/`422`), `DeliveryClientAdapterTest` (4: statuses including none and unknown, chunks of 100, down, cancel retried), `ClientsPropertiesTest` (2).

## Not done here
- A Payment read **timeout** is not tested against a slow WireMock (it would take 6 s per test); the timeout path is the same code as Catalog's, which is tested, and Payment's `504` is tested.
