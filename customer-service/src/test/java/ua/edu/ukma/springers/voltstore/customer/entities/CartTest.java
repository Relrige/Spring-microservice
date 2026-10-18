package ua.edu.ukma.springers.voltstore.customer.entities;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import ua.edu.ukma.springers.voltstore.customer.exceptions.CartItemNotFoundException;
import ua.edu.ukma.springers.voltstore.customer.exceptions.CartRuleViolation;
import ua.edu.ukma.springers.voltstore.customer.exceptions.CartRuleViolationException;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CartTest {
    private static final UUID A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID B = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID C = UUID.fromString("00000000-0000-0000-0000-00000000000c");

    private final Cart cart = Cart.empty(UUID.randomUUID());

    @Nested
    class Add {
        @Test
        void newProduct_addsALine() {
            cart.add(A, 2);

            assertThat(cart.lines()).containsExactly(new CartLine(A, 2));
        }

        @Test
        void existingProduct_incrementsItsLine() {
            cart.add(A, 2);
            cart.add(A, 3);

            assertThat(cart.lines()).containsExactly(new CartLine(A, 5));
        }

        @Test
        void incrementToExactlyTen_isAllowed() {
            cart.add(A, 4);
            cart.add(A, 6);

            assertThat(cart.lines()).containsExactly(new CartLine(A, 10));
        }

        @Test
        void incrementPastTen_isRejected_andChangesNothing() {
            cart.add(A, 4);

            assertViolation(() -> cart.add(A, 7), CartRuleViolation.QUANTITY_LIMIT_EXCEEDED);
            assertThat(cart.lines()).containsExactly(new CartLine(A, 4));
        }

        @Test
        void incrementWithHugeQuantity_doesNotOverflow() {
            cart.add(A, 1);

            assertViolation(() -> cart.add(A, Integer.MAX_VALUE), CartRuleViolation.QUANTITY_LIMIT_EXCEEDED);
        }

        @Test
        void newProductWithQuantityEleven_isRejected() {
            assertViolation(() -> cart.add(A, 11), CartRuleViolation.QUANTITY_LIMIT_EXCEEDED);
            assertThat(cart.lines()).isEmpty();
        }

        @Test
        void tenthProduct_isAllowed_eleventhIsRejected() {
            List<UUID> products = products(10);
            products.forEach(id -> cart.add(id, 1));
            assertThat(cart.lines()).hasSize(10);

            CartRuleViolationException ex = assertViolation(() -> cart.add(UUID.randomUUID(), 1),
                    CartRuleViolation.TOO_MANY_PRODUCTS);
            assertThat(ex.getDetails()).containsEntry("resultingProductCount", 11).containsEntry("maxProducts", 10);
            assertThat(cart.lines()).hasSize(10);
        }

        @Test
        void fullCart_canStillIncrementAnExistingLine() {
            List<UUID> products = products(10);
            products.forEach(id -> cart.add(id, 1));

            cart.add(products.getFirst(), 1);

            assertThat(cart.lines()).hasSize(10).contains(new CartLine(products.getFirst(), 2));
        }

        @Test
        void quantityBelowOne_isAProgrammingError() {
            assertThatThrownBy(() -> cart.add(A, 0)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    class SetQuantity {
        @Test
        void existingLine_getsTheExactValue() {
            cart.add(A, 7);

            cart.setQuantity(A, 2);

            assertThat(cart.lines()).containsExactly(new CartLine(A, 2));
        }

        @Test
        void ten_isAllowed_elevenIsRejected() {
            cart.add(A, 1);
            cart.setQuantity(A, 10);

            assertViolation(() -> cart.setQuantity(A, 11), CartRuleViolation.QUANTITY_LIMIT_EXCEEDED);
            assertThat(cart.lines()).containsExactly(new CartLine(A, 10));
        }

        @Test
        void missingLine_isNotFound() {
            assertThatThrownBy(() -> cart.setQuantity(A, 1)).isInstanceOf(CartItemNotFoundException.class);
        }
    }

    @Nested
    class Remove {
        @Test
        void removesTheLine_andIsIdempotent() {
            cart.add(A, 1);
            cart.add(B, 1);

            cart.remove(A);
            cart.remove(A);

            assertThat(cart.lines()).containsExactly(new CartLine(B, 1));
        }
    }

    @Nested
    class ReplaceAll {
        @Test
        void replacesEveryLine_keepingUpdatingAndRemoving() {
            cart.add(A, 1);
            cart.add(B, 1);

            cart.replaceAll(List.of(new CartLine(B, 5), new CartLine(C, 2)));

            assertThat(cart.lines()).containsExactly(new CartLine(B, 5), new CartLine(C, 2));
        }

        @Test
        void emptyList_emptiesTheCart() {
            cart.add(A, 1);

            cart.replaceAll(List.of());

            assertThat(cart.lines()).isEmpty();
        }

        @Test
        void tenLines_areAllowed_elevenAreRejected() {
            cart.replaceAll(lines(10, 1));
            assertThat(cart.lines()).hasSize(10);

            CartRuleViolationException ex = assertViolation(() -> cart.replaceAll(lines(11, 1)),
                    CartRuleViolation.TOO_MANY_PRODUCTS);
            assertThat(ex.getDetails()).containsEntry("resultingProductCount", 11);
            assertThat(cart.lines()).hasSize(10);
        }

        @Test
        void quantityOverTen_isRejected_listingEveryOffendingProduct() {
            cart.add(A, 1);

            CartRuleViolationException ex = assertViolation(() -> cart.replaceAll(
                            List.of(new CartLine(A, 11), new CartLine(B, 10), new CartLine(C, 12))),
                    CartRuleViolation.QUANTITY_LIMIT_EXCEEDED);
            assertThat(ex.getDetails()).containsEntry("productIds", List.of(A, C));
            assertThat(cart.lines()).containsExactly(new CartLine(A, 1));
        }

        @Test
        void duplicateProduct_isRejected() {
            cart.add(A, 1);

            CartRuleViolationException ex = assertViolation(() -> cart.replaceAll(
                    List.of(new CartLine(B, 1), new CartLine(B, 2))), CartRuleViolation.DUPLICATE_PRODUCT);
            assertThat(ex.getDetails()).containsEntry("productIds", List.of(B));
            assertThat(cart.lines()).containsExactly(new CartLine(A, 1));
        }
    }

    @Nested
    class MergeFrom {
        @Test
        void disjointLines_areAdded() {
            cart.add(A, 1);

            cart.mergeFrom(List.of(new CartLine(B, 2)));

            assertThat(cart.lines()).containsExactly(new CartLine(A, 1), new CartLine(B, 2));
        }

        @Test
        void overlappingProducts_areSummed_upToExactlyTen() {
            cart.add(A, 4);
            cart.add(B, 1);

            cart.mergeFrom(List.of(new CartLine(A, 6), new CartLine(C, 3)));

            assertThat(cart.lines()).containsExactly(new CartLine(A, 10), new CartLine(B, 1), new CartLine(C, 3));
        }

        @Test
        void sumsOverTen_areRejected_listingEveryOffendingProduct() {
            cart.add(A, 6);
            cart.add(B, 9);
            cart.add(C, 1);

            CartRuleViolationException ex = assertViolation(() -> cart.mergeFrom(
                            List.of(new CartLine(A, 5), new CartLine(B, 2), new CartLine(C, 1))),
                    CartRuleViolation.QUANTITY_LIMIT_EXCEEDED);
            assertThat(ex.getDetails()).containsEntry("productIds", List.of(A, B));
            assertThat(cart.lines()).containsExactly(new CartLine(A, 6), new CartLine(B, 9), new CartLine(C, 1));
        }

        @Test
        void resultWithElevenProducts_isRejected_countingTheMergedResult() {
            List<CartLine> existing = lines(6, 1);
            existing.forEach(line -> cart.add(line.productId(), 1));
            List<CartLine> incoming = new ArrayList<>(lines(5, 1));
            incoming.add(existing.getFirst());

            CartRuleViolationException ex = assertViolation(() -> cart.mergeFrom(incoming),
                    CartRuleViolation.TOO_MANY_PRODUCTS);
            assertThat(ex.getDetails()).containsEntry("resultingProductCount", 11);
            assertThat(cart.lines()).hasSize(6);
        }

        @Test
        void emptyList_changesNothing() {
            cart.add(A, 3);

            cart.mergeFrom(List.of());

            assertThat(cart.lines()).containsExactly(new CartLine(A, 3));
        }

        @Test
        void duplicateProduct_isRejected() {
            assertViolation(() -> cart.mergeFrom(List.of(new CartLine(A, 1), new CartLine(A, 1))),
                    CartRuleViolation.DUPLICATE_PRODUCT);
        }
    }

    @Test
    void clear_removesEveryLine() {
        cart.add(A, 1);
        cart.add(B, 2);

        cart.clear();

        assertThat(cart.lines()).isEmpty();
    }

    @Test
    void removeAll_removesOnlyTheGivenProducts_andSkipsMissingOnes() {
        cart.add(A, 1);
        cart.add(B, 2);
        cart.add(C, 3);

        cart.removeAll(List.of(A, C, UUID.randomUUID()));

        assertThat(cart.lines()).containsExactly(new CartLine(B, 2));
        cart.removeAll(List.of(A, C));
        assertThat(cart.lines()).containsExactly(new CartLine(B, 2));
    }

    @Test
    void lines_areOrderedByProductId() {
        cart.add(C, 1);
        cart.add(A, 1);
        cart.add(B, 1);

        assertThat(cart.lines()).extracting(CartLine::productId).containsExactly(A, B, C);
    }

    @Test
    void changes_moveUpdatedAtForward() throws InterruptedException {
        var before = cart.getUpdatedAt();
        Thread.sleep(2);

        cart.add(A, 1);

        assertThat(cart.getUpdatedAt()).isAfter(before);
    }

    private static CartRuleViolationException assertViolation(Runnable action, CartRuleViolation violation) {
        CartRuleViolationException ex = (CartRuleViolationException) org.assertj.core.api.Assertions
                .catchThrowableOfType(CartRuleViolationException.class, action::run);
        assertThat(ex).as("expected " + violation).isNotNull();
        assertThat(ex.getViolation()).isEqualTo(violation);
        return ex;
    }

    private static List<UUID> products(int count) {
        return IntStream.range(0, count).mapToObj(i -> UUID.randomUUID()).toList();
    }

    private static List<CartLine> lines(int count, int quantity) {
        return products(count).stream().map(id -> new CartLine(id, quantity)).toList();
    }
}
