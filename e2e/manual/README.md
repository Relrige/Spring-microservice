# Manual End-to-End Checks

Step-by-step command runbooks for checking that services work together in a real deployment. They are the
manual predecessor of automated e2e tests (deferred for now; see the options discussed for a JUnit +
Testcontainers `ComposeContainer` module).

| Runbook | Environment | Status |
|---|---|---|
| [docker-compose-catalog-inventory.md](docker-compose-catalog-inventory.md) | Docker Compose | Run successfully on 2026-10-12 |
| [k8s-catalog-inventory.md](k8s-catalog-inventory.md) | minikube | Run successfully on 2026-10-13 |

## Conventions
- **Shell: Git Bash**, from the repository root. Windows PowerShell 5.1 is not used because its `curl` is an
  alias of `Invoke-WebRequest` and it mangles the quotes inside JSON bodies.
- Commands that pass a Linux path into a container (`docker exec kafka /opt/kafka/...`) are prefixed with
  `MSYS_NO_PATHCONV=1`; otherwise Git Bash rewrites `/opt/...` into a Windows path.
- IDs returned by the APIs are kept in shell variables (`CATEGORY_ID`, `PRODUCT_ID`), so run each runbook's
  steps in **one** terminal session.
- `jq` is not assumed to be installed; IDs are extracted with `grep`/`cut`.
- Every run creates new data with fresh IDs, so runs do not interfere with each other. The data stays in the
  databases until the volumes are removed.
