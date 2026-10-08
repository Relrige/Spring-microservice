---
status: DONE
service: auth-service
---
# Bootstrap Staff and Admin Accounts

## Context
The Auth Service is the identity master for `CATALOG_MANAGER`, `INVENTORY_WORKER`, and `ADMIN`, but the spec defines only customer registration. Without some way to create staff accounts, those roles can never log in. This gap needs an explicit, minimal answer.
*References:* [Auth Service Spec — Service Overview (Roles issued)](../../design/services-specs/auth-service-spec.md)

## Acceptance Criteria
- [x] Decide and document how non-customer accounts are created. **Decision: option 2**, an admin-only endpoint, plus a startup seeder that creates the first administrator (otherwise nobody could call the endpoint).
- [x] Implement the chosen option: `POST /user/non-customer` creates `CATALOG_MANAGER`, `INVENTORY_WORKER` or `ADMIN` users, so any staff role can be created on demand.
- [x] Seed credentials come from configuration (`auth.admin.email`, `auth.admin.password`, i.e. `ADMIN_EMAIL` / `ADMIN_PASSWORD` env variables, with a local example in `.env.example`); the password is plain text in configuration only and is stored BCrypt-hashed.
- [x] Seeding is performed by an `ApplicationRunner` (`AdminSeeder`) and is idempotent: restarting the service does not create duplicates or fail on the unique email constraint.
- [x] A seeded admin can log in through `POST /auth/login` and the token carries the correct `role`.

## Technical Notes / Constraints
- **Endpoint:** `POST /user/non-customer`, body `{ email, password, role }`, returns `201 Created { userId }`. The request reuses the registration validation rules for `email` and `password`; `role` is required.
- **Authorization:** only callers with `X-User-Role: ADMIN` may use it; otherwise `403 Forbidden`. The header is set by the API Gateway after it validates the JWT. This service does not validate tokens, so it must not be reachable except through the gateway, and the gateway must strip any client-supplied `X-User-Role`. The check is a route rule `hasRole("ADMIN")` in the security filter chain (see [task 08](08-trusted-header-authentication.md)); callers without any valid user headers get `401`.
- **Role rules:** `CUSTOMER` is rejected with `400` (field error on `role`); customers can only come from public registration. `ADMIN` can be assigned, so an admin can create further admins. An unknown role value is `400`.
- **Seeder:** `AdminSeeder` runs at startup and creates the admin from configuration. If the email already exists it logs and skips, so restarts and multiple instances are safe.
- **Spec:** the new endpoint extends the service spec. The spec still needs a new `EP-AUTH-03` entry and the `X-User-Role` trust note.
- Registration (EP-AUTH-01) must still never accept a `role` from the client.
