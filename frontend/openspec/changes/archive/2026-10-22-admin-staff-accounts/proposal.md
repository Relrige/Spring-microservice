# Proposal

## Why

Admins can log in, but `/admin` is a placeholder, so staff accounts can only be created by the seed script or through the API. Auth Service already offers EP-AUTH-03 (`POST /user/non-customer`, admin only, routed by the gateway), and the frontend already calls it from the seed. This iteration gives the admin a page for it, which completes the four role areas of the demo.

## What Changes

- **Staff accounts page** (`/admin`): a form with email, password, password confirmation and role (Catalog manager, Inventory worker, Admin), checked by the rules of EP-AUTH-01/03 before sending `POST /user/non-customer`.
- **Answers:** `201` confirms the new account by email and role and clears the form; `409` shows that the email is in use on the email field; `400` field errors appear on their fields.
- **Mock:** the existing EP-AUTH-03 handler gets the handler tests it lacks (`201`, `400` with `errors.role` for `CUSTOMER`, `409`, `401`, `403`).
- **Browser journey:** the admin creates an inventory worker, logs out, and the new worker logs in and lands on the stock page. Against the mock and the live stack.

Not in this change: listing, disabling or changing accounts and password resets (BL-09, `Later`); the admin's header link stays "Admin".

## Capabilities

### New Capabilities
- `staff-accounts`: the admin's page for creating staff and admin accounts.

### Modified Capabilities
None. The admin landing area stays `/admin`, and the header link is unchanged.

## Impact

- Code under `frontend/` only; no service, gateway or spec changes, no backlog changes.
- Uses `createNonCustomer` in `src/api/auth.ts` as it is; no new API code.
- New: `src/features/admin/StaffAccountsPage.tsx`, its tests, mock handler tests, one journey. The `/admin` placeholder route goes away.
- The live journey creates one worker account per run (unique email); accounts cannot be deleted (BL-09).
