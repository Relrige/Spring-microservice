# Auth Service — Implementation Spec

Responsible for user authentication and JWT issuance. It is the identity master for all system roles. Owns user credentials and roles only — no profile data, no business objects. All other services receive a trusted `X-User-Id` and `X-User-Role` header from the API Gateway and do not validate JWTs themselves.

---

## Service Overview

| | |
|---|---|
| **Owned entities** | `User` (`id`, `email`, `passwordHash`, `role`, `createdAt`) |
| **Events emitted** | none |
| **Events consumed** | none |
| **Roles issued** | `CUSTOMER`, `CATALOG_MANAGER`, `INVENTORY_WORKER`, `ADMIN` |

---

## EP-AUTH-01: Register Customer

**Type:** `HTTP POST /auth/register`
**Caller:** Customer (unauthenticated, via API Gateway)
**Auth:** None

**Steps:**
1. Parse and validate the request body: `email` must be non-empty and in valid format; `password` must meet strength rules (minimum length, complexity — exact rules TBD during implementation).
2. Query the `users` table by `email` to detect duplicates.
3. Hash the password using a strong one-way algorithm (e.g., BCrypt).
4. Persist `User(id=UUID, email, passwordHash, role=CUSTOMER, createdAt=now())`.
5. Return `201 Created { userId }`.

**Edge cases:**
- `email` is missing or has invalid format → `400 Bad Request` with a field-level error message
- `password` does not meet strength requirements → `400 Bad Request`
- `email` already registered → `409 Conflict` ("Email already in use")

**External calls:** none
**Emits:** none
**Refs:** US-CUST-06, SCN-C01, SCN-C02

---

## EP-AUTH-02: Login

**Type:** `HTTP POST /auth/login`
**Caller:** Any user (unauthenticated, via API Gateway)
**Auth:** None

**Steps:**
1. Parse and validate the request body: `email` and `password` must be non-empty.
2. Query `User` by `email`.
3. Verify the provided plain-text `password` against the stored `passwordHash`.
4. On match: issue a signed JWT containing `{ sub: userId, role, exp }`. Expiry window TBD.
5. Return `200 OK { token }`.

**Edge cases:**
- `email` or `password` missing → `400 Bad Request`
- No user found for the given email → `401 Unauthorized` ("Invalid credentials"). Do **not** distinguish "not found" from "wrong password" to prevent user enumeration attacks.
- Password does not match → `401 Unauthorized` ("Invalid credentials")

**External calls:** none
**Emits:** none
**Refs:** US-CUST-06, SCN-C03, SCN-C04
