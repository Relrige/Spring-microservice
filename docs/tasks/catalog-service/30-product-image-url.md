---
status: DONE
service: catalog-service
---
# Product Image URL

## Context
Products have no images, so the storefront looks bare and products are hard to tell apart (frontend backlog item `BL-06`, `frontend/docs/backlog/BL-06-product-image-url.md`). The catalog manager sets an optional image reference on a product; the storefront shows it, and falls back to its placeholder when there is none. Images are not stored or uploaded by Catalog: the reference points at a file served elsewhere.
*References:* [Catalog spec — EP-CAT-01, 02, 04, 05](../../design/services-specs/catalog-service-spec.md), [API Contracts](../../design/services-requirements/api-contracts.md), [Entities](../../design/services-requirements/entities.md)

## Acceptance Criteria
- [x] `Product` has an optional `imageUrl` (Flyway migration adds a nullable column, at most 2048 characters).
- [x] Create (EP-CAT-01) and update (EP-CAT-02) accept `imageUrl`: either an absolute `https://` URL or a path under `/images/` (no `..`, no whitespace). Anything else → `400` with `errors.imageUrl`. Update replaces it like every other field, so an update without `imageUrl` clears it.
- [x] Details (EP-CAT-05), the update answer and the list rows (EP-CAT-04) return `imageUrl`, or `null` when unset.
- [x] `openapi/public.json` regenerated; the field is optional in every schema.
- [x] Spec, `api-contracts.md` and `entities.md` updated (spec sync).

## Decisions (2026-10-23)
1. The demo images are illustrations bundled into the frontend image and served under `/images/…` by the frontend's nginx, so the demo needs no internet and has no licensing questions. Catalog therefore accepts a relative `/images/…` path as well as an `https` URL (the backlog item proposed `https` only).
2. Uploading, storing or resizing images is not part of this task.

## Technical Notes / Constraints
- The internal batch-get (`BatchProductResponse`) and the product events are not changed: nothing needs the image there yet (Order snapshots and Inventory's product copy keep their current fields).

## Implementation Notes (2026-10-23)
- **Schema:** `V5__add_product_image_url.sql` adds `products.image_url VARCHAR(2048)`, nullable; existing products have no image.
- **Entity:** `Product.imageUrl` with `changeImageUrl(...)`, called by `ProductService` after `createNew` / `updateDetails`. A separate method rather than a fifth parameter keeps the existing factory and its callers unchanged.
- **Request:** `ProductRequest.imageUrl` with `@Size(max = 2048)` and `@Pattern` `^(https://\S+|/images/(?!.*\.\.)[A-Za-z0-9._/-]+)$`, message "must be an https URL or a path under /images/". Validation runs through the existing `RequestValidator`, so a bad value is a `400` with `errors.imageUrl` like every other field. `null` (or a missing field) passes.
- **Responses:** `ProductResponse` (details, update answer) and `ProductSummaryResponse` (list rows) carry `imageUrl`, not marked required, so the OpenAPI schema lets it be absent or `null`.
- **OpenAPI:** `openapi/public.json` regenerated (`-Dopenapi.update=true`): `imageUrl` in `ProductRequest` (with `maxLength` and the pattern), `ProductResponse` and `ProductSummaryResponse`. `internal.json` is unchanged.
- **Verified:**
  - `ProductIntegrationTest`: create with an `https` URL and with an `/images/…` path, read back through details and the list; six invalid values (`http://…`, `/images/../secret`, `laptop.png`, `/static/…`, a URL with a space, an empty string) and a value over 2048 characters → `400` with `errors.imageUrl`, nothing stored; an update sets the image, the next update without `imageUrl` clears it.
  - `ProductControllerTest` adapted to the new constructor argument.
  - Full suite `mvnw.cmd clean test` (JDK 25): 27 test classes, 290 tests, all green.

## Deviations from spec / task
- **Relative paths are accepted** (decision 1). The backlog item proposed `https` only; the demo images ship with the frontend, so `/images/…` must be accepted too. The path is restricted to safe characters and may not contain `..`, so it cannot point outside the frontend's image folder. Alternative: absolute URLs of the deployment (`http://localhost:3000/images/…`), rejected because the host differs between Compose and Kubernetes.
- **`https` only for absolute URLs:** a plain `http` URL would be blocked as mixed content as soon as the app runs over HTTPS.
- **The pattern is anchored** (`^…$`). Java's `@Pattern` matches the whole value anyway, but the OpenAPI document copies the pattern, and JSON Schema matches a pattern anywhere in the string; anchoring keeps both readings the same.
- **PUT clears a missing `imageUrl`.** The update replaces all editable fields (EP-CAT-02), so omitting the field removes the image. Clients must send the current value to keep it. Alternative: keep the old value when the field is absent; rejected because it would make `PUT` partial for one field only.
- **Not in batch-get or events** (technical note): nothing consumes the image there yet.

*Spec sync (2026-10-23):* `imageUrl` is now part of the Catalog spec (owned entities, EP-CAT-01/02/04/05, decision D8), `api-contracts.md` (Catalog rows) and `entities.md` (`Product`).

*Update (2026-10-23):* the demo now links freely licensed Wikimedia Commons photos by `https` URL instead of bundled illustrations (decided with the user during the frontend change). Accepting `/images/` paths stays, so images served by the frontend remain possible.
