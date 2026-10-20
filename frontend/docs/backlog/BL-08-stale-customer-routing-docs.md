---
id: BL-08
title: Update stale Customer and gateway status notes
status: Open
importance: Nice to have
services: [docs]
blocks: —
created: 2026-10-20
---

# BL-08: Update stale Customer and gateway status notes

## Problem

The specs say Customer Service is not routed by the gateway, but it is. Agents and people reading the specs get a wrong picture of what works.

## Current behaviour

- `api-gateway/src/main/resources/application.yml` configures all nine `customer-*` routes (and the Order and Delivery routes).
- [Customer spec, *Implementation Status*](../../../docs/design/services-specs/customer-service-spec.md#implementation-status): "Gateway routes: Not yet routed"; Kubernetes "not yet deployed to minikube".
- [Gateway spec](../../../docs/design/services-specs/api-gateway-spec.md): the *Service Overview* says Order, Delivery and Customer routes are added later; *Open Issues* says "Customer Service does not exist yet"; *Configuration* says "customer-service is not deployed yet". The route-table intro already says every row is configured since 2026-10-17, which contradicts those lines.

## Proposed change

Update those status lines to the current state (routes configured; whether Customer is deployed to minikube, after checking).

## Requirements

- [ ] Customer spec *Implementation Status* matches the code and deployment
- [ ] Gateway spec *Service Overview*, *Configuration* and *Open Issues* no longer contradict the route table

## Frontend impact

None; documentation only.
