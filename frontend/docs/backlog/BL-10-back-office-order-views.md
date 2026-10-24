---
id: BL-10
title: Back-office order views
status: Open
importance: Later
services: [order-service, api-gateway]
blocks: —
created: 2026-10-20
---

# BL-10: Back-office order views

## Problem

Only customers can see orders (their own). Staff have no view of orders beyond the worker's fulfillment queue: shipped, delivered, cancelled and expired orders disappear from every staff screen, and nobody can find an order by id or see orders that need manual attention (failed cancellation sagas, owed refunds).

## Current behaviour

[Order spec](../../../docs/design/services-specs/order-service-spec.md): customer routes `GET /orders`, `GET /orders/{id}`; worker route `GET /orders/fulfillment-queue` (`PAID`, `PENDING_SHIPPING` only). `needsManualAttention` exists on orders but is not exposed. `ADMIN` gets `403` on all order routes (roles are not hierarchical).

## Proposed change

Optional, for later: a read-only staff order list and details (filter by status, date, customer email, `needsManualAttention`), for a role to be decided (`ADMIN` or a new support role).

## Requirements

- [ ] Decide the role and scope before implementation

## Frontend impact

None until picked up; the admin area stays a single page.
