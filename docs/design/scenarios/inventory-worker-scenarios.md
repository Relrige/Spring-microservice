# Inventory Worker Scenarios

> Draft interaction scenarios for the Inventory Worker actor.
> The Inventory Worker is an internal staff role authenticated via Auth Service with `ROLE_INVENTORY_WORKER`.

---

## SCN-INV01: Update Stock Quantity

Covers both restocking (positive delta) and write-offs or corrections (negative delta).

1. Worker sends `POST /inventory/stock/{productId}/adjust` with `{delta}` and JWT (`ROLE_INVENTORY_WORKER`).
   - `delta > 0`: stock increase.
   - `delta < 0`: stock decrease.
2. Inventory Service checks if `StockItem` exists for `productId`.
   - If yes: proceeds directly. StockItem existence is sufficient proof that the product is known.
   - If not: Inventory Service calls Catalog Service synchronously to verify the product exists.
     - Product not found → returns `404 Not Found`. No StockItem is created.
     - Product found → creates `StockItem(productId, availableQty=0, reservedQty=0, packaging=null)` and proceeds.
3. Inventory Service validates: `availableQty + delta >= 0` — cannot reduce below zero.
4. Inventory Service applies `availableQty += delta`.
5. If `availableQty` transitions from `0` to `> 0` AND product is `ACTIVE`:
   - Publishes event: `ProductStockReplenished(productId)` → Catalog updates read-model to `IN_STOCK`.
6. If `availableQty` transitions from `> 0` to `0` AND product is `ACTIVE`:
   - Publishes event: `ProductStockDepleted(productId)` → Catalog updates read-model to `OUT_OF_STOCK`.
7. Returns `200 OK` with `{newAvailableQty}`.

*Restocking is permitted even when the product is `NOT_ACTIVE` — warehouse stock can be prepared before the product goes live.*

---

## SCN-INV02: Set / Update Packaging Logistics Parameters

1. Worker sends `PUT /inventory/stock/{productId}/packaging` with `{grossWeightGrams, lengthCm, widthCm, heightCm}` and JWT.
2. Inventory Service validates: all dimension values are positive.
3. Inventory Service checks if `StockItem` exists for `productId`.
   - If yes: proceeds directly.
   - If not: calls Catalog Service synchronously to verify the product exists.
     - Product not found → returns `404 Not Found`.
     - Product found → creates `StockItem(productId, availableQty=0, reservedQty=0, packaging=null)` and proceeds.
4. Inventory Service updates packaging dimensions on the `StockItem`.
5. Returns `200 OK`.

*This step is a prerequisite for product activation (SCN-CM02) and for successful TTN generation (SCN-S04).*

---

## SCN-INV03: View Stock Balances

1. Worker sends `GET /inventory/stock` with optional filters and JWT.
2. Inventory Service returns a list of all `StockItems`:
   `[{productId, availableQty, reservedQty, totalQty, packagingConfigured}]`.

*`totalQty = availableQty + reservedQty`. `packagingConfigured` indicates whether dimensions are set.*

---

## SCN-INV04: View Paid Orders Fulfillment Queue

1. Worker sends `GET /orders/fulfillment-queue` with JWT (`ROLE_INVENTORY_WORKER`).
2. Order Service returns all orders with `status` in `{PAID, PENDING_SHIPPING}`.
3. Each entry includes: `orderId`, `placedAt`, `items` (productName, quantity from snapshot), `deliveryDetails`, `ttn` (if generated), `deliveryStatus` (e.g., `TTN_GENERATED`, `TTN_FAILED`, `TTN_PENDING`).
4. Worker can see which orders are ready for dispatch (TTN present) and which have failures.

---

## SCN-INV05: Manual TTN Generation Retry

1. Worker sees an order with `deliveryStatus = TTN_FAILED` in the fulfillment queue.
2. Worker sends `POST /delivery/orders/{orderId}/ttn/retry` with JWT.
3. Delivery Service re-attempts carrier gateway registration.
4. On success: TTN is issued, `deliveryStatus` updates to `TTN_GENERATED`, TTN is attached to the order in the queue.
5. On failure: `deliveryStatus` remains `TTN_FAILED`, failure reason is updated with the new attempt's error.

---

## SCN-INV06: Order Handover to Courier (Dispatch)

1. Worker marks the order as dispatched: sends `POST /orders/{orderId}/ship` with JWT.
3. Order Service verifies: `status` is in `{PAID, PENDING_SHIPPING}` AND a TTN has been generated.
4. Order Service sets `Order.status = SHIPPED`.
5. Order Service publishes event: `OrderShipped(orderId, ttn)`.
6. Inventory Service consumes `OrderShipped` → permanently deducts `reservedQty` for all order items (stock is fully consumed).
7. Notification Service consumes `OrderShipped` → sends "Parcel Shipped" email with TTN to customer.

*Once `SHIPPED`, customer self-service cancellation is locked.*
