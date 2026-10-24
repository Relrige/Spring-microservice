# Tasks

## 1. Pre-filled packaging dialog

- [x] 1.1 `getStockItem` call and mock handler (design D3); the dialog reads the stock item and fills the form, with loading and error states (design D1); verify with tests for *Stored values shown*, *Packaging not set yet*, *Packaging saved* and *Missing value*

## 2. Seed and live check

- [x] 2.1 The seed sets packaging only when it differs from the fixture (design D2); live run on Compose with #37/#38: seed twice (second run unchanged), the dialog shows stored values, live journeys pass; BL-03 `Done`

## Workflow follow-up

- Review the change, then archive it with `/opsx:archive`.
