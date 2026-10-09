---
status: TODO
service: catalog-service
---
# Trusted-Header Security (Copy from Auth Service)

## Context
The gateway authenticates users and forwards `X-User-Id` / `X-User-Role`; services do not validate JWTs. Catalog Service has public routes, manager-only routes, and one internal route (`batch-get`). The first CRUD and Kafka tasks were built without authentication so the messaging path could be proven quickly. This task copies the `security` package proven in auth-service, sets up the filter chain, and **protects every endpoint that already exists**. Endpoint tasks after this one add their own route rule.
*References:* [Security Model](../../design/services-requirements/security-model.md), [Trusted-Header Authentication (pilot)](../auth-service/08-trusted-header-authentication.md), [API Gateway Spec — Route Table](../../design/services-specs/api-gateway-spec.md)

## Acceptance Criteria
- [ ] Add `spring-boot-starter-security` and `spring-boot-starter-security-test` to `pom.xml`; exclude `UserDetailsServiceAutoConfiguration` in `application.yml` (no username/password login, same reason as in auth-service).
- [ ] Copy the `security` package from auth-service (`AuthenticatedUser`, `TrustedHeaderAuthenticationFilter`, `InternalTokenAuthenticationFilter`, `InternalTokenProperties`, `SecurityErrorHandlers`, `SecurityConfig`) and adapt the package name and the role enum. Leave out the JWT classes (`JwtTokenService`, `TokenService`): Catalog never issues or validates tokens.
- [ ] The role enum contains `CUSTOMER`, `CATALOG_MANAGER`, `INVENTORY_WORKER`, `ADMIN`.
- [ ] `SecurityConfig` produces a stateless chain (no CSRF, sessions, form login, HTTP Basic), registers both filters, permits the health/actuator probe endpoints, and ends with `anyRequest().denyAll()`.
- [ ] Route rules for endpoints that already exist: `POST /catalog/categories`, `PUT`/`DELETE /catalog/categories/{id}`, `POST /catalog/products`, `PUT /catalog/products/{id}` → `hasRole("CATALOG_MANAGER")`.
- [ ] `401` for anonymous access to a protected route, `403` for a wrong role (including `ADMIN` and `CUSTOMER`), both rendered as `ProblemDetail` with the same shape as [task 04](04-error-handling-and-validation.md). The role check runs before body validation.
- [ ] Add `auth.internal.token` configuration (`INTERNAL_TOKEN`, not blank, at least 32 characters, never logged) to `.env`, `.env.example`, `docker-compose.yml`, and the test properties.
- [ ] Existing controller and integration tests (including the Kafka publish test from [task 11](11-product-created-publish-and-listen.md)) are updated to send the trusted headers and still pass.
- [ ] Tests: unit tests for both filters (copy and adapt from auth-service); `401`/`403`/success tests for each protected endpoint; a slice test proving the `denyAll()` fallback; a **route coverage test** equivalent to auth-service's `RouteSecurityRulesTest` so a forgotten rule fails the build.

## Technical Notes / Constraints
- **Rules for later endpoints** (each of those tasks adds its own line and its own `401`/`403` tests):

  | Route | Rule |
  |---|---|
  | `GET /catalog/products/batch-get` | `hasRole("SERVICE")` |
  | `GET /catalog/products` | `permitAll()` (role only changes what is returned) |
  | `GET /catalog/products/{id}` | `permitAll()` |
  | `GET /catalog/categories` | `permitAll()` |
  | `PATCH /catalog/products/{id}/status` | `hasRole("CATALOG_MANAGER")` |

- **Matcher order matters.** The first matching rule wins. `/catalog/products/batch-get` must be declared **before** `/catalog/products/*`, otherwise the `permitAll()` rule for product details also opens the internal endpoint.
- The route coverage test calls every route as `ADMIN` and fails on `403`. `batch-get` is internal-only, so it must be excluded in that test and covered with a `SERVICE` caller instead (auth-service notes describe this exception).
- The role check is in the filter chain, before body binding and validation, so an unauthorized caller never receives validation feedback. Do not use `@PreAuthorize`.
- Roles are not hierarchical: `ADMIN` has no implicit access to manager routes.
- The `INTERNAL_TOKEN` Kubernetes Secret is wired in [task 20](20-containerization-and-deployment.md). The sender side (adding the token to the call to Inventory) is in [task 21](21-inventory-readiness-client.md).
