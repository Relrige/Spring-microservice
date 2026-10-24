---
id: BL-03
title: Inventory workers can read current packaging values
status: Done
importance: Important
services: [inventory-service, api-gateway]
blocks: iteration 5 (inventory-worker-console)
created: 2026-10-20
---

# BL-03: Inventory workers can read current packaging values

## Problem

The packaging form (weight and three dimensions) replaces all four values at once (`PUT …/packaging`). Without a way to read the current values, the form always starts empty: a worker who wants to fix one dimension must re-enter all four and cannot see what is stored.

## Current behaviour

- `PUT /inventory/stock/{productId}/packaging` (EP-INV-03) is routed for `INVENTORY_WORKER`.
- `GET /inventory/stock/{productId}/packaging` (EP-INV-05) exists but is internal (role `SERVICE`, not routed; listed under *Not routed* in the [gateway spec](../../../docs/design/services-specs/api-gateway-spec.md#route-table)). It answers `422 packaging_data_missing` when packaging is incomplete, because Delivery uses it as a precondition check.
- `GET /inventory/stock` only returns the flag `packagingConfigured`.

## Proposed change

Options (team to decide):
- **A (recommended).** A worker-facing read with a display-friendly answer: e.g. `GET /inventory/stock/{productId}` for `INVENTORY_WORKER` returning the stock row plus `grossWeightGrams, lengthCm, widthCm, heightCm` (`null` when not set), routed by the gateway. EP-INV-05 stays internal and keeps its `422` contract with Delivery and Catalog.
- **B.** Allow `INVENTORY_WORKER` on EP-INV-05 and route `GET …/packaging`. Smallest change, but the worker gets `422` for exactly the products that need packaging, and the gateway's method-specific rule for this path changes.
- **C.** Add the four packaging values to each row of `GET /inventory/stock`. No new route, larger list rows.

## Requirements

- [ ] A worker can read the stored packaging values of one product, with `null` for unset values and `200` (not `422`) when packaging is incomplete
- [ ] Internal callers keep the EP-INV-05 contract unchanged
- [ ] Updated: inventory spec, `api-contracts.md`, gateway route table and route test

## Frontend impact

- Until done: the packaging form starts empty and says that current values cannot be shown. Marked in code with `BL-03`.
- After: the form is pre-filled.

## Resolution (2026-10-23)

Option A, chosen by the user: Inventory task 25 (PR #37) adds `GET /inventory/stock/{productId}` (EP-INV-08) for `INVENTORY_WORKER`, the stock row plus the four packaging values (missing while not set, `200` when incomplete); gateway task 13 (PR #38) routes it. EP-INV-05 stays internal. Verified from the frontend on 2026-10-23 (change `packaging-prefill`): the packaging dialog opens with the stored values on the Compose stack, and the seed now compares stored packaging with the fixtures.
