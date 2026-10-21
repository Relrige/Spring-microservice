---
id: BL-06
title: Product image URL
status: Open
importance: Nice to have
services: [catalog-service]
blocks: —
created: 2026-10-20
---

# BL-06: Product image URL

## Problem

Products have no images, so the storefront looks bare and products are hard to tell apart at a glance.

## Current behaviour

`Product` has `id, title, description, categoryId, basePrice, status, stockStatus` ([catalog spec](../../../docs/design/services-specs/catalog-service-spec.md#service-overview)). No image data anywhere in the system.

## Proposed change

Add an optional `imageUrl` (absolute `https` URL, at most 2048 characters) to `Product`:
- set on create and update (EP-CAT-01, EP-CAT-02) by the catalog manager;
- returned by the list (EP-CAT-04), the details (EP-CAT-05) and the internal batch-get (so order snapshots could keep it later).

The images live elsewhere (any public URL). Uploading and storing images (object storage, resizing) is explicitly **not** part of this item.

## Requirements

- [ ] `imageUrl` optional on create/update; invalid URL → `400` with `errors.imageUrl`
- [ ] `imageUrl` (or `null`) in list and details responses
- [ ] Flyway migration adds a nullable column
- [ ] Updated: catalog spec, `api-contracts.md`, `entities.md`

## Frontend impact

- Until done: every product shows a placeholder derived from its category (iteration 1).
- After: the storefront shows the image when `imageUrl` is set, the placeholder otherwise; the manager form gets an image URL field. The demo seed script can set URLs.
