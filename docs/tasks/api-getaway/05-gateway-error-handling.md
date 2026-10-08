---
status: TODO
service: api-gateway
---
# Gateway Error Responses (Problem Details)

## Context
Errors produced by the gateway itself must look like the services' errors, so clients handle one format. Errors returned by upstreams are passed through untouched.
*References:* [API Gateway Spec — Request Pipeline](../../design/services-specs/api-gateway-spec.md#request-pipeline), [Auth Service error handling](../auth-service/05-error-handling-and-validation.md)

## Acceptance Criteria
- [ ] Gateway-originated errors are RFC 9457 `application/problem+json` with `type`, `title`, `status`, `detail`, `timestamp` and `service: api-gateway`.
- [ ] `401` has the fixed detail "Authentication required" and never reveals why the token was rejected; `403` has the detail "Access denied".
- [ ] No matching route → `404` in the same format (also for an unlisted method; never `405`).
- [ ] Upstream unavailable (connection refused, DNS failure, e.g. customer-service not deployed) → `502` or `503` with a generic detail; upstream timeout → `504`. No internal hostnames or stack traces in the body.
- [ ] Unexpected gateway errors → `500` with the generic detail "An unexpected error occurred."; the cause is logged server-side.
- [ ] Upstream responses (including their `4xx`/`5xx`, and Auth Service's `401`/`409`) are passed through unchanged.
- [ ] Connect and read timeouts for upstream calls are configured.
- [ ] Tests cover each error type, including that an upstream `409` body reaches the client unchanged.

## Technical Notes / Constraints
- The `401`/`403` come from the pipeline filters ([task 04](04-request-pipeline-filters.md)), so use one shared error writer for filters and exception handling. Reuse the approach of auth-service (Jackson 3 `tools.jackson`, or Spring's message converters).
- Check what the framework returns by default for an unmatched path and make it conform.
- Timeout values are configuration, not constants; choose and document defaults.
