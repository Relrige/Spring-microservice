# Load Tests (k6)

Three load scenarios against the whole system on Docker Compose, through the API Gateway. Results and their
interpretation: [docs/reports/contract-load-testing.md](../docs/reports/contract-load-testing.md). Decisions:
[infrastructure task](../docs/tasks/infrastructure/contract-and-load-testing.md).

| File | What it does |
|---|---|
| [`seed.js`](seed.js) | Creates the data the scenarios need, only through the public API: a catalog manager and an inventory worker, 3 categories, 50 ACTIVE products (`LoadTest product 01`–`50`) with packaging and 1,000,000 units each, and 50 customers `load-user-{n}@voltstore.test`. Safe to run again. |
| [`s1-catalog-browse.js`](s1-catalog-browse.js) | **S1** anonymous browsing: product list (category, page or search) → product details. `ramping-arrival-rate` 10 → 200 it/s in steps. |
| [`s2-cart.js`](s2-cart.js) | **S2** customer cart: add → set quantity → read → sometimes remove. `constant-arrival-rate` 30 it/s for 3 min. |
| [`s3-checkout.js`](s3-checkout.js) | **S3** checkout spike: validate → place the order. 2 it/s → 25 it/s for 30 s → 2 it/s. |
| [`lib/common.js`](lib/common.js) | Base URL, login, checks, data lookup, the summary files. |

## Running

Git Bash, from the repository root. k6 runs from its Docker image, so nothing is installed. The scripts reach
the Gateway on the host through `host.docker.internal:8080` (override with `-e BASE_URL=...`).

1. Start the system and wait until the services are ready (see
   [e2e/manual/docker-compose-order.md](../e2e/manual/docker-compose-order.md), step 1):
   ```bash
   docker compose up -d --build
   ```
   Turn the gateway's rate limiter off first: k6 sends every request from one address, so it would get `429`
   answers. Add `GATEWAY_RATE_LIMIT_ENABLED=false` to `api-gateway/.env` (then `docker compose up -d api-gateway`),
   and remove it again afterwards.
2. Choose the passwords of the load-test users and read the administrator's credentials from the local env
   file. They are passed to k6 as `-e` variables and are never written into the scripts or the results:
   ```bash
   export STAFF_PASSWORD='choose-one' CUSTOMER_PASSWORD='choose-another'
   export ADMIN_EMAIL=$(grep '^AUTH_ADMIN_EMAIL=' auth-service/.env | cut -d= -f2-)
   export ADMIN_PASSWORD=$(grep '^AUTH_ADMIN_PASSWORD=' auth-service/.env | cut -d= -f2-)
   K6="docker run --rm -i -e ADMIN_EMAIL -e ADMIN_PASSWORD -e STAFF_PASSWORD -e CUSTOMER_PASSWORD -v $(pwd -W)/load-tests:/scripts -v $(pwd -W)/docs/reports/contract-load-testing:/out grafana/k6:1.3.0"
   ```
3. Seed once:
   ```bash
   MSYS_NO_PATHCONV=1 $K6 run /scripts/seed.js
   ```
4. Run each scenario. Each run writes `<scenario>-summary.json` (all metrics) and `<scenario>-summary.txt` into
   `docs/reports/contract-load-testing/`:
   ```bash
   MSYS_NO_PATHCONV=1 $K6 run /scripts/s1-catalog-browse.js
   MSYS_NO_PATHCONV=1 $K6 run /scripts/s2-cart.js
   MSYS_NO_PATHCONV=1 $K6 run /scripts/s3-checkout.js
   ```

Docker's `-e NAME` without a value copies the variable from the shell into the container, and k6 reads the
container's environment as `__ENV`, so the passwords never appear in a command line or the shell history. (k6's
own `-e NAME` without a value would set the variable to an empty string.) Other options are passed to k6 as
`-e NAME=value`, for example `$K6 run -e SCALE=0.5 /scripts/s1-catalog-browse.js`. `MSYS_NO_PATHCONV=1` stops Git Bash from rewriting `/scripts/...` into a Windows path.

## Options

| Variable | Default | Meaning |
|---|---|---|
| `BASE_URL` | `http://host.docker.internal:8080` | The API Gateway |
| `SCALE` | `1` | Multiplies every arrival rate of S1–S3, to fit the profile to the machine |
| `DURATION` | `3m` | Length of S2 |
| `RUN_LABEL` | none | Suffix of the summary files, e.g. `warmup`, so a trial run does not overwrite the recorded one |
| `CUSTOMER_COUNT` | `50` | Customers created by the seed and used by S2/S3 |
| `OUT_DIR` | `/out` | Where the summary files are written inside the container |

## Notes

- Every scenario uses an **open-model** executor (`constant-arrival-rate`, `ramping-arrival-rate`): iterations
  start on a schedule, not when the previous one finishes. A closed model (a fixed number of VUs in a loop) waits
  for slow answers before sending the next request, so it sends less load exactly when the server is slow and
  hides that slowness (coordinated omission). When k6 runs out of VUs it reports `dropped_iterations`.
- The thresholds are the starting values from the task. A crossed threshold makes k6 exit with code 99. In this
  assignment that is a result to explain, not a broken script.
- S3 creates real orders that hold stock reservations until Order's payment window (15 min by default) expires;
  the large seeded stock makes this harmless. The orders also trigger the customers' confirmation emails
  (Mailpit).
- Run each scenario once as a warm-up (`-e RUN_LABEL=warmup`, can be shortened with `-e SCALE=0.2`) before the
  recorded run, so JIT compilation and connection pools do not distort the first numbers.
