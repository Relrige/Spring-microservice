# Documentation

This directory contains the documentation and design artifacts for the VoltStore project.

---

## Structure

```text
docs/
└── design/
    ├── entities/
    │   ├── entities.md
    │   └── order-state-machine.md
    ├── scenarios/
    │   ├── catalog-manager-scenarios.md
    │   ├── customer-scenarios.md
    │   ├── inventory-worker-scenarios.md
    │   └── system-scenarios.md
    ├── services-requirements/
    │   ├── api-contracts.md
    │   ├── api-getaway-draft.md
    │   └── events-catalogue.md
    ├── services-specs/
    │   ├── auth-service-spec.md
    │   ├── catalog-service-spec.md
    │   ├── customer-service-spec.md
    │   ├── delivery-service-spec.md
    │   ├── inventory-service-spec.md
    │   ├── notification-service-spec.md
    │   ├── order-service-spec.md
    │   └── payment-service-spec.md
    └── user-stories/
        ├── user-stories-review-notes.md
        ├── customer/
        │   ├── customer-user-stories.md
        │   └── customer-technical-insights.md
        ├── catalog-manager/
        │   ├── catalog-manager-user-stories.md
        │   └── catalog-manager-technical-insights.md
        └── inventory-worker/
            ├── inventory-worker-user-stories.md
            └── inventory-worker-technical-insights.md
├── tasks/
│   ├── _template.md
│   ├── cross-cutting/
│   ├── order-service/
│   ├── catalog-service/
│   ├── inventory-service/
│   ├── customer-service/
│   ├── payment-service/
│   ├── delivery-service/
│   ├── auth-service/
│   └── notification-service/
```

## Directory Guide

- **[`entities/`](design/entities/)**: Formal data models and state machines.
  - `entities.md`: Defines all aggregates and logical models for all services.
  - `order-state-machine.md`: The definitive state machine and transitions for the order lifecycle.
- **[`scenarios/`](design/scenarios/)**: Behavioral interaction flows between actors and the system.
  - Covers customer checkout flows, catalog administration, inventory management, and background system logic (e.g., sagas, TTL expiry).
- **[`services-requirements/`](design/services-requirements/)**: Concrete technical contracts derived from the scenarios.
  - `api-contracts.md`: Explicit REST API definitions for all endpoints.
  - `events-catalogue.md`: Definitions for all asynchronous domain events.
  - `api-getaway-draft.md`: Decisions regarding the Spring Cloud Gateway implementation.
- **[`services-specs/`](design/services-specs/)**: Detailed, per-service implementation specifications organized by entrypoint cards.
  - Contains self-contained specs for each service covering REST endpoints, event consumers, background jobs, execution steps, edge cases, external dependencies, and story/scenario traceability.
- **[`user-stories/`](design/user-stories/)**: The original functional requirements and analysis.
  - Includes actor-specific user stories, technical insights, and design review notes (`user-stories-review-notes.md`) that shape the microservice boundaries.

- **[`tasks/`](tasks/)**: Implementation backlog, grouped by service, using the \_template.md\ format.
