---
status: TODO
service: order-service
---
# Internal Token on Outgoing Calls and Security Setup (Follow-up)

## Context
Order Service calls `Internal` endpoints of other services (catalog `batch-get`, inventory reservations, payment charge/refund, delivery shipments). These calls bypass the gateway and carry no user. After the auth-service pilot, each service authenticates callers with trusted headers and a shared internal token. This task covers the sender side and the receiver-side copy for order-service. Catalog and delivery services, which also make internal calls, follow the same steps in their own tasks.

*References:* [Trusted-Header Authentication (pilot)](../auth-service/08-trusted-header-authentication.md), [Declarative HTTP Client](declarative-http-client.md), [API Contracts](../../design/services-requirements/api-contracts.md)

## Acceptance Criteria
- [ ] Add `InternalTokenInterceptor` (`ClientHttpRequestInterceptor`) that adds `X-Internal-Token` to every outgoing service-to-service request. Register it on the `RestClient` next to the existing correlation ID interceptor.
- [ ] The token comes from configuration (`INTERNAL_TOKEN` env variable), never hard-coded or logged.
- [ ] The interceptor is not applied to calls leaving the system (it must only be on `RestClient`s that target internal services).
- [ ] Copy the `security` package from auth-service (trusted-header filter, internal-token filter, `AuthenticatedUser`, security chain, error handlers) and adapt the `permitAll` and role rules (`requestMatchers(...).hasRole(...)`) to order-service endpoints from the API contracts.
- [ ] Internal endpoints exposed by order-service (if any) require `hasRole("SERVICE")` via route rules; customer endpoints use `hasRole(...)` rules or are public as defined in the contracts.
- [ ] Tests: a WireMock-based test asserts the `X-Internal-Token` header is sent; security slice tests cover `401`/`403`/success for each protected endpoint.

## Technical Notes / Constraints
- The same `INTERNAL_TOKEN` value must be configured in every service (via a Kubernetes Secret).
- The token authenticates the calling system, not a user. Do not forward `X-User-Id`/`X-User-Role` on internal calls unless a concrete need appears.
- Keep the copied package identical to the pilot where possible so later fixes can be applied to every copy.
