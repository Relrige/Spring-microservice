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

## Decisions, round 2 (2026-11-22)
A second pass fixes the rules the team listed after the first scan, "only where the fix is actually correct". Each finding was read in context before changing it.
8. **Same branch and PR.** Round 2 goes on `infrastructure/sonar-fixes` (PR #46), one commit per service, plus separate commits for the scanner settings, k8s, the load tests and the frontend.
9. **The scanner analyses Java 25 with the real classpath.** `sonar.java.source=25` turns on the rules for modern syntax (unnamed variables, record patterns, `Math.clamp`). `sonar.java.libraries` points at the jars from `mvnw dependency:copy-dependencies -DincludeScope=test`; without them the type-aware rules (null checks, always-true conditions) cannot run.
10. **`java:S5778` and `java:S5838` are no longer turned off in tests**, and their findings are fixed. `java:S100` stays off (snake_case test names).
11. **k8s: every container gets `ephemeral-storage` requests and limits** (`kubernetes:S6897`, `S6870` are no longer turned off). Default 128Mi request / 1Gi limit; Kafka 2Gi; Prometheus, Loki and Tempo 512Mi / 4Gi because they keep their data in `emptyDir`. `kubernetes:S6596` (`:latest` image tags) stays off for the local minikube images.
12. **Frontend findings are fixed under [`frontend/AGENTS.md`](../../../frontend/AGENTS.md)** in a frontend-only commit, without an OpenSpec change (no behaviour changes).
13. **Kept on purpose, with the reason:**
    - `java:S1172`: Resilience4j fallbacks must repeat the protected method's parameters; the `boolean ignored` parameter of `InvalidOrderStateException` keeps two constructors apart.
    - `java:S2143`: Nimbus JOSE takes `java.util.Date`.
    - `java:S2925`: the test sleeps are intentional (decision 6).
    - `javascript:S2245`: the k6 load tests pick random products; stays off for `load-tests/`.
    - `java:S2583` on line 52 of Order's `IdempotencyKeys`: `orElse(null)` really can return null.
    - `java:S2095` on test executors: they are stopped with `shutdownNow()` in `finally`. Try-with-resources would call `close()`, which waits for running tasks and can hang a failing test.
    - Frontend: the `<fieldset>` suggestion in one design mockup (it would change the layout; done in round 3 with the default styles reset). The path-template regex in `contract.ts` was kept here and fixed later: `[^{}]+` instead of `[^}]+` makes an unclosed `{` stop at the next one, so the time is linear on any input (40,000 unclosed braces: 402 ms before, under 1 ms after).
    - `java:S4502` (CSRF protection off) in the services' `SecurityConfig`s: the APIs are stateless, the caller comes from gateway headers or a bearer JWT and no cookie authenticates a request, so there is nothing for a cross-site request to ride on.
    - `java:S6809` in Customer's `CartService`: the public `@Transactional` methods call private helpers on `this`; the helpers run inside the caller's transaction, which is the intent. Moving them to another bean would only satisfy the rule.
    - `java:S1168` in Inventory's `ProductDataBackfill.findProducts`: `null` means "Catalog is unavailable, stop this run" and differs from an empty batch.
    - `java:S107`, `java:S3776`, `java:S125` on the same places as in decisions 3 and 4 and the round-1 notes.
14. **Rules missing from SonarQube Community** (`java:S2259`, `S2583`, `S2589`, `S2095` need the commercial engine) were checked by hand at every location from the team's list.

## Implementation Notes, round 2 (2026-11-22)
- **Java 22+ syntax:** unused catch and lambda variables became `_` (`java:S7467`); `instanceof`/`switch` on result records use record patterns, e.g. `case Approved(var gatewayTransactionId) ->` (`java:S6878`); Order's `CancellationProperties` uses `Math.clamp` (`java:S6885`, the bounds are always ordered).
- **Exceptions:** the `OutboxRelay.send` methods declare the three checked exceptions they can throw instead of `Exception` (`java:S112`); `throws` clauses that cannot happen in tests were removed (`java:S1130`).
- **Null and condition checks:** Order's `IdempotencyKeys.claim` compares the update count instead of an always-true check; `PaymentClientAdapter` and the Catalog, Customer and Delivery exception handlers read a nullable getter once into a local instead of calling it twice.
- **Tests:** `assertThrows` lambdas hold only the call under test (`java:S5778`); AssertJ uses the dedicated assertions (`hasSize`, `containsEntry`, `hasToString`, ...) and chains assertions on the same value (`java:S5838`, `S5853`); `allMatch` is preceded by a size check where the list must not be empty (`java:S5841`); Mockito calls with only `eq(...)` matchers pass the values directly (`java:S6068`); Payment's `ChargeIntegrationTest` reads `transactionId` with JsonPath instead of a regex (`java:S8786`).
- **Load tests:** the k6 default functions have names (`javascript:S7726`).
- **`.gitignore`:** ignores the scanner's `.sonar/` cache.
- **Verified:** each service's full suite via `mvnw test`: API Gateway 387, Auth 97, Payment 120, Catalog 291, Notification 86, Delivery 168, Inventory 407, Order 386 pass; Customer 130 of 131 (the known clock test, see Deviations). Frontend `npm run check` passes (429 tests). On minikube (`voltstore-np`) the changed manifests were applied, every Deployment, the Kafka StatefulSet and the OTel DaemonSet rolled out, all pods ran, and no pod was evicted.
- **Rescan leftovers:** the rescan after these commits found more unused variables of the same kind: `ignored` resources in try-with-resources and unused type-pattern variables in `switch` (`java:S1481`, 18), which became `_`. In the same pass: `failedAttempts - 1L` in `CancellationProperties` (`java:S2184`), method references in two tests (`java:S1612`), and a static `times` import (`java:S8924`).
- **Result:** with the same custom profile ("Sonar way comprehensive" plus the listed rules), the scan before round 2 had 316 open issues (Java 174, k8s 53, frontend and load tests 89). The rescan had 141 (Java 75, k8s 0, frontend and load tests 66). The leftover fixes above remove 29 more Java findings; the remaining Java ones are the kept findings of decision 13.

## Decisions, round 3 (2026-11-22)
A third list came from a newer analyzer than the local SonarQube Community. Its rules include k8s ConfigMap duplication, and it scanned files that `sonar-project.properties` excludes. That points to SonarCloud's automatic analysis, the IDE plugin, or a scan of the working tree, which also picked up the gitignored `frontend/playwright-report/`.
15. **`.sonarcloud.properties`** repeats the sources and exclusions for SonarCloud's automatic analysis, which does not read `sonar-project.properties`. Rule-by-path ignores (`java:S100` in tests, `javascript:S2245` in `load-tests/`, `kubernetes:S6596` in `k8s/`) cannot go in that file. They are set once in the SonarCloud project settings ("Ignore Issues on Multiple Criteria"). Both files now also exclude `frontend/public/mockServiceWorker.js` (generated by MSW) and Playwright output.
16. **The kept findings of decisions 3, 4, 6 and 13 stay**, together with the reasons given there. The same holds for `java:S2583` "always false" in `IdempotencyKeys` and `NotificationEntity.sentAt` (a mapped column). Also kept: the generated `paths`/`components`/`operations` type names in `frontend/src/api/generated/`, and the demo passwords in `frontend/src/fixtures/demo-data.ts` (documented demo accounts). Mark these as accepted in SonarCloud.
17. **k8s "duplicated environment variable":** each Postgres container loaded the whole service ConfigMap with `envFrom`, so it also received the application's settings (`JAVA_TOOL_OPTIONS`, service URLs, topics). Now it reads only `POSTGRES_DB` through `configMapKeyRef`. This could not be checked against the rule locally, because the rule is not in SonarQube Community.

18. **Kept false positives are suppressed in the code, with the reason next to them**, instead of only being accepted on one Sonar server: `@SuppressWarnings("java:S1172")` on the nine Resilience4j fallbacks in Order's client adapters (Resilience4j finds a fallback by the protected method's parameters) and on the flag constructor of `InvalidOrderStateException` (it keeps two `(UUID, OrderStatus, String)` constructors apart); `java:S2583` on `IdempotencyKeys.claim` (the key can be released between the claim and the read); `java:S1168` on `ProductDataBackfill.findProducts` (`null` means "Catalog unavailable, stop this run"; an empty list would make the run continue against the outage and log a false "unknown products" warning). In the k6 scripts, each `Math.random()` line carries `// NOSONAR(S2245)`: it only picks test products, quantities and the traffic mix, and nothing it produces is a secret. This replaces the `load-tests/**` ignore in `sonar-project.properties`. `java:S2143`: Nimbus JOSE 10.10 takes and returns JWT time claims only as `java.util.Date`, so all date logic (now, comparisons, token lifetime) now uses `Instant` and `Duration`, and `Date` remains only in one suppressed boundary per class: `HmacJwtVerifier.expirationOf`, `JwtTokenService.issueToken`, and a `jwtTime(Instant)` helper in the gateway and Auth tests.

## Implementation Notes, round 3 (2026-11-22)
- **Assertion lambdas (`java:S5778`):** in Auth, Catalog, Inventory, Order and Payment tests, the arguments are built in local variables before `assertThrows` / `assertThatThrownBy`, so the lambda holds one call. Lambdas of the same shape that were not listed were changed too, so the files stay consistent. Left as is: one `ProductRepositoryTest` lambda that must run `deleteById` and `flush` together.
- **Test support:** the `Concurrently` helpers close their executor with try-with-resources, and keep `shutdownNow()` in an inner `finally` so `close()` never waits on a hung task (`java:S2095`). `MutableClock` has identity `equals`/`hashCode`, as the `Clock` contract asks. `StubUpstream` keeps its reply in an `AtomicReference` (`java:S3077`). The gateway's HTTP test helpers declare `IOException, InterruptedException` instead of `Exception` (`java:S112`).
- **Main code:** `try (var _ = ...)` for trace scopes (`java:S7466`). Order's `OutboxRelay.send` declares its three checked exceptions (`java:S112`). Catalog's `OutboxRelay` has separate `catch (InterruptedException)` and `catch (Exception)` clauses instead of `instanceof` (`java:S1193`), with the same marking and logging.
- **Other tests:** a compiled `Pattern` replaces `String.replaceAll` inside loops in the `RouteSecurityRulesTest`s. Helper methods lower the complexity of `TrustBoundaryFilterTest` and Customer's `RouteSecurityRulesTest`. Unused imports and impossible `throws Exception` clauses were removed. `MockCarrierGatewayTest` asserts `doesNotThrowAnyException()`, and `PaymentClientAdapterTest` uses `hasToString`.
- **Frontend and load tests:** content-based React keys, `codePointAt`, `toHaveLength`, a `Set` for carriers, `String.raw`, optional chaining, top-level `await` in `main.tsx` and `seed.ts`, and `defineConfig` in `eslint.config.js`. Also: no nested template literals, a `const` mock database reset in place, and `<fieldset>` in the warehouse mockup. Code-unit comparators replace `localeCompare`, because locale collation would reorder mixed-case ids. In `ProductImage`, only names with characters outside the BMP (for example emoji) may now get a different placeholder colour. The `index.html` findings were in `frontend/playwright-report/`, which is now excluded.
- **Verified:** full `mvnw test` per service: API Gateway 387, Auth 97, Catalog 291, Customer 131, Delivery 168, Inventory 407, Notification 86, Order 386, Payment 120, all passing. Frontend `npm run check` passes (429 tests); `vite build` succeeds with top-level `await`. On minikube (`voltstore-np`), the Postgres deployments rolled out with only `POSTGRES_DB` from the ConfigMap, and all 27 pods became Ready.

## Deviations from spec / task
- **Two local test failures are left as they are.** `CartRepositoryTest.changes_moveUpdatedAtForward` (Customer) compares a timestamp from the database's `now()` with one from the JVM clock; the Docker VM clock ran about 0.3 s ahead, so the later value looked earlier. `EventReaderTest.brokenConstraints_areListedWithoutValues` (Notification) expects English Bean Validation messages and gets Ukrainian ones on a machine with a Ukrainian locale; it passes with `-Duser.language=en`. Neither file nor the code under test was changed here, and CI is not affected. Fixing them (one clock source, a fixed test locale) is a separate task.
- **`java:S125` findings were fixed by punctuation, not by deleting.** Several "commented-out code" findings were prose comments that end a line with `;`. Changing the `;` to `.` keeps the documentation and stops the false match. Alternative: mark them as false positives in Sonar, which only works on one server.
