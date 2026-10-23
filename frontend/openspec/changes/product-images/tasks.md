# Tasks

## 1. API and mock

- [x] 1.1 Run `npm run gen:api` against Catalog's `public.json` with `imageUrl`; the mock Catalog handlers validate, store and return `imageUrl` like the service (design D4); verify `npm run check` passes

## 2. Images

- [x] 2.1 Add the SVG illustrations in `public/images/products/` and an `image` for every fixture product (design D1, D4); verify every fixture's file exists (a unit test)
- [ ] 2.2 `ProductImage` shows `imageUrl` on the category ground with the placeholder as fallback, and its callers pass it (design D2); verify with component tests for *Product with an image*, *Image fails to load* and the placeholder scenarios

## 3. Manager form

- [ ] 3.1 Add the Image field with its rule, preview and `400` mapping to the create and edit forms (design D3); verify with tests for *Image set on create*, *Invalid image value*, *Image removed* and *Image kept on save*

## 4. Seed and docs

- [ ] 4.1 `npm run seed` creates products with their image and sets the image of existing fixture products (design D4); update the design-system README and the frontend README (design D5); verify with the live run in 5.1

## 5. Live run

- [ ] 5.1 Live run: rebuild Catalog (PR #35) and the frontend image in Compose, seed twice (second run unchanged), run the live journeys, check images in the browser; mark BL-06 `Done` and record the run in *Live verification*

## Workflow follow-up

- Review the change, then archive it with `/opsx:archive`.
