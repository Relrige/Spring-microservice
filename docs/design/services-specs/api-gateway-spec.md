# API Gateway — Implementation Spec

The single entry point for all client traffic, built with **Spring Cloud Gateway**. It routes requests to services, validates the user's JWT at the edge, applies coarse role-based access control per route, and tells downstream services who the caller is through trusted headers. It owns no data and contains no business logic.

Services do not validate JWTs. They trust the identity headers set by the gateway, which is only safe because of the rules in [Trust Boundary](#trust-boundary) below. The full reasoning is in the [Security Model](../services-requirements/security-model.md).

---

## Service Overview

| | |
|---|---|
| **Technology** | Spring Cloud Gateway (stateless, no database) |
| **Owned data** | none |
| **Events emitted / consumed** | none |
| **Secrets** | `JWT_SECRET` — the HS256 key used by Auth Service to sign tokens (the gateway only verifies with it) |
| **Upstreams** | Auth, Customer, Catalog, Order, Inventory, Delivery services (Payment and Notification have no externally routed endpoints) |
| **Deployment** | Kubernetes `Service` `api-gateway` (ClusterIP); the Ingress sends all external traffic here |

---

## Responsibilities

1. **Route** requests by method and path to the owning service, using an explicit allowlist.
2. **Authenticate** the caller from the `Authorization: Bearer <JWT>` header.
3. **Authorize coarsely**: reject callers whose role is not allowed on the route. Finer rules (for example "this order belongs to this customer") stay in the services.
4. **Propagate identity** as the trusted headers `X-User-Id` and `X-User-Role`.
5. **Protect the trust boundary**: strip client-supplied identity headers and never expose internal endpoints.

It does **not** handle service-to-service calls; those go directly between services and are authenticated with the internal token (see the Security Model).

---

## Request Pipeline

Filters run in this order for every incoming request.

1. **Strip identity headers.** Remove `X-User-Id`, `X-User-Role` and `X-Internal-Token` from the incoming request, so identity can only come from step 5.
2. **Match a route.** Match method and path against the [route table](#route-table). No match → `404 Not Found`. Internal endpoints are not in the table, so they are always unmatched.
3. **Authenticate**, depending on the route's auth mode:

   | Mode | No `Authorization` header | Valid JWT | Invalid, expired or malformed JWT |
   |---|---|---|---|
   | **Public** | Forward as anonymous | Ignored, forwarded as anonymous | Ignored, forwarded as anonymous |
   | **Optional** | Forward as anonymous | Identity is set (step 5) | `401 Unauthorized` |
   | **Required** | `401 Unauthorized` | Identity is set (step 5) | `401 Unauthorized` |

   A JWT is valid when: the signature verifies with HS256 and `JWT_SECRET`; `exp` is in the future (small clock-skew leeway allowed); `sub` is a UUID; and `role` is one of the known roles.
4. **Authorize.** For routes with allowed roles, a caller whose `role` is not listed → `403 Forbidden`. Roles are not hierarchical: `ADMIN` has no implicit access to other services' routes.
5. **Set identity headers.** For an authenticated caller, add `X-User-Id` (= `sub`) and `X-User-Role` (= `role`). Anonymous requests carry neither header.
6. **Remove `Authorization`** from the forwarded request (downstream services do not need the token), then forward to the upstream.

Errors produced by the gateway itself use the same problem-details format as the services (`application/problem+json` with `type`, `title`, `status`, `detail`, `timestamp`, `service: api-gateway`). Error bodies of `401` do not say why the token was rejected.

---

## Trust Boundary

The model is safe only if all of these hold:

- **Services are unreachable except through the gateway** (and other services): ClusterIP services only, no per-service Ingress rules, and a Kubernetes `NetworkPolicy` that allows ingress to each service only from the gateway and from other services.
- **Identity headers are stripped** on every incoming request (pipeline step 1), including `X-Internal-Token`.
- **Internal endpoints are never routed.** Routes are an allowlist of method + path pairs; there is no wildcard such as `/payments/**`.
- **`JWT_SECRET` is a secret** shared only by Auth Service and the gateway (Kubernetes Secret). Anyone holding it can mint tokens.

---

## Route Table

Derived from the [API Contracts](../services-requirements/api-contracts.md). **Auth mode:** `Public` = no token needed; `Optional` = token used if present; `Required` = token needed and role checked. An empty roles column means any authenticated role (or anonymous, for `Public` and `Optional`).

| Method | Path | Upstream | Auth mode | Allowed roles |
|---|---|---|---|---|
| `POST` | `/user/register` | auth-service | Public | — |
| `POST` | `/auth/login` | auth-service | Public | — |
| `POST` | `/user/non-customer` | auth-service | Required | `ADMIN` |
| `GET` | `/customers/me/profile` | customer-service | Required | `CUSTOMER` |
| `PUT` | `/customers/me/profile` | customer-service | Required | `CUSTOMER` |
| `GET` | `/customers/cart` | customer-service | Required | `CUSTOMER` |
| `POST` | `/customers/cart/items` | customer-service | Required | `CUSTOMER` |
| `PATCH`, `DELETE` | `/customers/cart/items/{id}` | customer-service | Required | `CUSTOMER` |
| `POST` | `/customers/cart/merge` | customer-service | Required | `CUSTOMER` |
| `GET` | `/catalog/products` | catalog-service | Optional | — (managers get extra filters) |
| `GET` | `/catalog/products/{id}` | catalog-service | Public | — |
| `POST` | `/catalog/products` | catalog-service | Required | `CATALOG_MANAGER` |
| `PUT` | `/catalog/products/{id}` | catalog-service | Required | `CATALOG_MANAGER` |
| `PATCH` | `/catalog/products/{id}/status` | catalog-service | Required | `CATALOG_MANAGER` |
| `POST` | `/catalog/categories` | catalog-service | Required | `CATALOG_MANAGER` |
| `PUT`, `DELETE` | `/catalog/categories/{id}` | catalog-service | Required | `CATALOG_MANAGER` |
| `GET` | `/inventory/stock` | inventory-service | Required | `INVENTORY_WORKER` |
| `POST` | `/inventory/stock/{id}/adjust` | inventory-service | Required | `INVENTORY_WORKER` |
| `PUT` | `/inventory/stock/{id}/packaging` | inventory-service | Required | `INVENTORY_WORKER` |
| `POST` | `/orders/validate` | order-service | Optional | — |
| `POST` | `/orders` | order-service | Optional | — |
| `POST` | `/orders/{id}/pay` | order-service | Optional | — |
| `GET` | `/orders` | order-service | Required | `CUSTOMER` |
| `GET`, `DELETE` | `/orders/{id}` | order-service | Optional | — (guest access by `?token=` is checked in the service) |
| `GET` | `/orders/fulfillment-queue` | order-service | Required | `INVENTORY_WORKER` |
| `POST` | `/orders/{id}/ship` | order-service | Required | `INVENTORY_WORKER` |
| `POST` | `/delivery/orders/{id}/ttn/retry` | delivery-service | Required | `INVENTORY_WORKER` |
| `POST` | `/delivery/carrier-webhook` | delivery-service | Public | — (see open issues) |

**Not routed (internal endpoints):** `GET /catalog/products/batch-get`; `GET /inventory/stock/{id}/readiness`; `GET /inventory/stock/{id}/packaging`; `POST /inventory/reservations`; `POST /inventory/reservations/{orderId}/release`; `POST /payments/charge`; `POST /payments/refund`; `GET /delivery/shipments`; `DELETE /delivery/shipments/{orderId}`.

**Route-definition rules** (to avoid accidentally exposing or shadowing endpoints):
- A route is a method + path pair. `GET /inventory/stock/{id}/packaging` is internal and unrouted, while `PUT` on the same path is routed.
- Path variables named `{id}` or `{orderId}` must be constrained to UUIDs. Otherwise `GET /catalog/products/batch-get` would match `/catalog/products/{id}`, and `/orders/fulfillment-queue` would match `/orders/{id}`.
- The path is forwarded unchanged (no prefix stripping), because services already serve under their own prefix.
- Requests with a method not listed for a path get `404` (no route), not `405`.

---

## Auth Service Interaction

- Login and registration are routed as `Public`; the gateway passes the request body through unchanged and returns Auth Service's response (including its `401`/`409`).
- The gateway never calls Auth Service. It verifies tokens locally with `JWT_SECRET`, using the algorithm and claims defined in [Auth Service Spec — JWT](auth-service-spec.md#jwt) (HS256; `sub`, `role`, `iat`, `exp`).
- `POST /user/non-customer` requires `ADMIN` at the gateway **and** at Auth Service (defence in depth).

---

## Configuration

| Environment variable | Purpose |
|---|---|
| `JWT_SECRET` | HS256 verification key, identical to Auth Service's |
| Route definitions | Part of the gateway's configuration (YAML), one entry per row of the route table, including upstream service URLs (`http://<service>:<port>`) |

Upstream cluster addresses at the time of writing: auth `8086`, catalog `8081`, order `8082`, delivery `8083`, inventory `8084`, payment `8085`; customer-service is not deployed yet.

### Relation to the Kubernetes Ingress

The target state is that the Ingress terminates TLS and forwards **all** external traffic to the gateway; per-service routing lives only in the gateway. The current Ingress still routes straight to individual services and is to be pointed at the gateway when it is deployed.

---

## Open Issues and Deferred

- **Carrier webhook** (`POST /delivery/carrier-webhook`) is public, as in the contracts. Delivery Service may verify a shared secret or signature; until then anyone can call it.
- **Rate limiting** (especially for `/auth/login` and registration), **CORS**, request/response transformation, and observability hooks (such as a correlation ID generated at the edge) are deferred.
- **Token revocation / logout** is not supported; tokens live until they expire (default 1 hour). Refresh tokens are out of scope.
- **HS256** means the gateway could mint valid tokens. Moving to an asymmetric algorithm (gateway holds only the public key) is a possible later improvement.
- **Customer service routes** are listed per the contracts but the service is not implemented yet.
