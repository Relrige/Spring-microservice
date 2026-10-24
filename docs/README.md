# Documentation

This directory contains the documentation and design artifacts for the VoltStore project.

---

## Structure

```text
docs/
├── design/
│   ├── entities/
│   │   ├── entities.md
│   │   └── order-state-machine.md
│   ├── scenarios/
│   │   ├── catalog-manager-scenarios.md
│   │   ├── customer-scenarios.md
│   │   ├── inventory-worker-scenarios.md
│   │   └── system-scenarios.md
│   ├── services-requirements/
│   │   ├── api-contracts.md
│   │   ├── events-catalogue.md
│   │   └── security-model.md
│   ├── services-specs/
│   │   ├── api-gateway-spec.md
│   │   ├── auth-service-spec.md
│   │   ├── catalog-service-spec.md
│   │   ├── customer-service-spec.md
│   │   ├── delivery-service-spec.md
│   │   ├── inventory-service-spec.md
│   │   ├── notification-service-spec.md
│   │   ├── order-service-spec.md
│   │   └── payment-service-spec.md
│   └── user-stories/
│       ├── user-stories-review-notes.md
│       ├── customer/
│       │   ├── customer-user-stories.md
│       │   └── customer-technical-insights.md
│       ├── catalog-manager/
│       │   ├── catalog-manager-user-stories.md
│       │   └── catalog-manager-technical-insights.md
│       └── inventory-worker/
│           ├── inventory-worker-user-stories.md
│           └── inventory-worker-technical-insights.md
├── tasks/
│   ├── _template.md
│   ├── api-getaway/
│   ├── auth-service/
│   ├── catalog-service/
│   ├── customer-service/
│   ├── delivery-service/
│   ├── infrastructure/
│   ├── inventory-service/
│   ├── notification-service/
│   ├── order-service/
│   └── payment-service/
└── reports/
    ├── messaging-fault-tolerance.md
    ├── messaging-experiment.compose.yml
    └── monitoring-alerting.md
```

## Directory Guide

### [`design/`](design/)

- **[`entities/`](design/entities/)**: Formal data models and state machines.
  - `entities.md`: Defines all aggregates and logical models for all services.
  - `order-state-machine.md`: The definitive state machine and transitions for the order lifecycle.
- **[`scenarios/`](design/scenarios/)**: Behavioral interaction flows between actors and the system.
  - Covers customer checkout flows, catalog administration, inventory management, and background system logic (e.g., sagas, TTL expiry).
- **[`services-requirements/`](design/services-requirements/)**: Concrete technical contracts derived from the scenarios.
  - `api-contracts.md`: Explicit REST API definitions for all endpoints.
  - `events-catalogue.md`: Definitions for all asynchronous domain events.
  - `security-model.md`: How services identify callers (gateway-validated JWT, trusted headers, internal token) and the preconditions for it to be safe.
- **[`services-specs/`](design/services-specs/)**: Detailed, per-service implementation specifications organized by entrypoint cards.
  - Includes the API Gateway spec (routing allowlist, authentication and trust boundary).
  - Contains self-contained specs for each service covering REST endpoints, event consumers, background jobs, execution steps, edge cases, external dependencies, and story/scenario traceability.
- **[`user-stories/`](design/user-stories/)**: The original functional requirements and analysis.
  - Includes actor-specific user stories, technical insights, and design review notes (`user-stories-review-notes.md`) that shape the microservice boundaries.

The specs are updated in bulk at the end of a sprint. Decisions agreed during a sprint are recorded in the task files until then (see below).

### [`tasks/`](tasks/)

The implementation backlog: one folder per service (the API Gateway's folder is `api-getaway/`), plus `infrastructure/` for shared setup such as Kafka and monitoring.

- One file per task, named `NN-short-name.md`, written from [`_template.md`](tasks/_template.md): context with references to the design, acceptance criteria, and technical notes.
- The front matter holds the task's `status`: `TODO`, `IN_PROGRESS`, `DONE`, or `SUPERSEDED`. A task is done when its status is `DONE` and its acceptance criteria are ticked.
- Decisions that differ from the specs in `design/` are recorded in the task file, for example in a "Deviations from spec" section.

### [`reports/`](reports/)

Results of experiments and course assignments:

- `messaging-fault-tolerance.md`: the fault-tolerant messaging report (transactional outbox, retry and dead-letter topics, idempotent consumer, failure experiment), with `messaging-experiment.compose.yml`, the Compose overlay used for the experiment.
- `monitoring-alerting.md`: the metrics and alerting report (Prometheus and Alertmanager in Kubernetes, alerts sent to Telegram).
