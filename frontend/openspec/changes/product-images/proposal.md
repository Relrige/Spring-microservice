# Proposal

## Why

Every product shows the same category placeholder, so the storefront looks bare before the demo and products are hard to tell apart (BL-06). Catalog now stores an optional `imageUrl` per product (Catalog task 30, PR #35): an `https://` URL or a path under `/images/`. This change shows those images, lets the catalog manager set them, and ships a set of illustrations with the app so the demo catalog has images without internet access.

## What Changes

- **Illustrations:** flat SVG illustrations, one per kind of product (phone, laptop, headphones, earbuds, speaker, charger, power bank, …), in `public/images/products/`. Vite serves them in development, and the nginx image serves them from `dist/`.
- **Showing images:** the product image (storefront tiles and list rows, the product page, the cart, the manager's product list) shows the product's `imageUrl` on the category's ground colour. Without an image, or when the image fails to load, the category placeholder stays.
- **Manager form:** the create and edit forms get an optional "Image" field with a small preview. It is checked against Catalog's rule before sending, and an empty field sends no image.
- **Demo data:** each fixture product names its illustration. The mock backend and `npm run seed` use it, and the seed also sets the image of fixture products that already exist on a live stack.
- **Docs:** the design-system README's *Product images* section describes images and the fallback; BL-06 becomes `Done` after the live check.

## Capabilities

### New Capabilities
None.

### Modified Capabilities
- `catalog-browsing`: *Product image placeholder* now shows the product's image when it has one, with the placeholder as the fallback.
- `product-management`: a new *Product image field*; *Save product changes* sends the image too.

## Impact

- **Depends on** Catalog PR #35 (`imageUrl` in `catalog-service/openapi/public.json`); `npm run gen:api` picks it up.
- **Changed code:** `src/components/ProductImage.tsx` and its four callers, `src/features/manage/` (form, field rules, edit page), `src/fixtures/demo-data.ts`, `src/mocks/` (Catalog handlers and database), `scripts/seed.ts`, `src/api/generated/catalog.ts`.
- **New files:** `public/images/products/*.svg`.
- **No change** to services, the gateway, Compose or `k8s/`: the images ride along in the existing frontend image.
