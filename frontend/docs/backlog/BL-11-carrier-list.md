---
id: BL-11
title: List of available carriers
status: Open
importance: Later
services: [order-service, api-gateway]
blocks: —
created: 2026-10-20
---

# BL-11: List of available carriers

## Problem

At checkout the customer picks a carrier, but the allowed carriers are server configuration that no endpoint exposes. The frontend must hardcode them and breaks silently if the configuration changes.

## Current behaviour

[Order spec, *Configuration*](../../../docs/design/services-specs/order-service-spec.md#configuration): `ORDER_CARRIERS` (default `NOVA_POSHTA`); `POST /orders` rejects other values with `400`. The delivery address is free text.

## Proposed change

A public read, e.g. `GET /orders/carriers` → `[ { code, displayName } ]`, routed `PUBLIC` (or `CUSTOMER`). Later, possibly branch lookup per carrier.

## Requirements

- [ ] The checkout can list the carriers `POST /orders` accepts

## Frontend impact

- Until done: checkout offers `NOVA_POSHTA` only (iteration 3), marked `BL-11` in code.
- After: the carrier select is filled from the endpoint.
