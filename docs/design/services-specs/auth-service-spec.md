# Auth Service — Implementation Spec

Responsible for user authentication and JWT issuance. It is the identity master for all system roles. Owns user credentials and roles only — no profile data, no business objects.

The API Gateway validates the JWTs this service issues and forwards the trusted headers `X-User-Id` and `X-User-Role` to downstream services. Auth Service itself does not validate JWTs either: for its own admin-only endpoint it trusts those gateway headers, like every other service (see [Security Model](../services-requirements/security-model.md)).

---

## Service Overview

| | |
|---|---|
| **Owned entities** | `User` (`id`, `email`, `passwordHash`, `role`, `createdAt`) |
| **Key enums** | `UserRole { CUSTOMER, CATALOG_MANAGER, INVENTORY_WORKER, ADMIN }` |
| **Events emitted** | none |
| **Events consumed** | none |
| **Roles issued** | `CUSTOMER`, `CATALOG_MANAGER`, `INVENTORY_WORKER`, `ADMIN` |
| **Database** | Own PostgreSQL database; table `users` (`id`, `email` UNIQUE, `password_hash`, `user_role`, `created_at`), schema managed by Flyway |

**Data rules**
- `email` is stored **normalized** (trimmed, lower-cased); the same normalization is applied wherever an email is looked up, so `Alice@X.com` and `alice@x.com` are the same account. A database `UNIQUE` constraint on `email` is the final guard against duplicates.
- `passwordHash` is a BCrypt hash. The plain-text password is never stored or logged.
- `role` is stored as text (the enum name), never as an ordinal.

**Endpoint summary**

| ID | Method and path | Who may call | Purpose |
|---|---|---|---|
| EP-AUTH-01 | `POST /user/register` | Anyone | Register a customer |
| EP-AUTH-02 | `POST /auth/login` | Anyone | Log in and obtain a JWT |
| EP-AUTH-03 | `POST /user/non-customer` | `ADMIN` | Create a staff or admin account |
| EP-AUTH-JOB-01 | Startup | — | Seed the initial administrator |

All other paths are denied.

---

## EP-AUTH-01: Register Customer

**Type:** `HTTP POST /user/register`
**Caller:** Customer (unauthenticated, via API Gateway)
**Auth:** None

**Steps:**
1. Parse and validate the request body:
   - `email`: non-blank, valid email format, at most 256 characters.
   - `password`: non-blank, 5 to 64 characters. No complexity rules for now.
2. Normalize the email (trim, lower-case) and check whether a user with it already exists.
3. Hash the password with BCrypt.
4. Persist `User(id=UUID, email, passwordHash, role=CUSTOMER, createdAt=now())` and flush immediately.
5. Return `201 Created { userId }`.

> The role is always `CUSTOMER`. Any `role` field in the body is ignored.

**Edge cases:**
- `email` missing, blank or invalid, or `password` missing or outside 5–64 characters → `400 Bad Request` with a field-level `errors` map (see [Error Format](#error-format))
- Malformed JSON → `400 Bad Request`
- `email` already registered (including differing only by letter case) → `409 Conflict` ("Email already in use")
- Race condition: two concurrent requests pass the existence check; the second fails on the database unique constraint, which is translated to the same `409`. Other integrity violations are not translated and result in `500`.

**External calls:** none
**Emits:** none
**Refs:** US-CUST-06, SCN-C01, SCN-C02

---

## EP-AUTH-02: Login

**Type:** `HTTP POST /auth/login`
**Caller:** Any user (unauthenticated, via API Gateway)
**Auth:** None

**Steps:**
1. Parse and validate the request body: `email` and `password` must be non-blank.
2. Normalize the email and look up the user.
3. Verify the provided password against the stored `passwordHash`. If no user was found, the password is still verified against a dummy BCrypt hash so that both failure paths take comparable time.
4. On match: issue a signed JWT (see [JWT](#jwt)).
5. Return `200 OK { token }`.

**Edge cases:**
- `email` or `password` missing or blank → `400 Bad Request` (field-level `errors`)
- No user for the given email → `401 Unauthorized` ("Invalid credentials")
- Password does not match → `401 Unauthorized` ("Invalid credentials")

> The two `401` cases return an identical response (apart from the `timestamp`) to prevent user enumeration.

**External calls:** none
**Emits:** none
**Refs:** US-CUST-06, SCN-C03, SCN-C04

---

## EP-AUTH-03: Create Non-Customer User

**Type:** `HTTP POST /user/non-customer`
**Caller:** Administrator (via API Gateway)
**Auth:** `ROLE_ADMIN` (from the gateway-supplied `X-User-Role` header)

Customers register themselves (EP-AUTH-01); this endpoint is the only way to create staff and administrator accounts after the initial administrator exists (EP-AUTH-JOB-01).

**Steps:**
1. The security filter chain checks the caller before the controller is reached and before the body is parsed: anonymous → `401`; any role other than `ADMIN` → `403`.
2. Parse and validate the body `{ email, password, role }`: `email` and `password` follow the rules of EP-AUTH-01; `role` is required and must be a known role.
3. Reject `role = CUSTOMER`.
4. Normalize the email and check for duplicates, hash the password, persist the user with the requested role (same persistence and race handling as EP-AUTH-01).
5. Return `201 Created { userId }`.

**Edge cases:**
- No or invalid user headers → `401 Unauthorized`
- Authenticated but not `ADMIN` → `403 Forbidden` (also for an invalid body: authorization is evaluated first)
- Invalid `email`/`password`, missing or unknown `role`, malformed JSON → `400 Bad Request`
- `role = CUSTOMER` → `400 Bad Request` with `errors.role`
- `email` already in use → `409 Conflict`

> `ADMIN` may be assigned, so an administrator can create further administrators.

**External calls:** none
**Emits:** none
**Refs:** none (extension beyond the original user stories: needed so that staff roles can exist at all)

---

## EP-AUTH-JOB-01: Initial Administrator Seeding

**Type:** Startup job (`ApplicationRunner`)
**Trigger:** Every application start

**Steps:**
1. Read `auth.admin.email` and `auth.admin.password` from configuration (environment variables `ADMIN_EMAIL`, `ADMIN_PASSWORD`).
2. Create a user with role `ADMIN` using the normal creation logic (the password is hashed with BCrypt before it is stored).
3. If a user with that email already exists, log that and do nothing.

**Edge cases:**
- Restarts and multiple instances starting together are safe: the unique email constraint prevents duplicates.
- Changing `ADMIN_PASSWORD` later does not change an existing administrator's password.
- The application does not start if `auth.admin.password` is not configured.

**External calls:** none
**Emits:** none

---

## Cross-Cutting Behaviour

### Caller identification and authorization

Auth Service runs with Spring Security in stateless mode. Callers are identified only from trusted headers; there is no session, login form or HTTP Basic, and no JWT validation inside the service.

| Request | Treated as |
|---|---|
| `X-User-Id` (UUID) and `X-User-Role` (known role) present | Authenticated user with authority `ROLE_<role>` |
| Missing, partial or malformed headers | Anonymous |
| Valid `X-Internal-Token` | Authenticated service (`ROLE_SERVICE`), takes precedence over user headers |
| Wrong or empty `X-Internal-Token` | Anonymous |

Route rules (everything else is denied, even for administrators):

| Route | Rule |
|---|---|
| `POST /user/register`, `POST /auth/login` | Public |
| `/actuator/health/**` | Public (Kubernetes probes) |
| `POST /user/non-customer` | `ROLE_ADMIN` |
| any other request | Denied (`401` anonymous, `403` authenticated) |

Auth Service exposes no internal (service-to-service) endpoints, so `ROLE_SERVICE` currently has no route.

### JWT

| | |
|---|---|
| **Algorithm** | HS256 (symmetric). The API Gateway validates tokens with the same secret. |
| **Claims** | `sub` = user id (UUID), `role` = role name, `iat`, `exp` |
| **Lifetime** | Configurable, default 1 hour. No refresh tokens. |
| **Signing key** | At least 32 characters, supplied through the environment, never committed |

The signing algorithm is isolated behind a `TokenService` interface, so switching to an asymmetric algorithm (gateway holding only a public key) does not change the API.

### Error Format

All errors are RFC 9457 problem details (`application/problem+json`) with the fields `type`, `title`, `status`, `detail`, `timestamp` and `service` (`auth-service`).

- Validation failures (`400`) additionally carry `errors`: a map from field name to a list of messages, e.g. `{"errors": {"email": ["must be a well-formed email address"]}}`.
- `401` for failed logins has the fixed detail "Invalid credentials"; `401` for missing authentication has the detail "Authentication required"; `403` has the detail "Access denied".
- Unexpected errors return `500` with the generic detail "An unexpected error occurred."; the cause is logged on the server and never returned.

### Configuration

| Environment variable | Purpose |
|---|---|
| `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD` | Database connection |
| `JWT_SECRET` | HS256 signing key (min. 32 characters), shared with the API Gateway |
| `INTERNAL_TOKEN` | Shared internal token (min. 32 characters), identical in every service |
| `ADMIN_EMAIL` (default `admin@voltstore.com`), `ADMIN_PASSWORD` | Initial administrator |

`auth.jwt.expiration` (default `1h`) sets the token lifetime.

### Deployment

- Container image `voltstore/auth-service`; Kubernetes `Service` `auth-service` (ClusterIP) on port `8086` → container port `8080`; own PostgreSQL deployment `auth-service-postgres`.
- Database credentials are in the Secret `auth-db-credentials`; `JWT_SECRET`, `ADMIN_PASSWORD` and `INTERNAL_TOKEN` are in the Secret `auth-secrets`, which is mounted only into the application pod.
- Liveness and readiness probes use `/actuator/health/liveness` and `/actuator/health/readiness`.
