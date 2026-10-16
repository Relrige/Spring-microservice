---
status: TODO
service: customer-service
---
# Customer Profile: Entity and Endpoints (EP-CUST-01, EP-CUST-02)

## Context
`CustomerProfile` holds the customer's name, phone and default delivery address. It is created lazily: a customer who has never saved a profile has no row, and reading the profile then returns empty fields rather than an error, so the client can show an empty form. The profile is independent of the cart.
*References:* [Customer Spec — EP-CUST-01, EP-CUST-02](../../design/services-specs/customer-service-spec.md), [Domain Entities — CustomerProfile](../../design/entities/entities.md), [API Contracts — Customer Service](../../design/services-requirements/api-contracts.md)

## Acceptance Criteria
- [ ] Flyway migration creates `customer_profiles`: `customer_id` (UUID PK), `first_name`, `last_name`, `phone`, `default_address`, `created_at`, `updated_at`, with length limits and `NOT NULL` on the required fields.
- [ ] `CustomerProfile` entity (`@Getter`, protected no-arg constructor, factory method, no setters; an `update(...)` method for changes). Timestamps via Hibernate `@CreationTimestamp` / `@UpdateTimestamp`.
- [ ] `GET /customers/me/profile` → `200 OK {firstName, lastName, phone, defaultAddress}`; with no row → `200 OK` with all fields `null`. Reading never creates a row.
- [ ] `PUT /customers/me/profile` upserts (insert if missing, otherwise update) and returns `200 OK` with the saved profile.
- [ ] Validation: `firstName`, `lastName` non-blank; `phone` matches the agreed format; `defaultAddress` optional. Violations → `400` with `errors`.
- [ ] Tests: a Testcontainers repository test (schema `validate`); WebMvc or integration tests for an empty profile, insert, update, and each validation error.

## Technical Notes / Constraints
- **Decide and record:** the phone format (the spec leaves the regex open). A simple choice is E.164, `^\+[1-9]\d{7,14}$`.
- **Decide and record:** whether `defaultAddress` is required on `PUT`. The spec lists only `firstName`, `lastName` and `phone` as validated.
- Two parallel first `PUT`s can race on the primary key. Pick a handling (catch the duplicate-key error and retry as an update, or a native `INSERT … ON CONFLICT DO UPDATE`) and record it. Unlike the cart, the profile has no invariants spanning rows, so a lock is not needed.
