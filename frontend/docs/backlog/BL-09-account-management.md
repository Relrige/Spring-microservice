---
id: BL-09
title: Account management features
status: Open
importance: Later
services: [auth-service, api-gateway]
blocks: —
created: 2026-10-20
---

# BL-09: Account management features

## Problem

Users can register, log in and (admins) create staff accounts, and nothing else. A real shop would need more; for the demo this is acceptable and postponed.

## Current behaviour

[Auth spec](../../../docs/design/services-specs/auth-service-spec.md): `POST /user/register`, `POST /auth/login`, `POST /user/non-customer`. Tokens live 1 hour; no refresh tokens; no revocation (gateway spec, *Open Issues*).

## Proposed change

Candidates, to be split into separate items when picked up:
- change own password;
- password reset by email (Notification Service + Mailpit);
- email verification on registration;
- admin: list users, disable/enable a staff account, change a role;
- logout with token revocation, or short-lived access tokens with refresh tokens.

## Requirements

- [ ] Split into individual items before implementation

## Frontend impact

None until picked up. The frontend has login, registration, logout (client-side only) and, in iteration 6, staff account creation.
