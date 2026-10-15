---
status: DONE
service: api-gateway
---
# Gateway Error Responses (Problem Details)

## Context
Errors produced by the gateway itself must look like the services' errors, so clients handle one format. Errors returned by upstreams are passed through untouched.
*References:* [API Gateway Spec — Request Pipeline](../../design/services-specs/api-gateway-spec.md#request-pipeline), [Auth Service error handling](../auth-service/05-error-handling-and-validation.md)

## Acceptance Criteria
- [x] Gateway-originated errors are RFC 9457 `application/problem+json` with `type`, `title`, `status`, `detail`, `timestamp` and `service: api-gateway`.
- [x] `401` has the fixed detail "Authentication required" and never reveals why the token was rejected; `403` has the detail "Access denied".
- [x] No matching route → `404` in the same format (also for an unlisted method; never `405`).
- [x] Upstream unavailable (connection refused, DNS failure, e.g. customer-service not deployed) → `502` or `503` with a generic detail; upstream timeout → `504`. No internal hostnames or stack traces in the body.
- [x] Unexpected gateway errors → `500` with the generic detail "An unexpected error occurred."; the cause is logged server-side.
- [x] Upstream responses (including their `4xx`/`5xx`, and Auth Service's `401`/`409`) are passed through unchanged.
- [x] Connect and read timeouts for upstream calls are configured.
- [x] Tests cover each error type, including that an upstream `409` body reaches the client unchanged.

## Technical Notes / Constraints
- The `401`/`403` come from the pipeline filters ([task 04](04-request-pipeline-filters.md)), so use one shared error writer for filters and exception handling. Reuse the approach of auth-service (Jackson 3 `tools.jackson`, or Spring's message converters).
- Check what the framework returns by default for an unmatched path and make it conform.
- Timeout values are configuration, not constants; choose and document defaults.

## Progress Notes (2026-10-14)
- `GatewayProblems` builds every gateway error (`type` `https://voltstore.com/errors/<kind>`, `title`, `status`, `detail`, `timestamp`, `service: api-gateway`); used by the pipeline filter (401/403) and by `GatewayExceptionHandler` (a `ResponseEntityExceptionHandler`, like auth-service's).
- No route: Spring raises `NoResourceFoundException`; `handleExceptionInternal` maps every 404 (and a 405, should one ever appear) to `404` "No route matches the request.".
- Upstream failures arrive as `ResourceAccessException`: a read timeout (`HttpTimeoutException`/`SocketTimeoutException` in the cause chain) → `504`; anything else (connection refused, unknown host, connect timeout) → `502`. The exception message names the upstream host, so it is only logged (warn).
- **Timeouts (decision):** Boot's HTTP client properties, which Gateway MVC's `RestClient` uses: `spring.http.clients.connect-timeout: 2s`, `spring.http.clients.read-timeout: 15s` (room for Catalog's activation call to Inventory with retries). Overridable by `SPRING_HTTP_CLIENTS_*` variables.
- Tests: `ErrorResponsesTest` (404/401/403 format; upstream 409/401/400/500/201 passed through byte for byte; generic 500) and `UpstreamFailureTest` (closed port → 502, `.invalid` host → 502, 3 s upstream with 500 ms read timeout → 504).
