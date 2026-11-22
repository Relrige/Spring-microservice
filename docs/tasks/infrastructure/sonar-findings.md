---
status: DONE
service: infrastructure
---
# SonarQube Configuration and Backend Findings

## Context
A SonarQube scan of the repository reported about 700 issues. Most come from team conventions (snake_case test names), generated code (OpenAPI types in the frontend) and the local minikube setup, not from defects. This task adds a scanner configuration that turns those off, then fixes the remaining findings in the eight backend services and the API Gateway.

The frontend findings are out of scope: `frontend/` follows its own [`AGENTS.md`](../../../frontend/AGENTS.md).

No REST contract, event, or architectural change.

*References:* [`sonar-project.properties`](../../../sonar-project.properties)

## Acceptance Criteria
- [x] `sonar-project.properties` at the repository root excludes build output, generated code and the rules that conflict with team conventions.
- [x] Java findings of the local scan ("Sonar way comprehensive" profile) are fixed in every service, except the ones listed as won't-fix below.
- [x] Every `catch (InterruptedException ...)` in main code restores the interrupt flag or rethrows.
- [x] Every service's full test suite passes.
- [x] A rescan shows the Java issue count went down.

## Decisions (2026-11-22)
1. **Cross-cutting scope.** One branch `infrastructure/sonar-fixes` and this one task file for all services, one commit per service. No spec sync is needed because nothing visible outside a service changes.
2. **Uncommitted local work stays out.** Files with uncommitted changes from before this task (`AdminSeeder`, `CancellationSaga`, `AdjustStockIntegrationTest`, the deleted `*ApplicationTests`, the frontend, `start-minikube.*`) are neither edited nor committed.
3. **False positives are left in the code** (won't fix, with the reason):
   - `java:S1450` on JPA entity fields (`publishedAt`, `ttn`, `paidAt`, ...): they are persisted columns; turning them into local variables would drop them from the mapping.
   - `java:S1172` on Resilience4j fallback methods in Order's client adapters: a fallback must repeat the signature of the method it protects.
   - `java:S9438`: a JPA entity field cannot be `final`.
   - `java:S2143`: Nimbus JOSE takes `java.util.Date` for JWT claims.
   - `java:S2187`: `InventoryGrpcConnectionTest` keeps its tests in nested classes.
   - `java:S107`, `java:S1301`: an 8-parameter method and a `switch` statement are style preferences.
4. **Cognitive complexity (`java:S3776`) is not refactored** in Inventory's `StockGrpcService` and Order's `OrphanReservationJob`: both sit on the reservation and saga paths, and the regression risk is larger than the readability gain.
5. **`java:S6213`:** variables named `record` (a restricted identifier since Java 16) are renamed.
6. **`Thread.sleep` in tests (`java:S2925`)** stays; a one-line comment explains intentional delays. Awaitility is only a dependency in Catalog, and adding it elsewhere is a new library.
7. **Findings of the original list that the local scan did not report:** only the real ones are fixed. The `java:S2259` NPE warnings are false positives (the code already checks for null). `catch (InterruptedException ...)` blocks in main code are checked and fixed.

## Implementation Notes (2026-11-22)
- **`sonar-project.properties`:** sources and tests are taken from the whole repository. Excluded: `target/`, `node_modules/`, `dist/`, `build/`, `*.zip`, `frontend/src/api/generated/`, `docs/reports/`. Rules turned off by path: `java:S100`, `java:S5778`, `java:S5838` in `**/src/test/**`; `javascript:S2245` in `load-tests/`; `kubernetes:S6596`, `S6870`, `S6897` in `k8s/`.
- **Renames (`java:S6213`, 78):** Kafka `record` variables became `consumerRecord`, `producerRecord` or `failedRecord`. `NotificationMetrics.record(...)` became `recordAttempt(...)` (one caller, `NotificationSenderJob`). The test helper `record(...)` in Inventory's `EventTypeFilterTest` became `consumerRecord(...)`.
- **Constants (`java:S1192`):** extracted in the exception handlers, `SecurityConfig`s, `StockController`, `StockItemSpecifications`, `StockGrpcService`, `ProductService`, `InventoryGrpcAdapter` and `EmailRenderer`. Every value is unchanged, so problem details, JSON fields and email subjects stay the same.
- **Comments:** every empty method and empty block got a one-line comment saying why it is empty. Each kept `Thread.sleep` in tests got a comment saying why the delay is needed.
- **`transient`:** the item lists in `CartRuleViolationException`, `InsufficientStockForReservationException`, `IneligibleItemsException` and `InsufficientStockException`. Nothing Java-serializes these exceptions.
- **Small rewrites:** `ProductDataBackfill.fillMissingProductData` (Inventory) has one loop condition instead of three `break`s, and the Catalog call moved into `findProducts(ids)`. It still stops on an empty batch, a short batch, a Catalog outage, or `MAX_BATCHES`. `SmtpEmailSender.collect` (Notification) uses a `while` loop instead of assigning the `for` counter. `ProductIntegrationTest` (Catalog) reads `productId` with JsonPath instead of a slow regex.
- **`InterruptedException`:** every catch in main code already re-interrupts (Catalog, Delivery, Inventory, Order and Payment `OutboxRelay`s and mock gateways, Order's `InventoryGrpcAdapter`). Only the test `SmtpEmailSenderMailpitTest` needed the interrupt call.
- **Result:** the local scan ("Sonar way comprehensive") went from 352 to 195 open issues; Java from 199 to 42. The 42 left are the won't-fix rules of decisions 3 and 4 (33), the kept sleeps of decision 6 (6), and three false positives: `java:S125` in Delivery's `SecurityConfig` (prose about role rules), `java:S4030` in Inventory's `ConsumedEventsIntegrationTest` (the list is read in the following `await()`), and `java:S2160` on Auth's `CreateNonCustomerUserRequest` (Lombok's `@EqualsAndHashCode(callSuper = true)` generates `equals`).
- **Verified:** each service's full suite via `mvnw test` (Testcontainers): API Gateway 387, Catalog 291, Auth 97, Payment 120, Customer 131, Delivery 168, Notification 86, Inventory 407, Order 386. Two failures, both unrelated to this change (see Deviations).

## Deviations from spec / task
- **Two local test failures are left as they are.** `CartRepositoryTest.changes_moveUpdatedAtForward` (Customer) compares a timestamp from the database's `now()` with one from the JVM clock; the Docker VM clock ran about 0.3 s ahead, so the later value looked earlier. `EventReaderTest.brokenConstraints_areListedWithoutValues` (Notification) expects English Bean Validation messages and gets Ukrainian ones on a machine with a Ukrainian locale; it passes with `-Duser.language=en`. Neither file nor the code under test was changed here, and CI is not affected. Fixing them (one clock source, a fixed test locale) is a separate task.
- **`java:S125` findings were fixed by punctuation, not by deleting.** Several "commented-out code" findings were prose comments that end a line with `;`. Changing the `;` to `.` keeps the documentation and stops the false match. Alternative: mark them as false positives in Sonar, which only works on one server.
