package ua.edu.ukma.springers.voltstore.delivery.services;

import ua.edu.ukma.springers.voltstore.delivery.carrier.Parcel;

import java.util.Arrays;
import java.util.List;

/**
 * Combines the packaging of a shipment's items into the one parcel the carrier registers (decision 2026-10-15,
 * the <b>stacking heuristic</b>). A pure function: no Spring, no I/O.
 * <ul>
 *   <li><b>Weight:</b> {@code Σ grossWeightGrams × quantity}.</li>
 *   <li><b>Dimensions:</b> every unit (an item with quantity 3 is 3 units) is a box whose sides are sorted so that
 *   {@code a ≥ b ≥ c}. The boxes are stacked flat on their largest face: {@code length = max(a)},
 *   {@code width = max(b)}, {@code height = Σ c} over all units.</li>
 * </ul>
 * Example: 2 × 22×12×6 and 1 × 40×30×8 → 40 × 30 × (6 + 6 + 8) = 40×30×20 cm.
 * <p>
 * The result over-estimates the real volume a little, which is the safe direction for a carrier that bills by
 * volumetric weight. Rejected alternatives: choosing a standard carton, or splitting into several parcels. VoltStore
 * has no real warehouse, so neither would be more accurate, and both are more complex.
 */
public final class ParcelCalculator {

    private ParcelCalculator() {
    }

    /**
     * @throws IllegalArgumentException if the list is empty, or the parcel's weight or height does not fit an
     *                                  {@code int} (reported instead of a wrong, overflowed number)
     */
    public static Parcel calculate(List<ItemPackaging> items) {
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("A parcel needs at least one item");
        }
        try {
            int weight = 0;
            int length = 0;
            int width = 0;
            int height = 0;
            for (ItemPackaging item : items) {
                int[] sides = {item.lengthCm(), item.widthCm(), item.heightCm()};
                Arrays.sort(sides);
                // sides[2] >= sides[1] >= sides[0]; every unit of the item has the same sorted sides
                length = Math.max(length, sides[2]);
                width = Math.max(width, sides[1]);
                height = Math.addExact(height, Math.multiplyExact(sides[0], item.quantity()));
                weight = Math.addExact(weight, Math.multiplyExact(item.grossWeightGrams(), item.quantity()));
            }
            return new Parcel(weight, length, width, height);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("Parcel weight or height is too large", e);
        }
    }
}
