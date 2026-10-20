---
id: BL-12
title: Product attributes (technical characteristics)
status: Open
importance: Later
services: [catalog-service]
blocks: —
created: 2026-10-20
---

# BL-12: Product attributes (technical characteristics)

## Problem

US-CUST-01 asks to filter by "specifications" and US-CUST-02 to show "technical characteristics" and "delivery options". Products only have a title, a free-text description, a flat category and a price, so the storefront can search, filter and sort by those only.

## Current behaviour

`Product` has no attributes or brand; categories are flat (`Category { id, name }`), although the catalog spec's introduction mentions a "category hierarchy".

## Proposed change

Postponed; agreed 2026-10-20 that search, filters, sorting and paging on the existing fields are enough for now. If picked up: key/value attributes per product (possibly typed per category), filterable in EP-CAT-04, and optionally a brand.

## Requirements

- [ ] Decide the attribute model before implementation

## Frontend impact

None; the product page shows the description as the only product information.
