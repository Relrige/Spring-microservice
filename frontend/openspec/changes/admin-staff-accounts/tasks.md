# Tasks

## 1. Mock and page

- [ ] 1.1 Add handler tests for `POST /user/non-customer` through `createNonCustomer` (`201`, `400` with `errors.role` for `CUSTOMER`, `409` for a used email, `401` without a token, `403` for a non-admin); verify the tests pass against the existing handler
- [ ] 1.2 Build `StaffAccountsPage` at `/admin` replacing the placeholder (design D1–D2); verify with component tests for *Worker account created*, *No customer role*, *Short password*, *Passwords differ*, *Email in use* and *Server field error*, and update the tests that expected the "Administration" placeholder

## 2. Journey, live run and docs

- [ ] 2.1 Write the admin journey and update the staff-login journey (design D3); verify `npm run check:e2e` passes
- [ ] 2.2 Run `npm run test:e2e:live` against the Compose stack; record the result in *Live verification* of `frontend/README.md`; verify the live run passes
- [ ] 2.3 Check `/admin` in the preview pane on desktop and at 375 px, light and dark; verify by screenshots that it follows the design system README and has no horizontal scroll

## Workflow follow-up

- Review the change, then archive it with `/opsx:archive` so `staff-accounts` becomes a main spec.
- Propose iteration 4 (`catalog-manager-console`).
