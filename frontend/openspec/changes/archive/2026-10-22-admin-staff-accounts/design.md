# Design

## Context

- `src/api/auth.ts` already exports `createNonCustomer(body, auth = 'session')` with `NonCustomerRequest` (role restricted to the three non-customer roles) and `ROLES`; the seed uses it. The mock handler for `POST /user/non-customer` exists (role check, field errors, `409`) but has no tests.
- `RegisterPage` is the nearest screen: the same email and password rules, `EMAIL_TAKEN`, `applyServerErrors`/`FormError`.
- No mockup for the admin area; the design system README (staff screens, forms) applies. `ROLE_LABELS` in `src/session/roles.ts` gives the role words.

## Goals / Non-Goals

**Goals:** create every non-customer role from the browser with the same rules and messages as registration.

**Non-Goals:** listing or managing accounts, password resets (BL-09); a separate admin navigation (one page, the existing "Admin" link).

## Decisions

### D1. One page at `/admin`
`StaffAccountsPage` replaces the `AreaPlaceholderPage` under `RequireRole allow="ADMIN"`; `/admin/*` other paths show the not-found page. Heading "Staff accounts". *Alternative:* `/admin/accounts` with `/admin` redirecting — rejected, one page needs no sub-route.

### D2. Form as on the register page
Mantine `useForm` with `email`, `password`, `confirmPassword`, `role` (`Select` from `ROLES` minus `CUSTOMER`, labels from `ROLE_LABELS`, no default). The email rule and messages are reused from `RegisterPage` by exporting its `EMAIL` pattern alongside `EMAIL_TAKEN`. On `201` a success `Alert` "Account created: {email} · {role label}" stays until the next submit, and the form resets. `409` → `EMAIL_TAKEN` on the email field; other errors through `applyServerErrors(form, error, ['email', 'password', 'role'])`.

### D3. Tests and journey
- Mock handler tests for EP-AUTH-03 through `createNonCustomer`.
- Component tests named after the spec scenarios.
- Journey (mock and live): the admin logs in, creates `e2e-worker-<timestamp>@example.com` as an inventory worker, logs out; the new worker logs in and sees the "Stock" heading. The staff-login journey's admin heading changes from "Administration" to "Staff accounts".

## Risks / Trade-offs

- [Live runs leave one worker account each] → harmless for the demo; accounts cannot be removed until BL-09.
- [An admin can create another admin] → intended by EP-AUTH-03; the role list says "Admin" plainly.

## Migration Plan

New frontend code only. Rollback means reverting this change's commits.
