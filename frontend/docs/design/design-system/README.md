VoltStore is the online shop of a single consumer-electronics retailer. One web app serves four roles: shoppers browse, fill a cart, check out and follow their orders; catalog managers maintain products and categories; inventory workers keep stock, set packaging and dispatch parcels; admins create staff accounts. The look is clean and precise, like a well-lit electronics counter: neutral cool greys, one electric-blue action colour, and a small volt-yellow spark used rarely.

## Content fundamentals

- Write plainly, in English, to "you". Short sentences. No exclamation marks, no emoji, no hype ("amazing", "unbeatable").
- Sentence case everywhere: "Add to cart", "Delivery details", "Log out". Uppercase only in the `label` style (column headers, status chips).
- Buttons say exactly what happens: "Add to cart", "Place order", "Pay UAH 4,598.00", "Mark as shipped", "Retry TTN". The toast then confirms in the past tense: "Added to cart", "Order placed".
- Errors say what went wrong and what to do: "This card was declined. Try another card." Never "Oops" or "Something went wrong" when a reason is known.
- Money is always `UAH 1,299.00` (code, space, thousands comma, two decimals), set in the `price` style with tabular figures. Totals come from the server; never show a client-computed total as final.
- Dates: `18 Oct 2026, 14:05` (24-hour, local time).
- Identifiers (order ids, TTN, product ids) use the `mono` style; long UUIDs show their first 8 characters with the full value on hover or copy: `#7f3a91c2`.
- Status words, the same everywhere:

| Backend value | Label | Chip colour |
|---|---|---|
| `IN_STOCK` / `OUT_OF_STOCK` | In stock / Out of stock | `success` / `danger` |
| `ACTIVE` / `NOT_ACTIVE` | Active / Inactive | `success` / `warning` |
| `PENDING_PAYMENT` | Awaiting payment | `warning` |
| `PAID` | Paid | `primary` |
| `PENDING_SHIPPING` | Ready to ship | `primary` |
| `SHIPPED` | Shipped | `primary` |
| `DELIVERED` | Delivered | `success` |
| `CANCELLATION_REQUESTED` | Cancelling | `warning` |
| `CANCELLED` / `EXPIRED` | Cancelled / Expired | `danger` / neutral (`ink-muted` on `surface-sunken`) |
| `TTN_PENDING` / `TTN_GENERATED` / `TTN_FAILED` | TTN pending / TTN ready / TTN failed | `warning` / `success` / `danger` |

## Visual foundations

### Colour

- Pages sit on `surface`; content blocks (cards, tiles, tables, header) on `surface-raised`; table headers, the filter panel and value blocks on `surface-sunken`. Separate them with `line` hairlines, not shadows.
- Text is `ink`; secondary text is `ink-muted`. Both read on all three surfaces in both themes.
- `primary` is the only action colour: the main button of a screen (one per view), links, the active navigation item, selected chips, the focus ring. Text on a primary fill is `on-primary`; never white in dark theme.
- `volt` is a spark, not a theme colour: the wordmark bar, the cart count badge, a "best price" or "new" tag. At most one volt element in view at a time on the storefront, none in staff screens except the wordmark. Text on it is `on-volt`. In light theme volt never appears as text or as a thin mark on a light surface.
- Status colours (`success`, `warning`, `danger`, each with its `-soft` ground) always come with a word; colour alone never carries a state. Chips: `-soft` ground, strong colour text, `label` style.
- Inputs and selects are bordered with `line-strong`; `line` is decorative only.

### Type

- One family: Inter (Google Fonts, weights 400, 600, 700; `font-feature-settings: "tnum"` wherever digits align). Identifiers use the `mono` stack.
- Scale: `display` (one per page, storefront only), `heading-1` page titles, `heading-2` sections and modals, `heading-3` cards and tiles, `body` default, `body-strong` emphasis and buttons, `small` metadata and staff tables, `label` uppercase headers and chips, `price` money, `mono` ids.
- Keep running text, such as product descriptions, at most about 68 characters wide.

### Space, layout and radius

- Spacing steps: `space-1` 4, `space-2` 8, `space-3` 12, `space-4` 16, `space-6` 24, `space-8` 32, `space-12` 48. Use `gap` on flex and grid, not margins between siblings.
- Content is centred at `content-max` (1200px) with `space-6` side gutters on desktop and `space-4` on phones. The header is `header-height` (64px; 56px on phones), `surface-raised` with a `line` bottom border, sticky.
- Storefront: filter column (`filter-panel`, 264px) on the left from 900px up, a drawer opened by a "Filters" button below. Product grid: 4 columns from 1200px, 3 from 900px, 2 from 600px, 1 below, gutter `space-6` (`space-4` on phones).
- Staff screens (manager, worker, admin) are denser: full-width tables in `small` text, `label` column headers on `surface-sunken`, row actions on the right, filters in a toolbar above the table.
- Radii: `radius-sm` controls and chips, `radius-md` cards, tiles, modals, `radius-lg` the large product image and the checkout summary.
- Shadows: `shadow-raised` only on hover of a clickable tile and on the header once the page scrolls; `shadow-overlay` for menus, modals and notifications. Static cards have no shadow.

### States

- Hover: clickable tiles get `shadow-raised` and their title turns `primary`; rows get `surface-sunken`.
- Focus: 2px solid `focus-ring` with a 2px offset in the ground colour, on every focusable element; never removed.
- Disabled: 45% opacity, no pointer; a disabled primary button keeps its label (for example "Placing order…" while the request runs).
- Loading: skeleton blocks in `surface-sunken` shaped like the content; a spinner only inside buttons.
- Empty: centred `heading-3` plus one `body` line in `ink-muted` and, when there is one, the action that fixes it ("Clear filters").
- Errors: a `danger-soft` notice with `danger` icon and text; for an unavailable service, add a "Try again" button.

## Product images

Products have no photos yet. Every product shows a placeholder tile: a 4:3 box (1:1 in the cart and tables) in `radius-md` (`radius-lg` on the product page), filled with one of `art-blue`, `art-yellow`, `art-mint`, `art-coral` chosen by category, with a centred 1.5px-stroke line icon for the category at 40% of the box height in the matching ink (`primary`, `art-yellow-ink`, `success`, `art-coral-ink`). Products of one category always share ground and icon. An unknown category uses `surface-sunken` with an `ink-muted` box icon. No text inside the placeholder.

## Iconography

Tabler Icons (outline, stroke 1.75) at 20px in controls and navigation, 16px in chips and table rows, inheriting the text colour. No emoji, no filled icon sets. Category icons for placeholders: device-mobile (phones), device-laptop (laptops), headphones (audio), plug (accessories), box (unknown).

## Wordmark

There is no logo file. The wordmark is the word "VoltStore" in Inter 700 at 20px, `ink`, with a 4px `volt` bar under "Volt", 2px below the baseline. On phones it shrinks to 18px. Never redraw it as an image or add a symbol.

## Building with Mantine

The app is built with Mantine. Map this system once, in the theme:
- `fontFamily` = the `sans` stack, `fontFamilyMonospace` = `mono`; `headings` sizes from `heading-1`, `heading-2`, `heading-3`.
- `primaryColor` = a 10-shade scale whose shade 6 is `primary` light (#1f55e0) and shade 4 is `primary` dark (#6f9bff); `primaryShade` `{ light: 6, dark: 4 }`.
- `defaultRadius` = `radius-md`; inputs and buttons override to `radius-sm`.
- `spacing` xs–xl = `space-1`, `space-2`, `space-4`, `space-6`, `space-8`.
- Expose every colour token as a CSS custom property (`--vs-surface`, `--vs-ink`, …) for both colour schemes, and use them for surfaces, borders and chips instead of Mantine's grey scale.
- Light and dark follow the system setting, with a toggle in the header menu.
