package ua.edu.ukma.springers.voltstore.delivery.carrier;

/**
 * The one parcel of a shipment as the carrier sees it: total weight and outer box dimensions (computed by
 * {@code ParcelCalculator}). All values are positive.
 */
public record Parcel(int weightGrams, int lengthCm, int widthCm, int heightCm) {
    public Parcel {
        if (weightGrams <= 0 || lengthCm <= 0 || widthCm <= 0 || heightCm <= 0) {
            throw new IllegalArgumentException("Parcel weight and dimensions must be positive");
        }
    }
}
