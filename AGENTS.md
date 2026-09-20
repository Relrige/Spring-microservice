# Working agreement

## Mission and scope
VoltStore is an e-commerce microservices platform for consumer electronics and gadgets.
It provides domain-driven backend microservices for product catalog browsing, inventory stock reservation, order lifecycle orchestration, online payment processing, and carrier parcel delivery.
Frontend/UI applications are currently out of scope for this repository (backend microservices only).

## Source of truth
1. [ARCHITECTURE_PROPOSAL.md](ARCHITECTURE_PROPOSAL.md) — Domain models, bounded contexts, domain snapshots, and context map.
2. [docker-compose.yml](docker-compose.yml) and per-service container specifications (`Dockerfile`, `.env.example`).
3. Production code (`src/main/java`) in each microservice module.
4. Test suites and integration tests (`src/test/java`).

## Code and document map
| Area | Path | Owns | Checks |
| --- | --- | --- | --- |
| Architecture | [ARCHITECTURE_PROPOSAL.md](ARCHITECTURE_PROPOSAL.md) | Domain architecture, bounded contexts, context map | N/A |
| Orchestration | [docker-compose.yml](docker-compose.yml) | Multi-container local environments and PostgreSQL instances | N/A |
| Catalog | [catalog-service](catalog-service/AGENTS.md) | Product catalog, pricing, categories, and technical specs | `python check.py --service catalog-service` |
| Delivery | [delivery-service](delivery-service/AGENTS.md) | Carrier integrations (Nova Poshta, Meest), waybill/TTN generation, tracking | `python check.py --service delivery-service` |
| Inventory | [inventory-service](inventory-service/AGENTS.md) | Warehouse stock levels, item reservations, physical dimensions/weights | `python check.py --service inventory-service` |
| Order | [order-service](order-service/AGENTS.md) | Order lifecycle, purchase item snapshots, status workflows, compensation triggers | `python check.py --service order-service` |
| Payment | [payment-service](payment-service/AGENTS.md) | Bank acquirer ACL, payment authorizations, settlements, and refund transactions | `python check.py --service payment-service` |

## Working rules
- Inspect before editing; preserve unrelated and untracked work.
- Confirm before any destructive action.
- State expected evidence before implementing.
- Keep changes focused on the requested scope.

## Checks
- Single command: `python check.py` (runs `test-compile` across all 5 services using each project's Maven wrapper).
- Single service check: `python check.py --service <service-name>`.
- Quiet mode: `python check.py -q`.
- Prerequisites: Java 25 JDK and Python 3.
- Full test suite: `python check.py --test` (requires an active Docker daemon for Testcontainers PostgreSQL containers).
- What it does not cover: Does not automatically start Docker Compose dependencies, does not run database Flyway migrations against external databases, and does not perform cross-service end-to-end integration tests.

## Handoff
`docs/HANDOFF.yaml` is the state that survives a context reset. Update it after
every verified step and before any long or risky one: snapshot, not log; refs
not content; under 40 lines; `next` always set. History is in git.
