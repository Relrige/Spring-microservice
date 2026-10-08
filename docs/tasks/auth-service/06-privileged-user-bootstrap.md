---
status: TODO
service: auth-service
---
# Bootstrap Staff and Admin Accounts

## Context
The Auth Service is the identity master for `CATALOG_MANAGER`, `INVENTORY_WORKER`, and `ADMIN`, but the spec defines only customer registration. Without some way to create staff accounts, those roles can never log in. This gap needs an explicit, minimal answer.
*References:* [Auth Service Spec — Service Overview (Roles issued)](../../design/services-specs/auth-service-spec.md)

## Acceptance Criteria
- [ ] Decide and document how non-customer accounts are created. Options to evaluate:
  1. Seed via Flyway migration or a startup `ApplicationRunner` driven by configuration (simplest; fits a study project).
  2. An admin-only `POST /auth/users` endpoint (requires trusting the gateway's `X-User-Role` header; extends the spec).
- [ ] Implement the chosen option so that at least one user per staff role exists in local/dev environments.
- [ ] Seed credentials come from configuration/env variables or an example config file, not hard-coded production secrets; passwords are stored BCrypt-hashed.
- [ ] Seeding is idempotent: restarting the service does not create duplicates or fail on the unique email constraint.
- [ ] A seeded staff user can log in through `POST /auth/login` and the token carries the correct `role`.

## Technical Notes / Constraints
- If option 2 is chosen, update the service spec first (new endpoint), since the spec is the source of truth.
- Registration (EP-AUTH-01) must still never accept a `role` from the client.
