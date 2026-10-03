---
status: TODO
service: order-service
---
# Declarative HTTP Client for Catalog Service

## Context
The Order Service must synchronously fetch product data (e.g., during checkout validation and price snapshotting) from the Catalog Service. 
*References:* [SCN-C14 Checkout Soft Check](../../design/scenarios/customer-scenarios.md), [API Contracts](../../design/services-requirements/api-contracts.md)

## Acceptance Criteria
- [ ] Create `ProductDto` as a Java `record` annotated with `@JsonIgnoreProperties(ignoreUnknown = true)` for Tolerant Reader.
- [ ] Create `CatalogClient` interface.
- [ ] Add `@GetExchange("/catalog/products/{id}")` to the `CatalogClient` method.
- [ ] Implement `ClientHttpRequestInterceptor` to automatically append `X-Correlation-Id` header to outgoing requests.
- [ ] Create `@Configuration` class to configure `JdkClientHttpRequestFactory` with 2s connect timeout and 3s read timeout.
- [ ] Build `RestClient` integrating the factory and the interceptor.
- [ ] Create the `CatalogClient` Spring `@Bean` using `HttpServiceProxyFactory`.
- [ ] Inject `CatalogClient` into an Order Service controller and verify successful retrieval.
- [ ] Create a local `MockCatalogController` (within the Order Service test/mock package) exposing the `/catalog/products/{id}` endpoint.
- [ ] Test timeout behavior by adding an artificial delay (>3s) to the mock controller and asserting that a `ResourceAccessException` is thrown.

## Technical Notes / Constraints
- **Must use** Spring 6 declarative `@HttpExchange` interfaces and `RestClient`. 
- **Do not use** the legacy `RestTemplate`.
- The target service should be modeled using a local mock controller inside this project to avoid needing to deploy the full infrastructure for this test.
