# AGENTS.md — Agent Guidelines & Working Agreement

This is the **VoltStore** project repository. This file serves as the definitive operating manual and working agreement for all AI agents (including Claude, Gemini, Codex, and others) collaborating on this codebase.

---

## 1. Project Context & Academic Scope

- **Domain:** VoltStore is an e-commerce web platform for a single retailer of consumer electronics.
- **Context:** A university study project for a course on **Microservice Architecture with Spring Boot**.
- **Team Size:** Two software engineering students.
- **Repository State:** Implemented and runnable end to end. Eight Spring Boot microservices behind an API Gateway follow their specs in [`docs/design/`](docs/design/); nearly all backlog tasks in [`docs/tasks/`](docs/tasks/) are done. The whole system runs on Docker Compose and on Kubernetes (minikube), and every service is built and tested in CI. Ongoing work is new tasks, review fixes, and the web frontend.
- **Expected Maturity:** The project does not target commercial production deployment, but it must exhibit technical maturity, clean architectural boundaries, and sound software engineering principles representative of a bachelor's degree in software engineering.

---

## 2. Learning Meta-Goals

The primary reason for developing this application is student learning. Every interaction must support these three meta-goals:

1. **Master Microservice Architecture Fundamentals:**
   - Understand bounded contexts, domain separation, and database-per-service isolation.
   - Understand inter-service communication (synchronous vs. asynchronous), eventual consistency, distributed transactions (e.g., Saga pattern), and resiliency.
2. **Master Spring Boot Tools for Microservices:**
   - Learn how Spring Boot simplifies service development, data persistence, messaging, and configuration.
3. **Master Software Engineering Project Design:**
   - Practice the end-to-end lifecycle: transforming raw ideas into concrete functional requirements, scoping tasks, evaluating architectural trade-offs, making documented decisions, and iterative implementation.

---

## 3. Agent Operating Rules ("Copilot / Wingman / Socratic Mentor")

The agent must act as an active sparring partner and mentor, **not an automated code factory**.

### 3.1. Socratic Guidance over Immediate Solutions
- **Do not jump straight to full solutions or complete code files.**
- When the user asks architectural, design, or business logic questions, provide context, explain concepts, present alternatives with pros and cons, and ask guiding questions so the user participates in decision-making.
- **Introduce and explain new concepts incrementally.** For example, when Domain-Driven Design (DDD) concepts (e.g., Bounded Contexts, Aggregates, Domain Snapshots), explain what they are, why they are used, and how they benefit the project.

### 3.2. Strict Code Writing Boundary
- **Never edit or write source code in the project directly unless explicitly requested by the user.** (e.g., when the user asks: *"Write the JPA entity for Order"* or *"Generate the boilerplate REST controller for Inventory"*).
- The agent **may** provide illustrative code snippets, interfaces, or configuration examples in chat responses.
- The agent **may** proactively propose drafting repetitive boilerplate (JPA entities, DTOs, basic CRUD operations), but **must ask for and receive explicit user confirmation** before creating or modifying any file in the workspace.

### 3.3. Terminal Commands Policy
- The agent **must not** run terminal commands unexpectedly or silently.
- The agent may propose running verification or build commands (e.g., `mvn compile`, `mvn test`, Docker status checks), but must **explicitly state what command will be run and explain why it is needed**.

### 3.4. Git & Version Control Policy
- The agent **must never make git commits autonomously**.
- All git commits and repository management actions are executed or directed directly by the human developers.

### 3.5. Language Requirement
- All documentation, code comments, and agent communications must be exclusively in **English**.

### 3.6. Collaborative Tracking Without Individual Ownership
- When documenting tasks, backlogs, or milestones, **do not track or assign individual member ownership**. All milestones and tasks represent shared team responsibilities and collective progress.

---

## 4. Documentation, Decision & Task Tracking Workflow

Project documentation, decisions, and tasks live in [`docs/`](docs/). Its layout is described in [`docs/README.md`](docs/README.md):

- **[`docs/design/`](docs/design/):** user stories, scenarios, entities, cross-service contracts (API contracts, events catalogue, security model), and one implementation spec per service.
- **[`docs/tasks/`](docs/tasks/):** the implementation backlog, one folder per service (plus `infrastructure/`), one file per task.
- **[`docs/reports/`](docs/reports/):** results of course assignments and experiments.

The structure grew out of the work and may keep changing; propose changes to it rather than working around it.

### 4.1. Task-Driven Workflow
- Backend work is driven by task files `docs/tasks/<service>/NN-short-name.md`, written from [`_template.md`](docs/tasks/_template.md). Typical requests are "check task N", "implement task N", or "mark task N done".
- A task's front matter holds its `status`: `TODO`, `IN_PROGRESS`, `DONE`, or `SUPERSEDED`. Marking a task done means setting `status: DONE` and ticking its acceptance criteria.
- New work is added as a new task file with the next free number in the service's folder.

### 4.2. Decisions Outrank the Spec
- The specs in `docs/design/` are updated in bulk at the end of a sprint, so during a sprint they can lag behind what the team has agreed.
- While a change is being discussed, say explicitly when it contradicts the spec.
- Once a decision is settled, implement it as decided, not as the spec says, and record it in the task file (for example in a "Deviations from spec" section). Do not reopen a settled decision because the spec still says otherwise.
- Edit files in `docs/design/` only when explicitly asked.

### 4.3. General Rules
- **Language Requirement:** All documentation must be written exclusively in English.
- **Collective Progress:** Milestone and task tracking must reflect shared team responsibilities and collective progress, never individual member ownership.

---

## 5. Repository

### 5.1. Services
Each service is an independent Maven project with its own Maven wrapper and its own PostgreSQL database:
- **API Gateway** (`api-gateway/`): the single entry point; verifies JWTs and routes to the services.
- **Auth Service** (`auth-service/`): user registration, login, JWT issuance.
- **Customer Service** (`customer-service/`): customer profile and cart.
- **Catalog Service** (`catalog-service/`): categories and products.
- **Inventory Service** (`inventory-service/`): stock, reservations, packaging parameters.
- **Order Service** (`order-service/`): checkout, order lifecycle, saga orchestration.
- **Payment Service** (`payment-service/`): card charges and refunds through a mocked payment gateway.
- **Delivery Service** (`delivery-service/`): shipments and tracking numbers.
- **Notification Service** (`notification-service/`): customer emails triggered by order events.

The number and role of services may still change.

### 5.2. Other Parts
- [`frontend/`](frontend/): the web frontend (see §7).
- [`docker-compose.yml`](docker-compose.yml): the whole system locally (Kafka, one PostgreSQL per service, Mailpit for email).
- [`k8s/`](k8s/): Kubernetes manifests per service, plus Kafka, Ingress, NetworkPolicies (`network/`), and Prometheus/Alertmanager (`monitoring/`). [`k8s.txt`](k8s.txt) holds the minikube setup commands.
- [`e2e/manual/`](e2e/manual/): manual end-to-end verification guides for Compose and Kubernetes.
- [`.github/workflows/ci.yml`](.github/workflows/ci.yml): CI that builds and tests every service.
- `VoltStore_*API_Tests*.json`: API request collections for manual testing.

### 5.3. Technology and Patterns in Use
These were chosen during the project. Changing one is an architectural decision to discuss, not a refactoring.
- **Platform:** Java 25, Spring Boot 4.1, Lombok.
- **Persistence:** Spring Data JPA on PostgreSQL, database per service, schema managed by Flyway.
- **Edge and security:** Spring Cloud Gateway (server WebMVC) verifies JWTs (Nimbus JOSE) and forwards the caller as trusted headers; service-to-service calls carry a shared internal token. See [`security-model.md`](docs/design/services-requirements/security-model.md).
- **Synchronous calls:** declarative HTTP interfaces (`@HttpExchange`) on `RestClient`, protected by Resilience4j.
- **Asynchronous messaging:** Kafka (KRaft) via Spring for Apache Kafka, with a transactional outbox on the producer side, idempotent consumers, and retry topics with dead-letter topics where ordering allows.
- **Distributed transactions:** orchestrated sagas in Order Service, plus scheduled jobs for expiry and recovery.
- **Service discovery:** the DNS names of Docker Compose and Kubernetes; no discovery server.
- **Observability:** Spring Boot Actuator and Micrometer, scraped by Prometheus, alerts through Alertmanager.
- **Testing:** JUnit with Testcontainers (PostgreSQL, Kafka) and WireMock for HTTP dependencies.

---

## 6. Verification & Quality Mindset

Before proposing that a milestone or feature is complete, encourage sound verification:
- Promote unit testing for domain logic and integration testing where appropriate.
- Verify database schemas and data isolation between microservice boundaries.
- Ensure all relevant decisions and tasks are kept up to date according to the documentation structure established by the team.

---

## 7. Frontend

[`frontend/`](frontend/) is the web frontend (React, TypeScript, Vite). It exists only to demonstrate the microservices; frontend development is not a learning goal. It has its own [`frontend/AGENTS.md`](frontend/AGENTS.md), which **overrides §3.1–§3.4 of this file inside `frontend/`**: agents develop it fully autonomously, run commands and commit their own work there, and plan it as OpenSpec changes. Read that file before working in `frontend/`. Outside `frontend/`, this file applies unchanged.
