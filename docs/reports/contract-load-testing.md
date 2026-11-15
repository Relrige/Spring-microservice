# Contract Test between Delivery and Inventory, and Three Load Scenarios

Group assignment (lecture 10, testing distributed systems):
- a contract test that works with two different services;
- proof that a contract change on one side makes the test fail;
- three load-testing scenarios, created and run;
- a report with the data and results of the runs;
- the report, the project and the test files handed in as an archive.

Runs: 2026-11-15. The contract tests ran as Maven tests (Testcontainers). The load tests ran with k6 against the whole system on Docker Compose. Task: [contract-and-load-testing.md](../tasks/infrastructure/contract-and-load-testing.md). All raw files are in [`contract-load-testing/`](contract-load-testing/).

## 1. The Contract

```
 Delivery (consumer)                                        Inventory (provider)
 ───────────────────                                        ────────────────────
 InventoryPackagingPactTest                                 PackagingPactProviderTest
   real InventoryClient + InventoryPackagingProvider          @SpringBootTest(RANDOM_PORT), PostgreSQL + Kafka
   ⇅ Pact mock server                                         (Testcontainers), real security filters
   │                                                          ▲
   └─ writes ─► contracts/pacts/delivery-service-inventory-service.json ─ reads ─┘
                (committed; every build of Inventory verifies it)
```

The pair is Delivery → Inventory on EP-INV-05, `GET /inventory/stock/{productId}/packaging`. Delivery calls it while it generates a shipment's waybill (TTN) and needs the parcel's weight and size. We picked this pair for four reasons:
- the endpoint is small;
- it has three meaningful answers;
- Delivery classifies each answer differently;
- a mistake here does not show up at checkout. It shows up later, as shipments stuck in `TTN_FAILED`.

The contract is **consumer-driven**. Delivery's test states what Delivery sends and what it needs back, and Pact turns that into a JSON file (Pact specification V4). Inventory replays every interaction from that file against its running application.

| Provider state | Request | Answer in the pact | What Delivery does with it (asserted by the consumer test) |
|---|---|---|---|
| `a stock item with complete packaging exists` | `GET .../6f1c2a4e-…-0001/packaging` | `200`, `grossWeightGrams`, `lengthCm`, `widthCm`, `heightCm`, each matched as an integer | uses the four values |
| `no stock item exists` | `GET .../6f1c2a4e-…-0002/packaging` | `404` | `stock_item_not_found:{id}`, not retryable |
| `a stock item without packaging exists` | `GET .../6f1c2a4e-…-0003/packaging` | `422` | `packaging_data_missing:{id}`, not retryable |

Each request requires `X-Internal-Token`, matched by the regex `.+`. The pact holds the dummy value `pact-consumer-token`, and the provider test replaces it with its own token. No real secret is in the file.

What the pact deliberately leaves out:
- **The bodies of the `404` and the `422`.** Delivery reads only their status. Inventory's `422` also carries `reason: "packaging_data_missing"`, but that field is Catalog's need, and Catalog is not part of this contract. Pinning it here would block Inventory from changing something Delivery never reads.
- **Exact values.** The matchers check types, so the provider can return any packaging numbers.

**How the pact travels.** There is no Pact Broker. Delivery's surefire configuration writes the pact to `contracts/pacts/` at the repository root (`pact.rootDir`, with `pact.writer.overwrite=true`, so an interaction removed from the test also disappears from the file). The file is committed. Inventory's test reads that folder with `@PactFolder("../contracts/pacts")`. CI checks out the whole repository, so both modules see the same file and `ci.yml` did not change. The working rule is written at the top of the provider test: when Delivery's expectations change, run Delivery's tests and commit the regenerated pact in the same PR.

Tools: Pact JVM 4.7.7, `consumer:junit5` and `provider:junit5spring`. A spike showed both work with Spring Boot 4.1, Spring 7 and Jackson 3.

### 1.1 Green run

Both sides pass (`contract-pass.log`):

```
Verifying a pact between delivery-service and inventory-service
  Given a stock item with complete packaging exists
  a packaging request for a product with complete packaging
    returns a response which
      has status code 200 (OK)
      has a matching body (OK)
  ... (422 and 404 interactions: OK)
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0 -- in ...PackagingPactProviderTest
```

### 1.2 Breaking the provider: `provider-break.patch`

Inventory renames the response field `grossWeightGrams` to `weightGrams` in `PackagingResponse`. This is a refactoring that looks harmless inside Inventory. The patch also updates Inventory's own tests and its OpenAPI snapshot, so the rename is "complete" from Inventory's point of view, and all of Inventory's own tests in the run pass. Only the contract fails (`provider-break.log`):

```
      has status code 200 (OK)
      has a matching body (FAILED)
1) Verifying a pact between delivery-service and inventory-service - a packaging request for a product with complete packaging has a matching body
    1.1) body: $ Actual map is missing the following keys: grossWeightGrams
[ERROR] Tests run: 18, Failures: 1, Errors: 0, Skipped: 0      (the 17 other tests: StockGetPackaging..., GetPackaging..., OpenApiDocument...)
```

Without the contract, this change would pass Inventory's whole build. Then Delivery's next TTN attempt would read `0` grams. Delivery's record has primitive fields, and Jackson 3 fails on a missing primitive, so in fact every TTN would fail.

### 1.3 Breaking the consumer: `consumer-break.patch`

Delivery starts to require a new boolean field `fragile`, for example to choose a fragile-goods carrier service. The patch makes three changes:
- it adds the field to `InventoryPackaging`;
- it adds `.booleanType("fragile", true)` to the `200` interaction;
- it adds `"fragile": false` to Delivery's WireMock stubs, because Jackson 3 rejects a missing primitive.

The run has two steps (`consumer-break.log`):

1. Delivery's tests are green, and the pact is rewritten. The new field arrives with a `type` matcher:
   ```diff
   +            "fragile": true,
                "grossWeightGrams": 180,
   +            "$.fragile": { "combine": "AND", "matchers": [ { "match": "type" } ] },
   ```
2. Inventory verifies the new pact and fails, because it does not send `fragile`:
   ```
       1.1) body: $ Actual map is missing the following keys: fragile
   [ERROR] Tests run: 3, Failures: 1, Errors: 0, Skipped: 0 -- in ...PackagingPactProviderTest
   ```

This is the consumer-driven loop. The consumer's need becomes visible to the provider's build before any deployment. The provider then adds the field, or the teams negotiate.

After each run the patch was reverted with `git apply -R` and the regenerated pact was restored, which left `git status` clean. Both patches still apply to the committed tree (`git apply --check`). To repeat a run, from the repository root:

```bash
git apply docs/reports/contract-load-testing/provider-break.patch
(cd inventory-service && ./mvnw.cmd test -Dtest=PackagingPactProviderTest)
git apply -R docs/reports/contract-load-testing/provider-break.patch
```

The logs are trimmed. Application JSON log lines, Spring/JVM start-up lines, Maven download lines, framework stack frames and the Maven help footer are removed, and local paths are shortened. Each log's header says so.

## 2. Load Tests: Set-up

| | |
|---|---|
| Machine | one laptop: AMD Ryzen 5 5500U (6 cores, 12 threads), 15.3 GB RAM, Windows 11 |
| Docker Desktop | 29.7.2, 12 CPUs and 7.4 GiB given to the VM |
| System under test | the full `docker-compose.yml`: API Gateway, 8 services, 8 PostgreSQL instances, Kafka, Mailpit, frontend. All services run one instance with default settings and no CPU or memory limits |
| Load generator | k6 1.3.0 (`grafana/k6:1.3.0` container) on the same machine, calling the Gateway at `host.docker.internal:8080` |
| Data | [`load-tests/seed.js`](../../load-tests/seed.js), created only through the public API: 3 categories, 50 ACTIVE products with packaging and 1,000,000 units each, 50 customers, plus a catalog manager and an inventory worker |
| Scripts and commands | [`load-tests/`](../../load-tests/), see its [README](../../load-tests/README.md) |

Every scenario uses an **open-model** executor (`constant-arrival-rate` or `ramping-arrival-rate`). Iterations start on a schedule, whether or not earlier ones have finished. A closed model, where a fixed number of VUs loop, waits for a slow answer before it sends the next request. So it sends less load exactly when the server struggles, and it measures latency only for the requests it managed to send. That is **coordinated omission**. With an open model, a slow server shows up as latency, as more VUs in use, and, when k6 runs out of VUs, as `dropped_iterations`.

We report latency as percentiles (p50/p90/p95/p99/max), not as an average. Thresholds are on p95 and p99, plus the error rate and the check rate. Every request has one check (the expected status) and a `name` tag, so the summary reports each step.

Each scenario first ran once at reduced rate as a warm-up (JIT compilation, connection pools), and then the recorded run started. The raw results are `<scenario>-summary.json` (all metrics, with setup data removed) and `<scenario>-summary.txt` in [`contract-load-testing/`](contract-load-testing/). Container CPU was sampled with `docker stats`. In the CPU figures below, 100 % is one core.

## 3. S1 — Catalog Browsing (stepped ramp)

- **Goal:** find where the read path Gateway → Catalog → PostgreSQL starts to slow down.
- **Flow (anonymous):** `GET /catalog/products` (50 % by category, 30 % a page sorted by price, 20 % a title search) → `GET /catalog/products/{id}` of a product from that page.
- **Profile:** `ramping-arrival-rate`, 10 → 25 → 50 → 100 → 150 → 200 iterations/s. Each step has a 10 s ramp and a 30 s plateau. Every iteration makes 2 requests, so the top step is 400 requests/s.
- **Thresholds:** p95 < 300 ms, p99 < 800 ms, errors < 1 %, checks > 99 %.

**Results.** All thresholds passed in both runs.

| Run | Requests | Iterations/s | p50 | p90 | p95 | p99 | max | Errors | Dropped iterations | Max VUs |
|---|---|---|---|---|---|---|---|---|---|---|
| 1 (first full-rate run, 14:13 UTC) | 39,649 | 99.1 | 10.6 ms | 65.8 ms | 168.9 ms | 438.7 ms | 1.15 s | 0 % | 226 | 155 |
| 2 (recorded files, 14:25 UTC) | 40,095 | 92.2* | 7.0 ms | 12.3 ms | 19.3 ms | 59.6 ms | 223 ms | 0.02 % (7) | 3 | 51 |

\* Run 2's rate is over its whole duration, including the setup step.

Per step, from run 2, computed from k6's per-request output:

| Window | Target | Requests/s | p50 ms | p95 ms | p99 ms | max ms | Errors | Dropped iterations |
|---|---|---|---|---|---|---|---|---|
| 10–40 s | 25 it/s | 50 | 7.3 | 11.3 | 18.2 | 38 | 0 % | 0 |
| 50–80 s | 50 it/s | 100 | 6.6 | 9.4 | 12.4 | 22 | 0 % | 0 |
| 90–120 s | 100 it/s | 200 | 6.3 | 10.5 | 16.5 | 49 | 0 % | 0 |
| 130–160 s | 150 it/s | 300 | 6.4 | 16.7 | 53.0 | 178 | 0 % | 0 |
| 170–200 s | 200 it/s | 399 | 8.1 | 39.8 | 97.6 | 223 | 0 % | 3 |

**Interpretation.**
- **Where it bends.** Up to 100 it/s (200 requests/s) latency is flat, with p95 around 10 ms. The median barely moves through the whole test, but the tail climbs from the 150 it/s step on: p99 goes from 16 ms to 53 ms, then to 98 ms at 200 it/s. That is the start of saturation. Requests begin to wait in queues: Tomcat threads, the Hikari pool of Catalog and the Gateway's connection pool. Most requests are still fast.
- **Who is busy.** Catalog and the Gateway are the busiest containers. In run 1 they peaked at 3.5 and 2.4 cores; in run 2, at 1.5 and 1.4 cores. Catalog's PostgreSQL stayed below 0.75 cores, so the limit is the Java services' CPU, not the database.
- **The two runs differ a lot.** Run 1 had a p95 nine times higher and 226 dropped iterations. The script and the data were the same. Run 1 was the first full-rate run after the stack started; the warm-up ran at 20 % rate only, and Kafka and Notification were still working off the warm-up's orders (Kafka averaged 0.4 cores during run 1). In run 1, k6 had to start 155 VUs to keep the rate, which shows the server answering more slowly. This variance is itself a result: on one shared laptop, one run is not a measurement, and the first run after a start is the worst.
- **The 7 errors in run 2** are `dial: i/o timeout` in k6 (status 0). New connections from the k6 container to `host.docker.internal` timed out. They never reached the Gateway. Section 6 explains this.

## 4. S2 — Customer Cart (steady rate)

- **Goal:** check the authenticated write path, Gateway (JWT verification) → Customer → PostgreSQL, under a steady rate.
- **Flow:** a logged-in customer adds a random product (`POST /customers/cart/items`), sets its quantity to 1–3 (`PATCH`), reads the cart (`GET`), and removes the product (`DELETE`) in 30 % of the iterations, or always once the cart reaches 8 products. The cart limit is 10.
- **Customers:** each VU uses its own customer. The tokens come from `setup()`, and setup also empties every cart, so no two concurrent iterations edit the same cart and leftover items cannot break the limit.
- **Profile:** `constant-arrival-rate`, 30 iterations/s for 3 minutes, about 3.9 requests per iteration.
- **Thresholds:** p95 < 400 ms, p99 < 1 s, errors < 1 %, checks > 99 %.

| Step | Requests | p50 | p90 | p95 | p99 | max |
|---|---|---|---|---|---|---|
| `POST /customers/cart/items` | 5,401 | 10.2 ms | 15.2 ms | 17.9 ms | 28.4 ms | 80 ms |
| `PATCH /customers/cart/items/{id}` | 5,401 | 9.1 ms | 13.1 ms | 15.5 ms | 23.6 ms | 73 ms |
| `GET /customers/cart` | 5,401 | 6.7 ms | 9.8 ms | 11.4 ms | 17.4 ms | 52 ms |
| **All requests** | 20,827 (112.6/s) | 9.0 ms | 13.4 ms | 16.0 ms | 27.2 ms | 114 ms |

Errors 0 %, checks 100 % (20,726), dropped iterations 0. 29.2 iterations/s were achieved against 30 planned, because the setup step is part of the duration. k6 never needed more than its 20 preallocated VUs.

**Interpretation.** At 30 it/s the cart is far from its limits. Customer averaged 0.4 cores and peaked at 1.1 cores, and the Gateway averaged 0.3 cores. Every write is a read-modify-write of one cart row (with an optimistic lock) and its items, and it stays in the 10–20 ms range. Reads are cheaper than writes, as expected. Kafka shows CPU during this run although the cart emits no events: Notification and Order were still working off the events of earlier runs. Section 6 covers this background noise. The profile was not raised to find this path's limit, because S1 already shows the shape of saturation on a simpler path. A steady-rate run answers a different question: is the service stable at an expected load? It is.

## 5. S3 — Checkout (spike)

- **Goal:** check the deepest path of the system under a sudden spike, and whether it recovers. Order calls Catalog over HTTP for prices and status, then Inventory over gRPC for the reservation, and writes the order and its `OrderPlaced` event to the outbox in one transaction.
- **Flow:** a logged-in customer picks 1–3 distinct products, calls `POST /orders/validate` (EP-ORD-01) and, if the answer is `204`, `POST /orders` (EP-ORD-02). The order carries `deliveryDetails` and a fresh `Idempotency-Key`.
- **Profile:** `ramping-arrival-rate`: 2 it/s for 1 min → 25 it/s for 30 s → 2 it/s for 1.5 min. The ramps up and down take 5 s each.
- **Thresholds:** p95 < 1 s, p99 < 2 s, errors < 2 %, checks > 98 %.

### 5.1 The planned profile

| Step | Requests | p50 | p90 | p95 | p99 | max |
|---|---|---|---|---|---|---|
| `POST /orders/validate` | 1,185 | 21.5 ms | 39.4 ms | 47.0 ms | 73.0 ms | 120 ms |
| `POST /orders` | 1,185 | 43.4 ms | 74.0 ms | 90.9 ms | 130.9 ms | 210 ms |
| **All requests** | 2,421 | 33.9 ms | 65.3 ms | 75.9 ms | 114.9 ms | 210 ms |

All thresholds passed: errors 0 %, checks 100 %, no dropped iterations, at most 20 VUs, 1,185 orders placed. Placing an order costs about twice a validate (a Catalog call, a gRPC reservation and a transaction with the outbox), but even at 25 it/s the system absorbs the spike without visible queuing. One iteration waited 11.4 s for a TCP connection (`http_req_blocked` max), and the request itself was fast after that. This is the client-side connection problem described in section 6.

### 5.2 The same spike at 4 × (`SCALE=4`)

The planned spike barely loaded the system, so the same script ran once more with every rate multiplied by 4: 8 it/s → 100 it/s for 30 s → 8 it/s. The results are in `s3-checkout-x4-summary.*`. **This run crossed the thresholds**, and k6 exited with code 99.

| Step | Requests | p50 | p95 | p99 | max | Errors |
|---|---|---|---|---|---|---|
| `POST /orders/validate` | 3,900 | 87.5 ms | 334 ms | 545 ms | 1.55 s | 265 × `503` |
| `POST /orders` | 3,635 | 1.28 s | 4.74 s | 6.24 s | 10.2 s | 166 × `503` |
| **All requests** | 7,586 | 111 ms | 3.50 s | 5.66 s | 10.2 s | 5.68 % |

Dropped iterations: 840. k6 reached its limit of 200 VUs and could not start more. 3,469 orders were placed.

Per window:

| Window | Target | Requests/s | p50 ms | p95 ms | p99 ms | max ms | Errors | Dropped iterations |
|---|---|---|---|---|---|---|---|---|
| 0–60 s | 8 it/s (base) | 16 | 27.7 | 70.4 | 79.5 | 131 | 0 % | 0 |
| 65–95 s | 100 it/s (spike) | 126 | 216.5 | 3,679 | 6,028 | 9,865 | 9.79 % | 694 |
| 95–100 s | 100 → 8 (down) | 145 | 211.9 | 3,165 | 5,140 | 7,400 | 6.07 % | 131 |
| 100–130 s | 8 it/s (recovery) | 34 | 103.2 | 4,362 | 5,731 | 10,233 | 1.65 % | 15 |
| 130–160 s | 8 it/s | 16 | 25.3 | 39.2 | 57.9 | 95 | 0 % | 0 |
| 160–190 s | 8 it/s | 16 | 24.1 | 37.1 | 44.4 | 53 | 0 % | 0 |

**Interpretation.**
- **The bottleneck is Order.** Order used up to 3 cores. Kafka used up to 1.8 cores and Inventory 1.3 cores. Catalog was not among the busy containers.
- **What fails, and why it fails fast.** All 431 errors are `503` from Order, and each one has the same line in Order's log: `Bulkhead 'catalog' is full and does not permit further calls`. Order allows at most 10 concurrent Catalog calls (Resilience4j bulkhead, `maxConcurrentCalls: 10`, no waiting). When Order is CPU-bound, each checkout holds its Catalog slot longer, the 10 slots fill up, and further requests are refused at once. They do not queue inside Order. That is the bulkhead doing its job: it sheds load and keeps Order's threads from piling up behind Catalog. The cost is visible errors. A short `maxWaitDuration` would turn some of these errors into latency instead.
- **The latency is queuing.** The requests that got in waited for CPU. `POST /orders` went from a 43 ms median to 1.3 s, with p99 over 6 s. `validate` makes the same Catalog call but no reservation and no transaction, so it stayed well under the 1 s threshold (p95 334 ms).
- **The open model made the overload visible.** k6 kept starting iterations at 100/s. When all 200 VUs were busy it dropped 840 iterations, and reported them. A closed-model test would have slowed down together with the server and reported much better latency.
- **Recovery.** After the spike, about 30 s of backlog still had to drain (the 100–130 s window: 34 requests/s against 16 planned, p95 4.4 s). From 130 s on, latency was back at the base level (p95 37–39 ms, no errors). The system recovers on its own: no restart, no stuck circuit breaker. Order's circuit breaker on Catalog did not open, because the bulkhead's refusals are not slow or failing calls.

## 6. Limitations

- **One machine.** k6, the Gateway, eight services, eight databases and Kafka share 12 logical CPUs. k6 itself used up to one core in S1. The numbers show the shape of the curves (where the tail starts to grow, what fails first). They are not the capacity of a deployed system.
- **Client-side connection timeouts.** All transport errors (7 in S1, the 11 s connection in S3) are timeouts of new connections from the k6 container to `host.docker.internal`. Docker Desktop forwards those connections from its VM to the host and back into the Gateway's container. Bursts of new connections occasionally stall there. The Gateway never saw these requests. Running k6 inside the Compose network (`--network spring-microservice_default`, Gateway at `http://api-gateway:8080`) would avoid this.
- **Background work leaks between runs.** S3 creates orders, and each order produces events: Kafka, Notification's confirmation emails to Mailpit, Order's expiry job after the 15-minute payment window. That work continues during the next run (Kafka's CPU in S1 run 1 and in S2). The runs were made 20 s apart, which is shorter than this tail.
- **Run-to-run variance.** S1 is shown twice because the two runs differ by a factor of nine at p95. A real study would repeat each scenario several times and report the spread.
- **The scenarios are separate.** A production-like mix (browsing, carts and checkouts at the same time) was not run.
- **The seed persists.** The 50 products and customers stay in the databases until the Compose volumes are removed. S3 leaves `PENDING_PAYMENT` orders, which expire and release their stock after 15 minutes.

## 7. Files

| File | Content |
|---|---|
| [`contracts/pacts/delivery-service-inventory-service.json`](../../contracts/pacts/delivery-service-inventory-service.json) | the pact |
| `delivery-service/src/test/.../clients/InventoryPackagingPactTest.java` | consumer test |
| `inventory-service/src/test/.../contract/PackagingPactProviderTest.java` | provider verification |
| [`contract-load-testing/contract-pass.log`](contract-load-testing/contract-pass.log) | green run, both sides |
| [`contract-load-testing/provider-break.patch`](contract-load-testing/provider-break.patch), [`.log`](contract-load-testing/provider-break.log) | provider-side break and its failing verification |
| [`contract-load-testing/consumer-break.patch`](contract-load-testing/consumer-break.patch), [`.log`](contract-load-testing/consumer-break.log) | consumer-side break: green consumer, pact diff, failing verification |
| [`load-tests/`](../../load-tests/) | k6 seed, S1–S3, shared helpers, README with the commands |
| `contract-load-testing/s1-catalog-browse-summary.{json,txt}` | S1, run 2 |
| `contract-load-testing/s2-cart-summary.{json,txt}` | S2 |
| `contract-load-testing/s3-checkout-summary.{json,txt}` | S3, planned profile |
| `contract-load-testing/s3-checkout-x4-summary.{json,txt}` | S3 at 4 × |

The per-window tables come from k6's per-request CSV output (`--out csv`). Those files (53 MB and 9 MB) are not committed.
