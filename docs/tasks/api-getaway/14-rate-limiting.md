---
status: DONE
service: api-gateway
---
# Rate Limiting at the Gateway

## Context
The gateway is the single entry point, and the spec deferred rate limiting ("especially for `/auth/login` and registration"). The course requires a configured rate limiter that answers with an error when a client exceeds its limit (Topic 8). Login and registration need the strictest limit, because they are where passwords can be guessed.
*References:* [API Gateway Spec](../../design/services-specs/api-gateway-spec.md), [security model](../../design/services-requirements/security-model.md)

## Acceptance Criteria
- [x] Every routed request is counted against a limit for its client IP address before the JWT is checked; a request over the limit never reaches a service.
- [x] Login (`auth-login`) and registration (`auth-register`) have a strict limit (default 5 requests per minute per IP); every other route has a general limit (default 50 requests per second per IP).
- [x] Over the limit: `429 Too Many Requests` as a ProblemDetail of type `https://voltstore.com/errors/too-many-requests` with a `Retry-After` header in seconds.
- [x] Limits and an on/off switch are configuration (`gateway.rate-limit.*`, overridable by environment variables); the k6 load tests document how to turn the limiter off.
- [x] Rejections are counted in a Micrometer counter `gateway.rate.limit.rejected` with the tag `limit` (`auth` or `general`).
- [x] Integration tests cover both limits, the 429 response, that the upstream is not called, and the counter.

## Decisions (2026-11-22)
1. **Resilience4j `RateLimiter`**, the library the services already use for their client calls (Resilience4j 2.4.0); only its core rate limiter module is added. Bucket4j was the alternative; it would be a new library and needs a cache provider.
2. **One limit per client IP address**, checked before the JWT: a flood never costs a token verification, and the attack that matters most (guessing passwords on login) has no user yet. In Kubernetes, Spring Boot detects the platform and takes the client address from the Ingress's `X-Forwarded-For`.
3. **Two limits:** a strict one for `auth-login` and `auth-register` (5 per minute), a general one for every other route (50 per second). Both are in `application.yml`.
4. **429 as the gateway's other errors:** ProblemDetail through `GatewayProblems`, fixed detail text, `Retry-After` in seconds.
5. **`GATEWAY_RATE_LIMIT_ENABLED=false`** turns the limiter off, for the k6 load tests that send many requests from one machine.
6. **Micrometer counter** for rejections, scraped by Prometheus with the gateway's other metrics.
7. **In memory, per gateway instance:** one limiter per client address, created on first use. Correct with the one gateway replica; several replicas would need a shared store.
8. **Branch `api-gateway/rate-limiter`** from `main`, this task file.

## Implementation Notes (2026-11-22)
- **`ratelimit/RateLimitFilter`:** looks up the route id, picks the strict `auth` rule for the routes in `gateway.rate-limit.auth-routes` and the `general` rule otherwise, and takes one permission from a Resilience4j `RateLimiter` named `<rule>:<client address>` (`timeoutDuration` 0, so a request never waits). Without a permission it counts the rejection and answers `GatewayProblems.tooManyRequests(period)`. `enabled: false` passes every request through.
- **`ratelimit/RateLimitProperties`:** `gateway.rate-limit.enabled`, `.auth-routes`, `.auth.requests`/`.period`, `.general.requests`/`.period`; defaults in `application.yml` (5 per `1m`, 50 per `1s`).
- **`errors/GatewayProblems.tooManyRequests`:** `429`, type `https://voltstore.com/errors/too-many-requests`, fixed detail, `Retry-After` = the rule's period in whole seconds (at most one period passes before the client's permissions are refreshed).
- **Pipeline:** `TrustBoundaryFilterFunctions.trustBoundary()`, the filter every route already has, runs the rate limit first and the trust boundary second.
- **Metric:** `gateway.rate.limit.rejected{limit="auth"|"general"}` (Prometheus: `gateway_rate_limit_rejected_total`).
- **Switch off:** `GATEWAY_RATE_LIMIT_ENABLED=false`, documented in `api-gateway/.env.example`, `load-tests/README.md` (k6) and `frontend/README.md` (live Playwright journeys). The test `application.properties` turns the limiter off; `RateLimitTest` turns it on with small limits.
- **Frontend seed script:** `frontend/scripts/seed.ts` now waits out a `429` like a `503` (honouring `Retry-After`, capped at 60 s instead of 15 s). One seed run makes exactly 5 calls on the login and registration routes, so a second run within a minute would otherwise fail.
- **Verified:** `RateLimitTest` (5 tests: login over the limit is `429` with `Retry-After: 60` and a ProblemDetail and never reaches Auth; registration shares the login limit; other routes have their own limit while login still works; an invalid token over the limit is `429`, not `401`; the counter per limit). Full gateway suite `mvnw test`: 393 tests, all green. Frontend `npm run check` passes. Docker Compose (gateway and Auth only): five wrong-password logins answered `401` from Auth, the sixth and seventh `429` with `Retry-After: 60` and the ProblemDetail body; `/catalog/products` was not limited.

## Deviations from spec / task
- **The limiter is the first step of the gateway pipeline, not a servlet filter** (decision 7 said "a servlet filter before routing"). A servlet filter runs outside Spring MVC and would have had to write the ProblemDetail JSON by hand; as a pipeline step, the `429` is a `ServerResponse` built like the gateway's `401` and `403`. Every route runs the pipeline (`RouteAccessPolicy` requires it), so coverage is the same; an unrouted path is a `404` either way.
- **The frontend's login page shows its generic error text for a `429`.** The API answer is clear; a dedicated message ("Too many attempts, try again in a minute") is app behaviour and goes through a frontend OpenSpec change.
- **The seed script change is in `frontend/`**, outside this service: without it, `npm run seed` could fail against a stack with the new limit. It is a separate frontend commit.

*Spec sync (2026-11-22):* now part of the API Gateway spec (Request Pipeline, Implementation, Errors, Configuration; removed from Open Issues and Deferred).
