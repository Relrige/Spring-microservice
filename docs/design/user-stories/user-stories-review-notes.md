# User Stories Review — Notes & Emerging Decisions

> Notes captured during the review of user stories and technical insight documents for all three actors (Customer, Catalog Manager, Inventory Worker).
> These are not final architectural decisions — they are clarifications and early positions that emerged from analyzing the user stories. They will feed into formal design artefacts (state machines, event catalogue, ADRs) produced during scenario writing.

---

## Service Scope (confirmed during review)

The following services were identified as needed. `Auth Service`, `Customer Service`, and `Notification Service` were absent from the initial AGENTS.md list but are required by the user stories.

| Service | Responsibility |
|---------|---------------|
| `Catalog Service` | Products, categories, `ProductStatus` lifecycle |
| `Inventory Service` | `StockItem`, stock reservations, packaging parameters |
| `Order Service` | Order lifecycle, `OrderItemSnapshot` |
| `Payment Service` | Charge, refund, `MockPaymentGateway` ACL |
| `Delivery Service` | TTN generation, carrier integration, `MockCarrierGateway` ACL |
| `Auth Service` | Authentication, JWT issuance, role-based access |
| `Customer Service` | Customer profile + backend Cart aggregate |
| `Notification Service` | Email dispatch on domain events |

---

## Notes & Early Positions

### N-01 — `productId` as the single cross-service product key
`Catalog Service` is the master domain that generates `productId`. All downstream services (`Inventory`, `Order`) reference it directly. No SKU field.
*Source: catalog-manager-technical-insights §1*

### N-02 — `ProductStatus` as a two-state Enum (`ACTIVE` / `NOT_ACTIVE`)
Enum (not boolean) for future extensibility. Products start `NOT_ACTIVE` by default. Hard deletes are forbidden; deactivation is the soft-delete mechanism.
*Source: catalog-manager-technical-insights §2*

### N-03 — Product activation requires a fully configured `StockItem`
Before `Catalog Service` transitions a product to `ACTIVE`, it performs a synchronous REST call to `Inventory Service` that validates:
1. A `StockItem` exists for the `productId`.
2. Packaging dimensions (gross weight, L×W×H) are fully set.

Rationale: a product that cannot be physically shipped should not be sold. This is a deliberate Catalog→Inventory coupling at activation time — an accepted trade-off for workflow safety.

### N-04 — `StockItem` creation: event-driven + idempotent fallback
`Inventory Service` listens to `ProductCreated` and creates a `StockItem` with zero stock.
If the event is lost, the first stock write (ingestion) idempotently creates the `StockItem` on demand.
The activation sync check (N-03) acts as the final safety net.

### N-05 — Cart owned by `Customer Service`
Backend cart persistence is the responsibility of `Customer Service`. `Cart` and `CustomerProfile` are treated as **separate aggregates** inside that service for clean separation and easy future extraction.
Guest carts remain client-side (localStorage); merged into the backend cart on login.

### N-06 — Two-phase checkout guard
- **Soft check** (cart → checkout form): confirms all items are `ACTIVE` and in stock. No stock locked. Orchestrated by `Order Service`.
- **Hard reservation** (form submit → payment): atomic stock lock + active status re-confirmation. Order created as `PENDING_PAYMENT`.
- Reservation TTL: 15 minutes. Expired reservations release stock automatically.

### N-07 — Order status `PACKING` renamed to `PENDING_SHIPPING`
More accurate for a context with no physical warehouse interaction modelled in the system. Full state machine to be derived from scenario writing.

### N-08 — Missing packaging dimensions fall back to `TTN_FAILED`, not order cancellation
If dimensions are absent when TTN generation fires, `Delivery Service` flags the delivery as `TTN_FAILED`. The three-tier resilience handles recovery. A paid order is never automatically cancelled due to a carrier integration failure.

### N-09 — Cancellation is strictly locked once order reaches `SHIPPED`
Customer self-service cancellation is only permitted before the order reaches `SHIPPED`. This eliminates the risk of the Cancellation Saga conflicting with an in-transit parcel.

### N-10 — `Notification Service` implementation details deferred
Confirmed in scope. Listens to domain events: `OrderPlaced`, `OrderPaid`, `OrderShipped`, `OrderDelivered`, `OrderCancelled`. Error handling strategy to be defined later.

### N-11 — Guest cancellation token details deferred
One-time email verification is required for guest order cancellation. Expiry policy and resend flow to be defined later.

### N-12 — Guest cart merge conflict resolution is a client-side concern; deferred

---

## Open Items (to be addressed during scenario writing)

| # | Item |
|---|------|
| O1 | **Full Order Status State Machine** — states, transitions, triggering events |
| O2 | **Event Catalogue** — all domain events with producer and consumer services |
| O3 | **Saga coordination approach** — choreography vs. orchestration (decide after state machine is defined) |
| O4 | **`OrderItemSnapshot` schema** — fields to be defined during `Order Service` domain modelling |
