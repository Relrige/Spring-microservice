---
status: DONE
service: delivery-service
---
# Parcel Calculation (Stacking Heuristic)

## Context
The carrier needs one parcel per shipment: weight and box dimensions. Inventory knows the packaging of each product; Delivery combines them. The decision (2026-10-15) is the **stacking heuristic**: VoltStore has no real warehouse, so carton selection or multi-parcel shipments would not be more accurate, and the heuristic is simple, deterministic and errs on the safe side (slightly larger than reality).
*References:* [Delivery Spec — Parcel calculation](../../design/services-specs/delivery-service-spec.md), [Inventory EP-INV-05](../../design/services-specs/inventory-service-spec.md)

## Acceptance Criteria
- [x] A pure domain class (no Spring, no I/O), e.g. `ParcelCalculator.calculate(List<ItemPackaging>) → Parcel`, where `ItemPackaging` = `productId`, `quantity`, `grossWeightGrams`, `lengthCm`, `widthCm`, `heightCm`.
- [x] Weight: `Σ grossWeightGrams × quantity`.
- [x] Dimensions: every unit's sides are sorted `a ≥ b ≥ c`; `length = max(a)`, `width = max(b)`, `height = Σ c` over all units.
- [x] Overflow is detected (`Math.multiplyExact` / `addExact`) and reported as an error rather than a wrong number.
- [x] An empty list or a non-positive value is rejected with `IllegalArgumentException`.
- [x] Unit tests: the spec example (2 × 22×12×6 + 1 × 40×30×8 → 40×30×20); a single item; sides given in any order give the same result; quantity is expanded into units; overflow; invalid input.

## Technical Notes / Constraints
- Keep it a pure function so it can be tested exhaustively without containers.
- Writing the heuristic down in the class Javadoc (with the rejected alternatives) is part of the task.

## Implementation Notes (2026-10-15)
- **`services/ParcelCalculator.calculate(List<ItemPackaging>) → carrier.Parcel`**, a static pure function; the heuristic and the rejected alternatives (standard carton, several parcels) are in its Javadoc. `services/ItemPackaging(productId, quantity, grossWeightGrams, lengthCm, widthCm, heightCm)` rejects non-positive values in its constructor.
- Units of one item have identical sorted sides, so the loop does not expand quantities: `length = max(a)`, `width = max(b)`, `height += c × quantity`, `weight += grossWeight × quantity`.
- **Overflow** (`Math.multiplyExact` / `addExact`) and an empty or `null` list → `IllegalArgumentException` (the overflow keeps the `ArithmeticException` as cause). The TTN job (task 11) treats this as a non-retryable failure.
- **Verified (2026-10-15):** `ParcelCalculatorTest` (spec example 40×30×20, single unit, any side order, quantity expansion, length and width from different items, overflow of weight and of height, invalid input) passes without Spring or containers.
