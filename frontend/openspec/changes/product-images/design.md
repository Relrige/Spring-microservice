# Design

## Context

- **Backend:** Catalog task 30 (PR #35) adds an optional `imageUrl` to products: create and update accept it, details, the update answer and the list rows return it (`null` when unset). It is an `https://` URL or a path under `/images/` (no `..`, no whitespace, at most 2048 characters); anything else is a `400` with `errors.imageUrl`. `PUT` replaces every field, so an update without `imageUrl` removes the image.
- **Today:** `src/components/ProductImage.tsx` draws a placeholder per category (design D15 of iteration 1, design-system README *Product images*). It is used by the storefront tile and row, the product page, the cart line and the manager's product list. The manager form (`ProductForm`) has four fields.
- **Decided with the user (2026-10-23):** first bundled SVG illustrations, then replaced by real photos: freely licensed photos on Wikimedia Commons, linked by `https` URL and not downloaded. The demo therefore needs internet access, and a link can break.

## Goals / Non-Goals

**Goals:**
- Every demo product shows a real photo in the mock, on a seeded Compose stack and in the cluster.
- A manager can set, change and remove a product's image.

**Non-Goals:**
- Uploading or storing image files, image galleries, or per-product photography.
- Images in order history and order pages (orders keep their own snapshot without an image).

## Decisions

### D1. Photos from Wikimedia Commons, linked
- Each fixture product links one photo of that product (or the closest model Commons has) as a Commons thumbnail URL (`https://upload.wikimedia.org/wikipedia/commons/thumb/…/800px-….jpg`), found through the Commons API and checked by hand. Only free licences (CC0, public domain, CC BY, CC BY-SA).
- `docs/image-credits.md` lists every photo with its Commons page, author and licence, as CC BY and CC BY-SA require attribution. The app shows no credits on the page; the file is the attribution for this demo.
- Linking instead of bundling was the user's choice: no image files in the repository, at the cost of needing internet during the demo. A link that breaks falls back to the category placeholder (D2).
- 800 px thumbnails keep the product page sharp without loading full-size originals.

### D2. `ProductImage` shows the image, the placeholder is the fallback
- A new `imageUrl` prop. When it is set and the image has not failed, the box (same size, ratio, radius and category ground as the placeholder) contains an `<img>` with `object-fit: cover` (photos bring their own background, so they fill the box; the ground shows while loading) and `alt=""`. The title is always shown next to the image, so the image is decorative; the placeholder keeps its accessible label for products without an image.
- `onError` switches to the placeholder for that image, so a broken link never shows a broken-image icon.
- The callers pass `imageUrl` from the data they already have: list rows and details carry it; the cart line reads the product details it already loads.

### D3. The image field in the manager form
- `ProductFormValues.imageUrl` is a string, `''` when there is none. `toProductInput` sends the trimmed value, or leaves the field out when empty (Catalog then stores no image).
- The rule from `public.json` is checked before sending (`imageUrlError`): the same regular expression as Catalog's `@Pattern`, plus the 2048-character limit. Message: "Use an https:// link or a path under /images/".
- A preview (the `ProductImage` of the entered value) is shown under the field while the value passes the rule.
- `imageUrl` joins the form's field names, so a `400` `errors.imageUrl` lands at the field.
- The edit page fills the field from the product (`imageUrl ?? ''`), so saving without touching it keeps the image (the `PUT` sends it back).

### D4. Demo data, mock and seed
- `DemoProduct.image` is the photo URL; the fixtures get one each. The mock database stores it as `imageUrl`, and the mock Catalog handlers validate, store and return `imageUrl` like the service (same rule, same `400` key, `PUT` clears a missing value).
- `npm run seed` sends the image when it creates a product. For fixture products that already exist with another `imageUrl`, it reads the product's details and sends a `PUT` with the current values and the fixture image, so a seeded stack from before this change gets images without losing manager edits. A second run reports nothing to change.

### D5. Docs
- The design-system README's *Product images* section describes the image on the category ground and the placeholder as fallback.
- BL-06 becomes `Done` once the live run shows images from Catalog.

## Risks / Trade-offs

- **[PR #35 not merged yet]** → this branch builds on the Catalog branch; the frontend PR targets `main` after #35 is merged.
- **[An image path that does not exist]** → `onError` shows the placeholder; the manager sees it in the preview.
- **[No internet during the demo, or a Commons link breaks]** → every product falls back to its category placeholder; nothing else breaks.
- **[Commons has no photo of an exact model]** → the closest model is used and named as such in the credits.
