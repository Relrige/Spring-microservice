---
status: DONE
service: delivery-service
---
# Trusted-Header Security and Internal Token

## Context
Delivery has three kinds of HTTP callers: Order Service (internal endpoints), the inventory worker (via the gateway), and the Kubernetes probes. It also calls Inventory's internal packaging endpoint, so it is a **sender** of the internal token as well as a receiver.

There is **no public endpoint**: the carrier is an in-process mock and reports deliveries through a Java interface, not a webhook (decision 2026-10-15). A signed webhook is part of the deferred [task 17](17-future-carrier-simulator-and-webhook-signature.md).
*References:* [Security Model](../../design/services-requirements/security-model.md), [Delivery Spec — Security and routing](../../design/services-specs/delivery-service-spec.md), [Inventory security task](../inventory-service/07-trusted-header-security.md)

## Acceptance Criteria
- [x] Copy the `security` package (trusted-header filter, internal-token filter, `AuthenticatedUser`, security chain, `401`/`403` handlers) from `inventory-service`, keeping it as identical as possible.
- [x] Route rules, then `denyAll()`:
  - `GET /delivery/shipments` and `DELETE /delivery/shipments/{orderId}` → `hasRole("SERVICE")`
  - `POST /delivery/orders/{orderId}/ttn/retry` → `hasRole("INVENTORY_WORKER")`
  - `/actuator/health/**` → `permitAll()`
- [x] The role check runs before the body is parsed (no validation feedback to unauthorized callers).
- [x] Sender side: an `InternalTokenInterceptor` adds `X-Internal-Token` (and `X-Correlation-Id`) on the Inventory `RestClient` only. It is registered in [task 09](09-inventory-packaging-client.md).
- [x] The token variable is `AUTH_INTERNAL_TOKEN` (bound to `auth.internal.token`), as in auth-service and Catalog (decision 2026-10-15; Inventory's `INTERNAL_TOKEN` is the outlier). Add it to `.env.example` and a new `k8s/delivery/app-secret-template.yaml` (`delivery-secrets`) with the same placeholder value as the other services.
- [x] Tests: a route-coverage test (every controller route has a rule); `401`/`403`/success per protected route; `ADMIN` gets `403` on worker and internal routes.

## Technical Notes / Constraints
- The token is never logged.
- The worker route is added to the gateway in [API gateway task 10](../api-getaway/10-remaining-service-routes.md).

## Implementation Notes (2026-10-15)
- **Copied from Inventory** with only the package renamed: `security` (`AuthenticatedUser`, `UserRole`, `TrustedHeaderAuthenticationFilter`, `InternalTokenAuthenticationFilter`, `InternalTokenProperties`, `SecurityErrorHandlers`), `filters` (`CorrelationIdFilter`, `CorrelationIdKeys`), and the sender side `clients.InternalTokenInterceptor` / `clients.CorrelationIdInterceptor` (registered on the Inventory `RestClient` in task 09).
- **`SecurityConfig`:** `/actuator/health/**` open; `GET /delivery/shipments` and `DELETE /delivery/shipments/{orderId}` → `SERVICE`; `POST /delivery/orders/{orderId}/ttn/retry` → `INVENTORY_WORKER`; everything else `denyAll()`. Authorization runs in the filter chain, so an unauthorized caller never reaches body parsing or validation (checked with a malformed body in the test).
- **Token variable:** `AUTH_INTERNAL_TOKEN` → `auth.internal.token` (validated: not blank, at least 32 characters). Added to `.env.example` and the new `k8s/delivery/app-secret-template.yaml` (`delivery-secrets`), which `app-deployment.yaml` now also loads (otherwise the pod would not start; the rest of the deployment alignment is task 16). `UserDetailsServiceAutoConfiguration` is excluded as in Inventory.
- **Deviation (test order):** there is no real controller yet, so `SecurityConfigTest` checks every rule against a probe controller that maps the three routes (`401` anonymous, `200` for the allowed role, `403` for every other role including `ADMIN`, wrong token, token precedence, health open, unlisted route denied). The route-coverage test over the real controllers (`RouteSecurityRulesTest`, copied from Inventory) is added with the first controller in task 12.
- **Local Compose:** each developer's untracked `delivery-service/.env` needs the new `AUTH_INTERNAL_TOKEN` line (copy from `.env.example`).
- **Verified (2026-10-15):** `SecurityConfigTest` (13 cases) and the whole module's tests pass.
