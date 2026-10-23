# Design

## Context

- **Backend:** Catalog task 30 (PR #35) adds an optional `imageUrl` to products: create and update accept it, details, the update answer and the list rows return it (`null` when unset). It is an `https://` URL or a path under `/images/` (no `..`, no whitespace, at most 2048 characters); anything else is a `400` with `errors.imageUrl`. `PUT` replaces every field, so an update without `imageUrl` removes the image.
- **Today:** `src/components/ProductImage.tsx` draws a placeholder per category (design D15 of iteration 1, design-system README *Product images*). It is used by the storefront tile and row, the product page, the cart line and the manager's product list. The manager form (`ProductForm`) has four fields.
- **Decided with the user (2026-10-23):** the demo shows linked product shots from manufacturers and retailers (D1), after bundled illustrations and Wikimedia Commons photos were tried. The demo therefore needs internet access, and a link can break.

## Goals / Non-Goals

**Goals:**
- Every demo product shows a real photo in the mock, on a seeded Compose stack and in the cluster.
- A manager can set, change and remove a product's image.

**Non-Goals:**
- Uploading or storing image files, image galleries, or per-product photography.
- Images in order history and order pages (orders keep their own snapshot without an image).

## Decisions

### D1. Linked product images from manufacturers and retailers
- Each fixture product links a clean product shot (on white or transparent background) from the manufacturer's or a retailer's image server: GSMArena's pictures for the phones, the makers' own shop images for the rest (Apple, ASUS, Lenovo, HP, Dell, Microsoft, Sony, JBL, Samsung, Xiaomi, Marshall, Bose, Sennheiser, Sonos, soundcore, Anker, Baseus, UGREEN, Logitech, Spigen, SanDisk, TP-Link, Belkin). Where no image of the exact model was found, the closest model is used; the Acer Nitro V keeps a Wikimedia Commons photo.
- History of the decision (2026-10-23, with the user): first bundled SVG illustrations, then free Wikimedia Commons photos, then, because the free photos are amateur shots, the user chose the copyrighted shop images. They are linked, not copied into the repository; `docs/image-credits.md` names each source and states that the rights stay with the owners.
- The demo therefore needs internet access, and an image the owner moves falls back to the category placeholder (D2).

### D2. `ProductImage` shows the image, the placeholder is the fallback
- A new `imageUrl` prop. When it is set and the image has not failed, the box (same size, ratio, radius and category ground as the placeholder) contains an `<img>` with `alt=""`. With product shots on white (D1), the box is white in both themes and the image is shown whole (`object-fit: contain`, 6% padding), like a shop tile. The title is always shown next to the image, so the image is decorative; the placeholder keeps its accessible label for products without an image.
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
- **[No internet during the demo, or an image link breaks]** → every product falls back to its category placeholder; nothing else breaks.
- **[No image of an exact model]** → the closest model is used and named as such in the credits.
