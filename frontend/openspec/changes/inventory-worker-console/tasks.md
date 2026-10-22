# Tasks

Apply on `inventory-worker-console`, branched from main after PR #27 (Catalog tasks 27–29, Inventory tasks 22–24, gateway task 12).

## 1. Contracts and mock backend

- [x] 1.1 Run `npm run gen:api`, review the Inventory diff and adapt `src/api/inventory.ts`, the seed and the mock to the new stock row (design D1); verify `npm run check` passes again
- [ ] 1.2 Add `getFulfillmentQueue`/`shipOrder` to `src/api/order.ts` and write `src/api/delivery.ts` with `retryTtn` and `ttnFailure` (design D2); verify with type tests for the new aliases and a unit test that `ttnFailure` reads the `reason` of a `ttn-generation-failed` problem and nothing from other errors
- [ ] 1.3 Add the mock stock, adjust, packaging, queue, ship and TTN-retry handlers and the lazy TTN failure and delivery progress (design D8); verify with handler tests through the API calls for every status code, the filters and sorts, untitled rows last, `401` and `403`, and that the checkout and order tests still pass

## 2. Navigation

- [ ] 2.1 Replace the warehouse placeholder with the `/warehouse` and `/warehouse/queue` routes and give the worker the "Stock" and "Fulfillment queue" links with the queue count (design D3); verify with tests for *Worker header* and the worker's landing, and update the tests that expected the "Warehouse" heading

## 3. Stock page

- [ ] 3.1 Build the stock page: rows, untitled rows, URL filters, search, sort, paging, empty and error states (design D4); verify with tests for *Stock rows shown*, *Title not copied yet*, *Packaging missing*, *Search by title* and *No matching stock*
- [ ] 3.2 Add the adjust dialog (design D5); verify with tests for *Restock* and *Write-off below zero*, and that a `503` shows the unavailable message
- [ ] 3.3 Add the packaging dialog (design D6); verify with tests for *Packaging saved* and *Missing value*

## 4. Fulfillment queue

- [ ] 4.1 Build the queue page with polling and the Delivery notice (design D7); verify with tests for *Orders waiting*, *Empty queue*, *Delivery Service down* and *TTN arrives* (fake timers)
- [ ] 4.2 Add "Mark as shipped" with confirmation (design D7); verify with tests for *Order shipped*, *TTN not ready* and *Shipped elsewhere*
- [ ] 4.3 Add "Retry TTN" (design D7); verify with tests for *Retry succeeds* and *Packaging still missing*, including the link to the stock page

## 5. Journeys, live run and docs

- [ ] 5.1 Write the worker journey and update the staff-login journey (design D9); verify `npm run check:e2e` passes
- [ ] 5.2 Run `npm run test:e2e:live` against the Compose stack with the inventory, order and delivery images rebuilt from main and the stack seeded; fix any mock mismatch (a generated type that disagrees with a service spec is a backlog item, `AGENTS.md` §3); record the result in *Live verification* of `frontend/README.md`; set BL-02 to `Done` in its file and the backlog index, correcting its note that PR #25 is not merged; verify the live run passes
- [ ] 5.3 Check the stock page, both dialogs and the queue in the preview pane on desktop and at 375 px (light and dark) against `WarehouseStock.dc.html`; verify by screenshots that layout and colours match and there is no horizontal scroll

## Workflow follow-up

- Review the change, then archive it with `/opsx:archive` so `warehouse-stock` and `fulfillment-queue` become main specs and the `app-navigation` delta is merged.
- Propose iteration 4 (`catalog-manager-console`); BL-01 is delivered on the backend.
