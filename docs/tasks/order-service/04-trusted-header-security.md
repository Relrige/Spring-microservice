---
status: DONE
service: order-service
---
# Trusted-Header Security: Orders for Registered Customers Only

## Context
Only registered customers can order (decision 2026-10-16). Every customer route requires role `CUSTOMER`, the customer is taken from `X-User-Id`, and a customer can reach only their own orders. Workers have two routes of their own; Order has no internal routes. This task is the receiver side of the older [internal-token-sender-and-security](internal-token-sender-and-security.md) task; the sender side is [task 05](05-internal-clients.md).
*References:* [Order Spec — Security and routing](../../design/services-specs/order-service-spec.md), [Security Model](../../design/services-requirements/security-model.md), [Inventory security task](../inventory-service/07-trusted-header-security.md)

## Acceptance Criteria
- [x] Add `spring-boot-starter-security` (+ `-test`); exclude `UserDetailsServiceAutoConfiguration`.
- [x] Copy the `security` package from inventory-service (`AuthenticatedUser`, `TrustedHeaderAuthenticationFilter`, `SecurityErrorHandlers`, `SecurityConfig`, `UserRole`). The internal-token **filter** is not needed (no internal routes); decide whether to keep the copies identical anyway and note the decision.
- [x] `AuthenticatedUser` also carries the email from `X-User-Email` (optional in the filter, `null` when missing). It never influences authorization.
- [x] Route rules exactly as the spec's table: the six customer routes → `hasRole("CUSTOMER")`; `GET /orders/fulfillment-queue` and `POST /orders/{orderId}/ship` → `hasRole("INVENTORY_WORKER")`; `/actuator/health/**` open; then `denyAll()`. `GET /orders/fulfillment-queue` must not be caught by `GET /orders/{orderId}` (rule order or a UUID-constrained path variable).
- [x] Anonymous → `401`; any other role, including `ADMIN` → `403`; the check runs before body validation.
- [x] **Ownership in one place**, e.g. `OrderAccess.loadOwned(orderId, user)`: loads the order by `id` **and** `customerId`; another customer's order is `404`, exactly like a missing one. Used by EP-ORD-03, 05 and 06.
- [x] Tests: filter unit tests (adapted, including `X-User-Email`); `401` / `403` / allowed per route; the `denyAll()` fallback; a route coverage test like Inventory's `RouteSecurityRulesTest`; customer B gets `404` for customer A's order (with a probe controller until [task 10](10-order-history-and-details.md) exists).

## Technical Notes / Constraints
- Do not use `@PreAuthorize`: the role check must run before binding and validation.
- `INTERNAL_TOKEN` is still needed, but only by the outgoing clients (task 05).
- The gateway must forward `X-User-Email` first: [Auth task 09](../auth-service/09-email-claim-in-jwt.md) and [gateway task 11](../api-getaway/11-forward-user-email-header.md).

## Decisions
- **No internal-token filter.** Order has no internal routes, so only `TrustedHeaderAuthenticationFilter`, `SecurityErrorHandlers`, `UserRole`, `AuthenticatedUser` and `SecurityConfig` were taken over. A request with only `X-Internal-Token` is anonymous (`401`). `INTERNAL_TOKEN` comes with the outgoing clients in task 05.
- **`X-User-Email` is optional in the filter.** A missing, blank, over-long (more than 256 characters) or `@`-less value leaves `AuthenticatedUser.email()` `null` and is logged without the value; the user is still authenticated. Rejecting a missing email is the submit endpoint's job (task 08), so tokens issued before Auth task 09 keep working on the other routes.

## Implementation
Done on 2026-10-16:
- `spring-boot-starter-security` (+ `-test`); `UserDetailsServiceAutoConfiguration` excluded.
- `security/` taken from Inventory (`SecurityErrorHandlers` and `UserRole` unchanged apart from the package); `AuthenticatedUser(id, role, email)` with a `toString()` that leaves the email out; the filter reads `X-User-Email`.
- `SecurityConfig`: health open; `GET /orders/fulfillment-queue` and `POST /orders/{orderId}/ship` → `INVENTORY_WORKER`, declared **before** the customer rules so that `/orders/fulfillment-queue` is not taken for `/orders/{orderId}`; the six customer routes → `CUSTOMER`; then `denyAll()`.
- `services/OrderAccess.loadOwned(orderId, user)`: one query by `id` and `customerId`; another customer's order → `OrderNotFoundException` (`404`).
- The Order endpoints do not exist yet, so the tests use `support/OrderRoutesProbeController`, a `@TestComponent` stand-in with all eight routes, loaded only where a test imports it. Replace it with the real controllers in tasks 07–14; `RouteSecurityRulesTest` then picks them up by itself.
- Tests: `TrustedHeaderAuthenticationFilterTest` (11: Inventory's 7 plus the email cases), `SecurityConfigTest` (17: each customer route for `CUSTOMER` only, each worker route for `INVENTORY_WORKER` only, `GET /orders/{orderId}` for other roles, the queue not taken for an order ID, role check before validation, the email reaching the service, an internal token granting nothing, draft and unlisted routes denied, health open), `RouteSecurityRulesTest` (1), `OrderAccessIntegrationTest` (2 on PostgreSQL through the real chain: the owner sees the order; another customer gets a `404` identical to a missing order's apart from the ID and timestamp).
