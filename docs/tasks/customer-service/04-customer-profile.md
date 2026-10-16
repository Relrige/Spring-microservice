---
status: DONE
service: customer-service
---
# Customer Profile: Entity and Endpoints (EP-CUST-01, EP-CUST-02)

## Context
`CustomerProfile` holds the customer's name, phone and default delivery address. It is created lazily: a customer who has never saved a profile has no row, and reading the profile then returns empty fields rather than an error, so the client can show an empty form. The profile is independent of the cart.
*References:* [Customer Spec — EP-CUST-01, EP-CUST-02](../../design/services-specs/customer-service-spec.md), [Domain Entities — CustomerProfile](../../design/entities/entities.md), [API Contracts — Customer Service](../../design/services-requirements/api-contracts.md)

## Acceptance Criteria
- [x] Flyway migration creates `customer_profiles`: `customer_id` (UUID PK), `first_name`, `last_name`, `phone`, `default_address`, `created_at`, `updated_at`, with length limits and `NOT NULL` on the required fields.
- [x] `CustomerProfile` entity (`@Getter`, protected no-arg constructor, factory method, no setters; an `update(...)` method for changes). Timestamps via Hibernate `@CreationTimestamp` / `@UpdateTimestamp`.
- [x] `GET /customers/me/profile` → `200 OK {firstName, lastName, phone, defaultAddress}`; with no row → `200 OK` with all fields `null`. Reading never creates a row.
- [x] `PUT /customers/me/profile` upserts (insert if missing, otherwise update) and returns `200 OK` with the saved profile.
- [x] Validation: `firstName`, `lastName` non-blank; `phone` matches the agreed format; `defaultAddress` optional. Violations → `400` with `errors`.
- [x] Tests: a Testcontainers repository test (schema `validate`); WebMvc or integration tests for an empty profile, insert, update, and each validation error.

## Technical Notes / Constraints
- **Decide and record:** the phone format (the spec leaves the regex open). A simple choice is E.164, `^\+[1-9]\d{7,14}$`.
- **Decide and record:** whether `defaultAddress` is required on `PUT`. The spec lists only `firstName`, `lastName` and `phone` as validated.
- Two parallel first `PUT`s can race on the primary key. Pick a handling (catch the duplicate-key error and retry as an update, or a native `INSERT … ON CONFLICT DO UPDATE`) and record it. Unlike the cart, the profile has no invariants spanning rows, so a lock is not needed.

## Implementation Notes (2026-10-16)
- **Schema (`V1__create_customer_profiles_table.sql`):** `customer_profiles` with `customer_id` PK, `first_name`/`last_name` `VARCHAR(100) NOT NULL`, `phone VARCHAR(16) NOT NULL`, `default_address VARCHAR(500)` (nullable), `created_at`/`updated_at` `timestamptz`.
- **Decision — phone format:** E.164, `^\+[1-9]\d{7,14}$` (a plus, then 8–15 digits, no spaces). The phone is **required**, because the profile exists to pre-fill delivery details.
- **Decision — `defaultAddress` is optional** (free text, at most 500 characters), as the spec validates only names and phone.
- **Decision — concurrent first saves:** a single native `INSERT … ON CONFLICT (customer_id) DO UPDATE` (`CustomerProfileRepository.upsert`). It is atomic, so parallel first `PUT`s end as one row (the last one wins) with no duplicate-key error and no retry code. The statement also sets the timestamps (`created_at` kept on update). Because of that, `CustomerProfile` is a **read-only** JPA entity (getters, no factory or `update` method). The task's "factory method and `update(...)`" criterion is replaced by the upsert.
- **`@Transactional` on `upsert`:** a `@Modifying` query needs a transaction; with the annotation on the repository method it joins the service's transaction or runs in its own (the repository test calls it directly).
- **Service:** `getProfile` never creates a row (`ProfileResponse.empty()` with all fields `null`); `saveProfile` upserts and returns the stored row.
- **`RouteSecurityRulesTest`** (copied from Delivery, without the internal caller) now runs over the real controllers, as task 03 deferred.
- **Verified (2026-10-16):** `CustomerProfileRepositoryTest` (insert, update keeps `created_at`), `CustomerProfileIntegrationTest` (7 cases: empty profile without a row, insert and update, isolation between customers, blank names, invalid and missing phone, too-long fields, 8 parallel first saves → one row, all `200`), `RouteSecurityRulesTest`, and the whole module's tests pass.
