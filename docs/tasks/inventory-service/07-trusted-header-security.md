---
status: DONE
service: inventory-service
---
# Trusted-Header Security (Copy from Auth Service)

## Context
Inventory Service has worker-only routes (`INVENTORY_WORKER`) and several `Internal` routes called by Catalog, Delivery and Order. The first tasks were built without authentication so the messaging path could be proven quickly. This task copies the `security` package proven in auth-service, sets up the filter chain, and **protects every endpoint that already exists**. Later endpoint tasks add their own rule.
*References:* [Security Model](../../design/services-requirements/security-model.md), [Trusted-Header Authentication (pilot)](../auth-service/08-trusted-header-authentication.md), [Catalog security task](../catalog-service/12-trusted-header-security.md), [API Gateway Spec — Route Table](../../design/services-specs/api-gateway-spec.md)

## Acceptance Criteria
- [x] Add `spring-boot-starter-security` and `spring-boot-starter-security-test`; exclude `UserDetailsServiceAutoConfiguration` in `application.yml`.
- [x] Copy the `security` package from auth-service (`AuthenticatedUser`, `TrustedHeaderAuthenticationFilter`, `InternalTokenAuthenticationFilter`, `InternalTokenProperties`, `SecurityErrorHandlers`, `SecurityConfig`), adapt package names and the role enum (`CUSTOMER`, `CATALOG_MANAGER`, `INVENTORY_WORKER`, `ADMIN`). Leave out the JWT classes.
- [x] `SecurityConfig` is stateless (no CSRF, sessions, form login, HTTP Basic), registers both filters, permits the health probes, and ends with `anyRequest().denyAll()`.
- [x] Route rule for the existing endpoint: `POST /inventory/stock/{productId}/adjust` → `hasRole("INVENTORY_WORKER")`.
- [x] `401` for anonymous access to a protected route, `403` for a wrong role (including `ADMIN`, `CATALOG_MANAGER`, `CUSTOMER`), rendered as `ProblemDetail` with the shape from [task 03](03-error-handling-and-validation.md). The role check runs before body validation.
- [x] Add `auth.internal.token` configuration (`INTERNAL_TOKEN`, not blank, at least 32 characters, never logged) to `.env`, `.env.example`, `docker-compose.yml`, and test properties.
- [x] Existing tests (including the Kafka publish test from [task 06](06-stock-events-publish-and-listen.md)) send the trusted headers and still pass.
- [x] Tests: unit tests for both filters (adapted from auth-service); `401`/`403`/success tests for each protected endpoint; a slice test proving the `denyAll()` fallback; a **route coverage test** like auth-service's `RouteSecurityRulesTest`.

## Technical Notes / Constraints
- **Planned rules for later endpoints** (each task adds its own line and tests):

  | Route | Rule |
  |---|---|
  | `GET /inventory/stock` | `hasRole("INVENTORY_WORKER")` |
  | `PUT /inventory/stock/{productId}/packaging` | `hasRole("INVENTORY_WORKER")` |
  | `GET /inventory/stock/{productId}/readiness` | `hasRole("SERVICE")` |
  | `GET /inventory/stock/{productId}/packaging` | `hasRole("SERVICE")` |
  | `POST /inventory/reservations` | `hasRole("SERVICE")` |
  | `POST /inventory/reservations/{orderId}/release` | `hasRole("SERVICE")` |

- Rules are **method + path**: `GET` and `PUT` on `/inventory/stock/{productId}/packaging` have different rules (the first is internal and never routed by the gateway, the second is a worker endpoint). Declare matchers with the HTTP method.
- The route coverage test calls every route as `ADMIN` and fails on `403`. Internal-only routes (`SERVICE`) must be excluded in that test and covered with a `SERVICE` caller instead.
  - *Superseded (as implemented):* `RouteSecurityRulesTest` calls every route with every user role **and** the internal-service caller, and fails only if all of them get `403`. Internal routes therefore need no exclusion.
- Do not use `@PreAuthorize`: the role check must run before binding and validation.
- Roles are not hierarchical. The `INTERNAL_TOKEN` Kubernetes Secret is wired in [task 18](18-containerization-and-deployment.md); the sender side (the token on calls to Catalog) is in [task 19](19-catalog-product-existence-client.md).
- *Sprint-end spec update (2026-10-12):* the full route/role table is in the [Inventory spec](../../design/services-specs/inventory-service-spec.md) (*Security and routing*).
