---
status: DONE
service: order-service
---
# Declarative HTTP Client for Catalog Service

## Context
The Order Service must synchronously fetch product data (e.g., during checkout validation and price snapshotting) from the Catalog Service. 
*References:* [SCN-C14 Checkout Soft Check](../../design/scenarios/customer-scenarios.md), [API Contracts](../../design/services-requirements/api-contracts.md)

## Acceptance Criteria
- [x] Create `ProductDto` as a Java class annotated with `@JsonIgnoreProperties(ignoreUnknown = true)` for Tolerant Reader.
- [x] Create `CatalogClient` interface.
- [x] Add `@GetExchange("/products/batch")` to the `CatalogClient` method.
- [x] Implement `ClientHttpRequestInterceptor` to automatically append `X-Correlation-Id` header to outgoing requests.
- [x] Create `@Configuration` class to configure `JdkClientHttpRequestFactory` with 2s connect timeout and 3s read timeout.
- [x] Build `RestClient` integrating the factory and the interceptor.
- [x] Create the `CatalogClient` Spring `@Bean` using `HttpServiceProxyFactory`.
- [x] Inject `CatalogClient` into an Order Service controller and verify successful retrieval.
- [x] Set up WireMock to simulate the external Catalog Service without deploying external infrastructure.
- [x] Test timeout behavior by adding an artificial delay (>3s) to WireMock and asserting that a `ResourceAccessException` is thrown.

## Technical Notes / Constraints
- **Must use** Spring 6 declarative `@HttpExchange` interfaces and `RestClient`. 
- **Do not use** the legacy `RestTemplate`.
- The target service should be modeled using a local mock controller inside this project to avoid needing to deploy the full infrastructure for this test.
