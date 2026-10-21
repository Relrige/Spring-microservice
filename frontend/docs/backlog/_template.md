---
id: BL-NN
title: Short name of the item
status: Open            # Open | In progress | Done | Rejected
importance: Important   # Blocker | Important | Nice to have | Later
services: [catalog-service, api-gateway]   # services (or docs) that must change
blocks: iteration N     # frontend iteration that needs it, or "—"
created: YYYY-MM-DD
---

# BL-NN: Short name of the item

## Problem

What the frontend needs, what it cannot do today, and who notices (which role, which screen). Two to five sentences.

## Current behaviour

What the backend does now, with references: spec sections (`../../../docs/design/services-specs/…#…`), gateway routes, and code paths checked. Quote exact status codes and fields.

## Proposed change

The change the backend should make. If the approach is not decided yet, list the options with pros and cons and mark the recommended one. Once decided, state the decision and the date.

## Requirements

- [ ] Observable behaviour 1 (endpoint, role, status code, response field)
- [ ] Observable behaviour 2
- [ ] Specs and contracts updated: list the documents (service spec, `api-contracts.md`, gateway route table, …)

## Frontend impact

What the frontend does until this item is `Done` (workaround, placeholder, missing screen), and what changes in the frontend afterwards.

## Notes

Anything else: related items, risks, open questions.
