package ua.edu.ukma.springers.voltstore.delivery.services;

import org.junit.jupiter.api.Test;
import ua.edu.ukma.springers.voltstore.delivery.carrier.Parcel;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ParcelCalculatorTest {

    @Test
    void specExample_twoPhonesAndALaptop_stackToFortyByThirtyByTwenty() {
        Parcel parcel = ParcelCalculator.calculate(List.of(
                item(2, 180, 22, 12, 6),
                item(1, 2100, 40, 30, 8)));

        assertThat(parcel).isEqualTo(new Parcel(2 * 180 + 2100, 40, 30, 20));
    }

    @Test
    void singleUnit_isItsOwnBoxWithSortedSides() {
        assertThat(ParcelCalculator.calculate(List.of(item(1, 500, 10, 30, 20))))
                .isEqualTo(new Parcel(500, 30, 20, 10));
    }

    @Test
    void sidesInAnyOrder_giveTheSameParcel() {
        Parcel expected = ParcelCalculator.calculate(List.of(item(2, 100, 22, 12, 6), item(1, 100, 40, 30, 8)));

        assertThat(ParcelCalculator.calculate(List.of(item(2, 100, 6, 22, 12), item(1, 100, 8, 40, 30))))
                .isEqualTo(expected);
        assertThat(ParcelCalculator.calculate(List.of(item(1, 100, 30, 8, 40), item(2, 100, 12, 6, 22))))
                .isEqualTo(expected);
    }

    @Test
    void quantity_isExpandedIntoUnits() {
        Parcel parcel = ParcelCalculator.calculate(List.of(item(3, 250, 20, 15, 5)));

        assertThat(parcel).isEqualTo(new Parcel(750, 20, 15, 15));
    }

    @Test
    void lengthAndWidth_areTakenFromDifferentItemsWhenNeeded() {
        // A long thin item and a wide flat one: length from the first, width from the second
        Parcel parcel = ParcelCalculator.calculate(List.of(item(1, 100, 100, 5, 5), item(1, 100, 30, 30, 2)));

        assertThat(parcel).isEqualTo(new Parcel(200, 100, 30, 7));
    }

    @Test
    void overflow_isReportedInsteadOfAWrongNumber() {
        assertThatThrownBy(() -> ParcelCalculator.calculate(List.of(item(Integer.MAX_VALUE, 2, 1, 1, 1))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasCauseInstanceOf(ArithmeticException.class);
        assertThatThrownBy(() -> ParcelCalculator.calculate(List.of(
                cube(Integer.MAX_VALUE), cube(Integer.MAX_VALUE))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void emptyOrMissingList_isRejected() {
        assertThatThrownBy(() -> ParcelCalculator.calculate(List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ParcelCalculator.calculate(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nonPositiveValues_areRejected() {
        assertThatThrownBy(() -> item(0, 100, 1, 1, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> item(1, 0, 1, 1, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> item(1, 100, -1, 1, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> item(1, 100, 1, 0, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> item(1, 100, 1, 1, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    // Every side is the smallest side too, so two of them overflow the stacked height
    private static ItemPackaging cube(int side) {
        return item(1, 1, side, side, side);
    }

    private static ItemPackaging item(int quantity, int weight, int length, int width, int height) {
        return new ItemPackaging(UUID.randomUUID(), quantity, weight, length, width, height);
    }
}
