# Inventory Worker — User Stories (Draft)

This document captures the functional requirements from the perspective of the **Inventory Worker / Warehouse Specialist** actor.

---

## 1. Inbound Stock Management

* **US-INV-01: Stock Ingestion / Restocking**
  * **As an** inventory worker,
  * **I want to** add incoming physical stock for existing products received from suppliers,
  * **So that** warehouse stock balances increase and out-of-stock items become available for customer purchase.
  * *Notes:* 
    - Products can be restocked even when in `NOT_ACTIVE` status, allowing warehouse inventory to be prepared prior to customer launch.
    - If stock increases from 0 to $>0$ on an active product, an asynchronous event notifies `Catalog Service` that the product is back in stock.

* **US-INV-02: Manage Package Logistics Parameters**
  * **As an** inventory worker,
  * **I want to** set and update the packaged gross weight and box dimensions (L x W x H) for a product,
  * **So that** `Delivery Service` has accurate package dimensions when calculating carrier shipping parameters.
  * *Notes:* Workers configure these parameters for any existing `productId`, including `NOT_ACTIVE` items.

* **US-INV-03: Stock Adjustment & Write-off**
  * **As an** inventory worker,
  * **I want to** write off or adjust stock counts with a reason (e.g., damaged in warehouse, inventory count correction),
  * **So that** digital stock records accurately reflect physical warehouse reality.

* **US-INV-04: Stock Balance Inspection**
  * **As an** inventory worker,
  * **I want to** inspect current stock balances (available vs. reserved vs. total) for all products,
  * **So that** I have full visibility over inventory health and can identify low-stock items.

---

## 2. Outbound Order Fulfillment (Shipping)

* **US-INV-05: View Paid Orders Queue**
  * **As an** inventory worker,
  * **I want to** view a list of paid orders that are ready for picking and packing, complete with their pre-generated postal tracking numbers (TTN),
  * **So that** I can fulfill customer orders systematically.
  * *Notes:* The carrier TTN is automatically pre-generated upon payment confirmation, ready for label printing.

* **US-INV-06: Manual TTN Generation Retry**
  * **As an** inventory worker,
  * **I want to** view orders where automatic TTN generation failed and manually trigger a retry,
  * **So that** transient carrier integration errors or outages can be resolved without blocking customer order fulfillment.
  * *Notes:* Displays the failure reason (e.g., carrier timeout). The worker has a dedicated "Retry TTN Generation" button to re-attempt consignment registration once the carrier is available.

* **US-INV-07: Order Packing & Handover to Courier**
  * **As an** inventory worker,
  * **I want to** confirm that an order's items are packed into a parcel and mark the parcel as handed over to the postal courier,
  * **So that** the order transitions to `SHIPPED` status, reserved stock is permanently deducted, and the customer is notified that their parcel is on its way.
