---
status: TODO
service: delivery-service
---
# Parcel Calculation (Stacking Heuristic)

## Context
The carrier needs one parcel per shipment: weight and box dimensions. Inventory knows the packaging of each product; Delivery combines them. The decision (2026-10-15) is the **stacking heuristic**: VoltStore has no real warehouse, so carton selection or multi-parcel shipments would not be more accurate, and the heuristic is simple, deterministic and errs on the safe side (slightly larger than reality).
*References:* [Delivery Spec — Parcel calculation](../../design/services-specs/delivery-service-spec.md), [Inventory EP-INV-05](../../design/services-specs/inventory-service-spec.md)

## Acceptance Criteria
- [ ] A pure domain class (no Spring, no I/O), e.g. `ParcelCalculator.calculate(List<ItemPackaging>) → Parcel`, where `ItemPackaging` = `productId`, `quantity`, `grossWeightGrams`, `lengthCm`, `widthCm`, `heightCm`.
- [ ] Weight: `Σ grossWeightGrams × quantity`.
- [ ] Dimensions: every unit's sides are sorted `a ≥ b ≥ c`; `length = max(a)`, `width = max(b)`, `height = Σ c` over all units.
- [ ] Overflow is detected (`Math.multiplyExact` / `addExact`) and reported as an error rather than a wrong number.
- [ ] An empty list or a non-positive value is rejected with `IllegalArgumentException`.
- [ ] Unit tests: the spec example (2 × 22×12×6 + 1 × 40×30×8 → 40×30×20); a single item; sides given in any order give the same result; quantity is expanded into units; overflow; invalid input.

## Technical Notes / Constraints
- Keep it a pure function so it can be tested exhaustively without containers.
- Writing the heuristic down in the class Javadoc (with the rejected alternatives) is part of the task.
