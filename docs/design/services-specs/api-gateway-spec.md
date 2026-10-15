# API Gateway — Implementation Spec

The single entry point for all client traffic, built with **Spring Cloud Gateway Server WebMVC** (the servlet variant of Spring Cloud Gateway). It routes requests to services, validates the user's JWT at the edge, applies coarse role-based access control per route, and tells downstream services who the caller is through trusted headers. It owns no data and contains no business logic.

Services do not validate JWTs. They trust the identity headers set by the gateway, which is only safe because of the rules in [Trust Boundary](#trust-boundary) below. The full reasoning is in the [Security Model](../services-requirements/security-model.md).

---

## Service Overview

| | |
|---|---|
| **Technology** | Spring Cloud Gateway Server WebMVC on Spring Boot (servlet stack, like the services); `nimbus-jose-jwt` for token verification; no Spring Security, no database (stateless) |
| **Owned data** | none |
| **Events emitted / consumed** | none |
| **Secrets** | `AUTH_JWT_SECRET` — the HS256 key used by Auth Service to sign tokens (the gateway only verifies with it); same variable name as in Auth Service |
| **Upstreams** | Auth, Catalog, Inventory (routed); Order, Delivery, Customer (routes added once those services implement the contract paths, see [Route Table](#route-table)). Payment and Notification have no externally routed endpoints |
| **Deployment** | Kubernetes `Service` `api-gateway` (ClusterIP, port `8080`); the Ingress sends all external traffic here. Docker Compose: host port `8080`. Actuator on a separate management port `8090` (not exposed) |

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

   A JWT is valid when: the header algorithm is HS256 and the signature verifies with `AUTH_JWT_SECRET`; `exp` is present and in the future (clock-skew leeway `auth.jwt.leeway`, default `30s`); `sub` is a UUID in canonical form; and `role` is one of the known roles. Any `Authorization` value that is not exactly one `Bearer <token>` (scheme case-insensitive) counts as an invalid token.
4. **Authorize.** For routes with allowed roles, a caller whose `role` is not listed → `403 Forbidden`. Roles are not hierarchical: `ADMIN` has no implicit access to other services' routes.
5. **Set identity headers.** For an authenticated caller, add `X-User-Id` (= `sub`) and `X-User-Role` (= `role`). Anonymous requests carry neither header.
6. **Remove `Authorization`** from the forwarded request (downstream services do not need the token), then forward to the upstream.

### Implementation

- Steps 1 and 3–6 are one route filter, `TrustBoundary`, declared on every route in the YAML configuration. Each route carries its auth mode and roles as route `metadata` (`authMode`, `roles`). Gateway MVC does not expose metadata at request time, so `RouteAccessPolicy` reads it at startup and the filter looks the rule up by route id. The gateway **refuses to start** if a route has no valid `authMode`, lists `roles` on a non-`REQUIRED` route, or does not apply the `TrustBoundary` filter.
- The filter therefore runs after route matching (step 2). This does not weaken step 1: an unmatched request is answered `404` and never forwarded, and every forwarded request has passed through the filter.
- The reason a token was rejected is logged at debug level; the token itself is never logged.

### Errors

Errors produced by the gateway itself use the same problem-details format as the services (`application/problem+json` with `type` `https://voltstore.com/errors/<kind>`, `title`, `status`, `detail`, `timestamp`, `service: api-gateway`). The details are fixed strings that reveal neither token problems nor upstream host names:

| Situation | Status | `detail` |
|---|---|---|
| No route (unknown path, unlisted method, non-UUID id, internal endpoint) | `404` | No route matches the request. |
| Missing or invalid token where one is needed | `401` | Authentication required |
| Role not allowed on the route | `403` | Access denied |
| Upstream unreachable (connection refused, unknown host, connect timeout) | `502` | The upstream service is unavailable. |
| Upstream did not answer within the read timeout | `504` | The upstream service did not respond in time. |
| Unexpected gateway error | `500` | An unexpected error occurred. |

Responses of upstreams, including their `4xx`/`5xx` problem details, are passed through unchanged.

---

## Trust Boundary

The model is safe only if all of these hold:

- **Services are unreachable except through the gateway** (and other services): ClusterIP services only, a single Ingress rule (`/` → `api-gateway`, path unchanged), and `NetworkPolicy` objects in `k8s/network/`: default deny ingress in the namespace; the gateway accepts only the Ingress controller (namespace `ingress-nginx`); services accept the gateway and other services; Kafka accepts services; each database accepts only its own service. Pods are selected by the label `app.kubernetes.io/component` (`gateway`, `service`, `database`, `broker`). Policies are enforced only by a policy-capable CNI, so minikube runs with `--cni=calico`.
- **Identity headers are stripped** on every incoming request (pipeline step 1), including `X-Internal-Token`.
- **Internal endpoints are never routed.** Routes are an allowlist of method + path pairs; there is no wildcard such as `/payments/**`.
- **`AUTH_JWT_SECRET` is a secret** shared only by Auth Service and the gateway (Kubernetes Secrets `auth-secrets` and `gateway-secrets`, which must hold the same value). Anyone holding it can mint tokens.
- **Actuator is not public**: it listens on the management port `8090`, which neither the Service nor Compose exposes; `/actuator/**` on the public port is an ordinary `404`.

---

## Route Table

Derived from the [API Contracts](../services-requirements/api-contracts.md). Rows marked *(deferred)* are not configured yet: Order and Delivery Service do not expose the contract paths yet and Customer Service does not exist; their routes are added per service. **Auth mode:** `Public` = no token needed; `Optional` = token used if present; `Required` = token needed and role checked. An empty roles column means any authenticated role (or anonymous, for `Public` and `Optional`).

| Method | Path | Upstream | Auth mode | Allowed roles |
|---|---|---|---|---|
| `POST` | `/user/register` | auth-service | Public | — |
| `POST` | `/auth/login` | auth-service | Public | — |
| `POST` | `/user/non-customer` | auth-service | Required | `ADMIN` |
| `GET` | `/customers/me/profile` | customer-service *(deferred)* | Required | `CUSTOMER` |
| `PUT` | `/customers/me/profile` | customer-service *(deferred)* | Required | `CUSTOMER` |
| `GET` | `/customers/cart` | customer-service *(deferred)* | Required | `CUSTOMER` |
| `POST` | `/customers/cart/items` | customer-service *(deferred)* | Required | `CUSTOMER` |
| `PATCH`, `DELETE` | `/customers/cart/items/{id}` | customer-service *(deferred)* | Required | `CUSTOMER` |
| `POST` | `/customers/cart/merge` | customer-service *(deferred)* | Required | `CUSTOMER` |
| `GET` | `/catalog/products` | catalog-service | Optional | — (managers get extra filters) |
| `GET` | `/catalog/products/{id}` | catalog-service | Public | — |
| `POST` | `/catalog/products` | catalog-service | Required | `CATALOG_MANAGER` |
| `PUT` | `/catalog/products/{id}` | catalog-service | Required | `CATALOG_MANAGER` |
| `PATCH` | `/catalog/products/{id}/status` | catalog-service | Required | `CATALOG_MANAGER` |
| `GET` | `/catalog/categories` | catalog-service | Public | — |
| `POST` | `/catalog/categories` | catalog-service | Required | `CATALOG_MANAGER` |
| `PUT`, `DELETE` | `/catalog/categories/{id}` | catalog-service | Required | `CATALOG_MANAGER` |
| `GET` | `/inventory/stock` | inventory-service | Required | `INVENTORY_WORKER` |
| `POST` | `/inventory/stock/{id}/adjust` | inventory-service | Required | `INVENTORY_WORKER` |
| `PUT` | `/inventory/stock/{id}/packaging` | inventory-service | Required | `INVENTORY_WORKER` |
| `POST` | `/orders/validate` | order-service *(deferred)* | Optional | — |
| `POST` | `/orders` | order-service *(deferred)* | Optional | — |
| `POST` | `/orders/{id}/pay` | order-service *(deferred)* | Optional | — |
| `GET` | `/orders` | order-service *(deferred)* | Required | `CUSTOMER` |
| `GET`, `DELETE` | `/orders/{id}` | order-service *(deferred)* | Optional | — (guest access by `?token=` is checked in the service) |
| `GET` | `/orders/fulfillment-queue` | order-service *(deferred)* | Required | `INVENTORY_WORKER` |
| `POST` | `/orders/{id}/ship` | order-service *(deferred)* | Required | `INVENTORY_WORKER` |
| `POST` | `/delivery/orders/{id}/ttn/retry` | delivery-service *(deferred)* | Required | `INVENTORY_WORKER` |

**Not routed (internal endpoints):** `GET /catalog/products/batch-get`; `GET /inventory/stock/{id}/readiness`; `GET /inventory/stock/{id}/packaging`; `POST /inventory/reservations`; `POST /inventory/reservations/{orderId}/release`; `POST /payments/charge`; `POST /payments/refund`; `GET /delivery/shipments`; `DELETE /delivery/shipments/{orderId}`.

**Route-definition rules** (to avoid accidentally exposing or shadowing endpoints):
- A route is a method + path pair. `GET /inventory/stock/{id}/packaging` is internal and unrouted, while `PUT` on the same path is routed.
- Path variables named `{id}` or `{orderId}` must be constrained to UUIDs. Otherwise `GET /catalog/products/batch-get` would match `/catalog/products/{id}`, and `/orders/fulfillment-queue` would match `/orders/{id}`. In the configuration this is a regex path variable in the `Path` predicate: `{id:[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}}`.
- The path is forwarded unchanged (no prefix stripping), because services already serve under their own prefix.
- Requests with a method not listed for a path get `404` (no route), not `405`.
- The gateway test suite compares the configured routes with this table, so a route cannot be added or removed in only one of the two places.

---

## Auth Service Interaction

- Login and registration are routed as `Public`; the gateway passes the request body through unchanged and returns Auth Service's response (including its `401`/`409`).
- The gateway never calls Auth Service. It verifies tokens locally with `AUTH_JWT_SECRET`, using the algorithm and claims defined in [Auth Service Spec — JWT](auth-service-spec.md#jwt) (HS256; `sub`, `role`, `iat`, `exp`).
- `POST /user/non-customer` requires `ADMIN` at the gateway **and** at Auth Service (defence in depth).

---

## Configuration

| Setting | Environment variable | Purpose / default |
|---|---|---|
| `auth.jwt.secret` | `AUTH_JWT_SECRET` | HS256 verification key, identical to Auth Service's; at least 32 characters, checked at startup |
| `auth.jwt.leeway` | `AUTH_JWT_LEEWAY` | Tolerated clock difference for `exp`; default `30s` |
| `gateway.upstreams.auth` / `.catalog` / `.inventory` | `GATEWAY_UPSTREAMS_AUTH` / `_CATALOG` / `_INVENTORY` | Upstream base URLs. Kubernetes (ConfigMap `gateway-config`): Service name and port, e.g. `http://auth-service:8086`. Compose: service name and container port, e.g. `http://auth-service:8080` |
| `spring.http.clients.connect-timeout` / `read-timeout` | `SPRING_HTTP_CLIENTS_CONNECT_TIMEOUT` / `_READ_TIMEOUT` | Upstream timeouts; defaults `2s` / `15s` (room for upstreams that call other services with retries) |
| `management.server.port` | — | Actuator and health probes; `8090` |
| Route definitions | — | `spring.cloud.gateway.server.webmvc.routes` in `application.yml`: one entry per configured row of the route table, with `Method` and `Path` predicates, the `TrustBoundary` filter and `metadata.authMode` / `metadata.roles` |

Upstream cluster addresses at the time of writing: auth `8086`, catalog `8081`, order `8082`, delivery `8083`, inventory `8084`, payment `8085`; customer-service is not deployed yet.

### Relation to the Kubernetes Ingress

The Ingress (`k8s/ingress/ingress.yaml`) forwards **all** external traffic, with the path unchanged, to the `api-gateway` Service; per-service routing lives only in the gateway. TLS is not terminated in the local setup (plain HTTP via `minikube tunnel`); terminating TLS at the Ingress remains the target for a real deployment.

---

## Open Issues and Deferred

- **Carrier webhook:** removed from the contracts (Delivery spec, decision 2026-10-15). The carrier is mocked in-process and reports deliveries without HTTP. If the deferred out-of-process carrier simulator is built, a public `POST /delivery/carrier-webhook` route returns, with an HMAC signature verified by Delivery Service.
- **Rate limiting** (especially for `/auth/login` and registration), **CORS**, request/response transformation, and observability hooks (such as a correlation ID generated at the edge) are deferred.
- **Token revocation / logout** is not supported; tokens live until they expire (default 1 hour). Refresh tokens are out of scope.
- **HS256** means the gateway could mint valid tokens. Moving to an asymmetric algorithm (gateway holds only the public key) is a possible later improvement.
- **Order, Delivery and Customer routes** are listed per the contracts but not configured until those services implement the contract paths (Customer Service does not exist yet).
- **Label-based NetworkPolicies** trust pod labels; anyone allowed to create pods with arbitrary labels in the namespace can bypass them. In a real cluster pod creation is restricted by RBAC.
