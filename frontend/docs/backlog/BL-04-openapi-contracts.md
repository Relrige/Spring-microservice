---
id: BL-04
title: OpenAPI description for every routed service
status: Open
importance: Important
services: [auth-service, catalog-service, customer-service, order-service, inventory-service, delivery-service]
blocks: —
created: 2026-10-20
---

# BL-04: OpenAPI description for every routed service

## Problem

The API contracts exist only as Markdown specs. The frontend writes its TypeScript request and response types by hand from them, so a backend change can silently break the frontend, and nothing checks that the specs and the running services agree.

## Current behaviour

- No service depends on springdoc or publishes `/v3/api-docs` (checked: no `springdoc` in any `pom.xml`).
- Contracts: `../../../docs/design/services-requirements/api-contracts.md` and the service specs.
- All services use Spring Boot 4.1.1.

## Proposed change

1. Add springdoc-openapi (the release line that supports Spring Boot 4.1) to every service with gateway-routed endpoints: Auth, Catalog, Customer, Order, Inventory, Delivery. Payment and Notification are optional (no routed endpoints).
2. Expose the document on the **management port** (or another non-routed path), so it stays off the public gateway, consistent with the trust boundary. The frontend build reads it from the services' host ports in Compose.
3. Describe what the frontend relies on: request/response schemas, status codes per operation, and the problem-details extensions (`errors`, `violation`, `reason`, `ineligibleItems`, `insufficientItems`, `maxTotal`) as named schemas.
4. Keep internal endpoints in a separate group (or tagged), so it is clear which operations are public.

## Requirements

- [ ] Each listed service serves an OpenAPI 3.1 document that is not reachable through the gateway
- [ ] Every routed operation lists its success and error statuses; error bodies reference shared problem-details schemas
- [ ] A short doc says where each document is served in Compose and Kubernetes
- [ ] Optional: a test per service fails when the document changes unexpectedly (snapshot)

## Frontend impact

- Until done: hand-written types in `src/api/<service>.ts`, each citing its spec section; live browser tests catch drift.
- After: the frontend generates its API types from the documents (`openapi-typescript`) and drops the hand-written ones.

## Notes

- The paging envelope ([BL-05](BL-05-uniform-paging-envelope.md)) should be settled first or together, so the generated types are uniform.
