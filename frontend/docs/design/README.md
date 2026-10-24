# UI Design

The VoltStore UI design, kept in the repository so every agent and teammate can use it without access to claude.ai.

| Folder | What | Read first |
|---|---|---|
| [`design-system/`](design-system/) | The design system: usage rules and tokens | [`design-system/README.md`](design-system/README.md) (colour, type, layout, states, status labels, product placeholders, icons, wordmark, Mantine mapping), then [`design-system/tokens.json`](design-system/tokens.json) (light and dark colours, type styles, spacing, radius, shadow, layout) |
| [`screens/`](screens/) | Screen mockups, one file per screen | the screen you are building; [`screens/canvas.json`](screens/canvas.json) lists them with titles |

Screens: `Main.dc.html` (storefront list, desktop, light), `StorefrontPhone.dc.html` (storefront, phone, dark), `Product.dc.html` (product details), `Login.dc.html` (login with the expired-session notice), `Cart.dc.html` (iteration 2), `Checkout.dc.html` (iteration 3: payment, test cards, declined card), `WarehouseStock.dc.html` (iteration 5: stock table and packaging form). They show the target state, including features that wait for backlog items (e.g. titles in the warehouse table need [BL-02](../backlog/BL-02-worker-product-titles.md)).

## How to use the files

- `tokens.json` is the single source for colours, fonts, spacing, radius and shadows. The app's Mantine theme is built from it; do not copy values by hand into components.
- `*.dc.html` files are **sources, not pages**: plain HTML with inline styles plus a small template layer (`{{…}}` holes, `<sc-for>`, `<sc-if>`, a `renderVals()` script with the sample data). Read them for layout, hierarchy, spacing, copy and colours. They do not render on their own (they need the canvas runtime); open the claude.ai canvas below to see them.
- Mockups are a reference: build screens from Mantine components and the theme so they match the layout, hierarchy, colours and spacing, not pixel for pixel. A screen without a mockup follows the design system README and the nearest existing screen.

## The claude.ai artifacts (visual viewer)

The files were made with Claude's Design tooling and are also published as two claude.ai artifacts, where they render on a pan-and-zoom canvas:

- Design system "VoltStore": https://claude.ai/artifact/6kmKJvD4C3quCvEhLBT7E2
- Screens "VoltStore Screens": https://claude.ai/artifact/3x2456ehYVhBGmFFgEWYvf

They are private to their owner. A teammate (or a teammate's agent) can open them only after the owner shares them from the page's Share menu; anyone else uses the files in this folder, which are complete.

## Changing the design

**This folder is the source of truth.** Change a token, a rule or a mockup here, in the same commit as the code that needs it. If you can edit the artifacts, republish the changed files to them so the viewer stays current (artifact paths: `project/README.md`, `project/tokens.json`, `project/components/Cover/preview.html` for the design system; `project/<Screen>.dc.html` and `project/canvas.json` for the screens). If someone changed the artifacts on claude.ai, copy the changed files back here before building from them.
