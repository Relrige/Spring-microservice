---
status: IN_PROGRESS
service: infrastructure
---
# Contract Test Delivery → Inventory (Pact) and Three k6 Load Scenarios

## Context
Course assignment (lecture 10, "Testing distributed systems"):
- create a contract test that works with two different services;
- show that changing the contract on one side makes the test fail;
- create and run three load-testing scenarios;
- write a report with the data and results of the runs;
- hand in the report, the project and the test files as an archive.

The lecture teaches consumer-driven contracts with Pact (the consumer test writes a JSON pact, the provider verifies it against its real controllers, Pact Broker and `can-i-deploy`) and load testing with k6 or Gatling (open model against coordinated omission, p95/p99 instead of the average, thresholds).

No API changes. The contract under test is the existing EP-INV-05.

*References:* [Inventory spec, EP-INV-05](../../design/services-specs/inventory-service-spec.md), [Delivery spec](../../design/services-specs/delivery-service-spec.md), `delivery-service/.../clients/InventoryClient.java`, `InventoryPackaging.java`, `InventoryPackagingProvider.java`, previous assignment report [grpc.md](../../reports/grpc.md) (layout to follow).

## Decisions (2026-11-15)

### Contract test
1. **Tool: Pact JVM, V4 pacts.** The consumer uses `au.com.dius.pact.consumer:junit5` and the provider `au.com.dius.pact.provider:junit5spring`, test scope only.
   - The first step is a spike: one trivial consumer test and one provider test, to check that the libraries work with Spring Boot 4.1, Spring 7 and Jackson 3.
   - If the Spring module does not work, use plain `au.com.dius.pact.provider:junit5`: `@SpringBootTest(RANDOM_PORT)`, plus `HttpTestTarget("localhost", port)` set in `@BeforeEach`. Record the outcome under *Implementation Notes*.
2. **Pair: Delivery (consumer) → Inventory (provider), EP-INV-05 `GET /inventory/stock/{productId}/packaging`.**
   - Pacticipant names are `delivery-service` and `inventory-service`.
   - The pact has three interactions, each with its own provider state:

     | Provider state | Answer | Body in the pact |
     |---|---|---|
     | `a stock item with complete packaging exists` | `200` | `grossWeightGrams`, `lengthCm`, `widthCm`, `heightCm`, each `integerType` |
     | `no stock item exists` | `404` | none required |
     | `a stock item without packaging exists` | `422` | none required (see below) |

   - Consumer-driven means the pact holds only what Delivery uses. Delivery reads the status of the `404` and the `422` and ignores their bodies (`InventoryPackagingProvider`), so the pact requires no body for them. In particular it does not pin `reason: "packaging_data_missing"`: that value is Catalog's need, and Catalog is not part of this contract.
   - The product id in the path is a fixed UUID per state. The provider states seed exactly these ids.
3. **The consumer test drives Delivery's real client code.**
   - It builds `InventoryClient` with `ClientsConfiguration.createInventoryClient(...)`, pointed at the Pact mock server, and calls `InventoryPackagingProvider`.
   - It asserts how Delivery reads each answer:
     - `200` → packaging values;
     - `404` → `PackagingUnavailableException` with `stock_item_not_found:{id}`, not retryable;
     - `422` → `packaging_data_missing:{id}`, not retryable.
   - The pact requires the `X-Internal-Token` header with a `matchHeader` regex (any non-empty value). The real secret never appears in the pact file.
   - The test class is `delivery-service/src/test/.../clients/InventoryPackagingPactTest.java`. No Spring context is needed.
4. **Moving the pact: a committed file, no broker.**
   - The consumer test writes the pact to `contracts/pacts/` at the repository root (system property `pact.rootDir=../contracts/pacts`, set in Delivery's surefire configuration). The file is `contracts/pacts/delivery-service-inventory-service.json` and is committed.
   - Inventory's provider test reads it with `@PactFolder("../contracts/pacts")`.
   - This works because CI checks out the whole repository and each module runs from its own folder. The Docker builds use `-DskipTests`, so the build context does not need the folder.
   - Optional, only if time allows: a Pact Broker. It would be a Compose file under `docs/reports/contract-load-testing/` (broker + Postgres) and a report section showing publish, provider verification with results published, and `can-i-deploy` saying no for the broken consumer version. It is not an acceptance criterion.
5. **Provider verification runs the full application.**
   - `@SpringBootTest(webEnvironment = RANDOM_PORT)` with the existing Testcontainers configs (`PostgresTestContainerConfig`, `KafkaTestContainerConfig`).
   - `@State` methods seed the stock items through the repository, and the table is cleared between states. `@Transactional` rollback does not work with `RANDOM_PORT`.
   - Each request gets the test configuration's internal token: overwrite the `X-Internal-Token` header in the `@TestTemplate` method through the `ClassicHttpRequest` parameter.
   - Pact's `pactVerificationTestTemplate` fails the build when an interaction does not match. Also set `pact.verifier.publishResults=false`.
   - The test class is `inventory-service/src/test/.../contract/PackagingPactProviderTest.java`.
6. **Breaking the contract is shown, never committed.** Two patch files in `docs/reports/contract-load-testing/`:
   - `provider-break.patch`: Inventory's EP-INV-05 response renames `grossWeightGrams` to `weightGrams`. Run Inventory's provider test; it fails on the `200` interaction (missing field). Inventory's own tests are updated in the patch where needed, so the only failure is the pact.
   - `consumer-break.patch`: Delivery's `InventoryPackaging` gets a new required field `fragile` (boolean), and the consumer test requires it in the pact. Run Delivery's consumer test (green, the pact file is rewritten), then Inventory's provider test; it fails because Inventory does not send `fragile`.
   - For each patch: `git apply`, run, save the output, `git apply -R`, and check `git status` is clean, including the regenerated pact file.
   - Logs in the same folder: `contract-pass.log` (baseline, both sides green), `provider-break.log` and `consumer-break.log`. Trim them to the relevant lines and say in the report where they were trimmed.
7. **CI.**
   - Both tests run in the existing per-module `mvn verify` jobs, so `ci.yml` does not change.
   - The order of work is a convention, written in the report and as a comment at the top of the provider test: when Delivery's expectations change, regenerate the pact (run Delivery's tests) and commit it in the same PR.
   - The provider in CI always verifies the committed pact.

### Load tests
8. **Tool: k6, run from the `grafana/k6` Docker image** (pin an exact version tag, no install).
   - Scripts go in `load-tests/` at the repository root: `lib/` for shared helpers (config, login, checks), `seed.js`, `s1-catalog-browse.js`, `s2-cart.js`, `s3-checkout.js` and a `README.md` with the exact commands.
   - Target: the whole system on Docker Compose, through the API Gateway. The base URL is `BASE_URL`, default `http://host.docker.internal:8080`.
   - Every scenario uses an open-model executor (`constant-arrival-rate` or `ramping-arrival-rate`) with enough `preAllocatedVUs` and `maxVUs`. The report explains why (coordinated omission).
9. **Seed data: `load-tests/seed.js`, run once before the scenarios.** It works only through public APIs, so the scenarios load what a real user would see:
   - logs in as the seeded administrator (credentials from the Compose env file, passed as k6 `-e` variables, never written into the scripts) and creates a manager and an inventory worker user;
   - creates 3 categories and 50 products, sets stock for each (1,000,000 units) and complete packaging, then activates them;
   - registers 50 customers `load-user-{n}@voltstore.test`, all with one password from a k6 `-e` variable (checkout takes the delivery details in the request, so no profile is needed);
   - can be run again: existing users and products are reused (`409` on registration is fine).

   Scenarios find product ids at runtime (catalog list in `setup()`), so no ids are hard-coded.
10. **The three scenarios.** Each has a business flow, a load profile and thresholds:

    | | Flow (per iteration) | Profile | Thresholds (starting values) |
    |---|---|---|---|
    | **S1 Catalog browsing** | anonymous: list products (random category or page, sometimes a search) → details of one product | `ramping-arrival-rate`, 10 → 200 it/s over ~4 min in steps, to find the saturation point | p95 < 300 ms, p99 < 800 ms, errors < 1 % |
    | **S2 Customer cart** | logged-in customer (token from `setup()`; each VU uses a customer by `__VU`): add an item → change its quantity → read the cart → sometimes remove an item | `constant-arrival-rate`, 30 it/s for 3 min | p95 < 400 ms, p99 < 1 s, errors < 1 % |
    | **S3 Checkout** | logged-in customer picks 1–3 products → `POST /orders/validate` (EP-ORD-01) → `POST /orders` (EP-ORD-02) with `deliveryDetails` and a fresh `Idempotency-Key` (Order → Catalog over HTTP, Inventory over gRPC, outbox) | spike: 2 it/s for 1 min → 25 it/s for 30 s → 2 it/s for 1.5 min | p95 < 1 s, p99 < 2 s, errors < 2 % |

    - Use one `check` per request and tag every request with a `name`, so the summary reports each step.
    - The numbers above are starting values. After a first short run, scale them to what one laptop can do (recorded in *Implementation Notes*). A crossed threshold is a finding to explain in the report, not a failure of the task.
    - S3 creates orders that hold reservations until Order's payment window expires; the large stock from the seed makes this harmless.
11. **Recording results.**
    - Each scenario run writes `--summary-export` JSON plus a text summary (`handleSummary`) to `docs/reports/contract-load-testing/`.
    - The report also records the machine (CPU, RAM, Docker Desktop resources), the Compose stack used, and for S1 the rate at which latency or errors start to climb.
    - Optionally, a Prometheus query or Grafana screenshot of the busiest service during S1 or S3.

### Report and archive
12. **Report and archive.**
    - The report is `docs/reports/contract-load-testing.md`, in English, with the layout of `grpc.md`. It covers:
      - the contract: pair, interactions, provider states, how the pact travels;
      - the green run;
      - both breaking runs, with log excerpts and why each fails;
      - each load scenario: goal, flow, profile, thresholds, results table (RPS, p50/p90/p95/p99, max, error rate, checks), and an interpretation;
      - limits of the measurements (one laptop, client and server share the CPU).
    - The archive is made at the end with `git archive --format=zip` of the branch into the scratchpad. It contains the project, the report, the pact, the patches, the logs and the k6 scripts. It is not committed. Its path is given to the user.

## Acceptance Criteria
- [ ] Pact compatibility checked with Spring Boot 4.1; the module used on the provider side is recorded.
- [ ] Delivery: `InventoryPackagingPactTest` with three interactions (200 / 404 / 422) that drives `InventoryPackagingProvider` and asserts its classification; it writes `contracts/pacts/delivery-service-inventory-service.json`, which is committed.
- [ ] Inventory: `PackagingPactProviderTest` verifies the committed pact against the running application with three `@State` methods, and is green.
- [ ] `provider-break.patch` and `consumer-break.patch` each make the provider verification fail; both logs and the green baseline log are saved; the tree is clean afterwards.
- [ ] Both services' `mvnw verify` are green with the new tests (and so is CI).
- [ ] `load-tests/` with `seed.js`, three scenario scripts, shared helpers and a README with exact run commands; no secrets in the scripts.
- [ ] All three scenarios run against Docker Compose; summary JSON and text are saved for each.
- [ ] Report `docs/reports/contract-load-testing.md` covers the contract runs and the three scenarios with numbers and interpretation.
- [ ] Specs synced: EP-INV-05 and Delivery's Inventory client section mention the contract test and the pact location.
- [ ] Archive created with `git archive` and its path given to the user.

## Technical Notes / Constraints
- **Implementation order:**
  1. Pact spike
  2. Consumer test and pact
  3. Provider test
  4. Breaking-change runs and logs
  5. `seed.js`
  6. S1–S3, first short runs, then the recorded runs
  7. Report
  8. Spec sync
  9. Archive
- **No production code changes** in Delivery or Inventory. The only changes are test dependencies, surefire configuration and tests. The breaking changes exist only as patches.
- **Pact matchers:** use type matchers (`integerType`), not literal values. Never pin values the consumer does not read.
- **Cross-service work in one PR is agreed:** Delivery tests, Inventory tests, `contracts/`, `load-tests/`, `docs/`.
- **Running k6 on Windows.** Run it with Docker Desktop: `docker run --rm -v "<repo>/load-tests:/scripts" -v "<repo>/docs/reports/contract-load-testing:/out" grafana/k6:<tag> run ...`. The README gives the exact command, and `host.docker.internal` reaches the Gateway published on the host's port 8080.
- **Secrets.** The administrator password and the internal token never go into scripts, logs, the pact or the report.
- **Before S1–S3,** stop anything else heavy on the machine, and run each scenario once for real after a short warm-up run (JIT, connection pools).

## Implementation Notes (2026-11-15)

### Contract test
- **Spike outcome:** Pact JVM **4.7.7** works with Spring Boot 4.1.1 (Spring 7, Jackson 3) on both sides, so the planned modules stay: `au.com.dius.pact.consumer:junit5` in Delivery and `au.com.dius.pact.provider:junit5spring` (with `PactVerificationSpringProvider`) in Inventory, both test scope. The fallback to plain `provider:junit5` was not needed.
- **Consumer:** `delivery-service/src/test/.../clients/InventoryPackagingPactTest` has three `@Pact` methods (one per provider state) and three tests. Each builds the production `InventoryClient` with `ClientsConfiguration.createInventoryClient(...)` against Pact's mock server, calls `InventoryPackagingProvider` and asserts its result: packaging values for `200`; `stock_item_not_found:{id}` and `packaging_data_missing:{id}`, both not retryable, for `404` and `422`. The request requires `X-Internal-Token` by regex `.+`; the example value is the dummy `pact-consumer-token`.
- **Pact file:** Delivery's surefire sets `pact.rootDir=${project.basedir}/../contracts/pacts` and `pact.writer.overwrite=true`, so every run rewrites `contracts/pacts/delivery-service-inventory-service.json` from scratch (by default Pact merges into an existing file, and an interaction removed from the test would stay in the pact). The file is committed.
- **Provider:** `inventory-service/src/test/.../contract/PackagingPactProviderTest` runs the whole application (`RANDOM_PORT`, Testcontainers PostgreSQL and Kafka, `CatalogKnowsEveryProduct` so stock items can be created without Catalog). `@BeforeEach` sets an `HttpTestTarget` on the random port and clears `stock_items`; the three `@State` methods seed through `StockService` (`setPackaging` for the complete item, `adjustStock` for the item without packaging, nothing for the unknown one). The `@TestTemplate` overwrites `X-Internal-Token` with the test configuration's token before `verifyInteraction()`. Inventory's surefire sets `pact.verifier.publishResults=false`.
- Both modules set `pact_do_not_track=true`, which turns off Pact's anonymous usage statistics.
- **Verified:** both tests green; output in `docs/reports/contract-load-testing/contract-pass.log`.

