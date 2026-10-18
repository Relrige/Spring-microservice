package ua.edu.ukma.springers.voltstore.notification.email;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class EmailRendererTest {
    private static final UUID ORDER_ID = UUID.fromString("7f6c2a1e-1d2b-4c3d-8e9f-0a1b2c3d4e5f");
    private final EmailRenderer renderer = new EmailRenderer(ZoneId.of("Europe/Kyiv"));

    @Test
    void orderPlaced_givesTotalAndPaymentDeadline() {
        RenderedEmail email = renderer.orderPlaced(ORDER_ID, new BigDecimal("1500"), "UAH",
                Instant.parse("2026-07-01T12:00:00Z"));

        assertThat(email.subject()).isEqualTo("VoltStore — Order #" + ORDER_ID + " placed, awaiting payment");
        assertThat(email.body())
                .contains("#" + ORDER_ID)
                .contains("Order total: 1500.00 UAH")
                .contains("My orders")
                // Summer time: UTC+3
                .contains("before 1 July 2026, 15:00 (Europe/Kyiv)")
                .contains("cancelled automatically");
    }

    @Test
    void deadline_isShownInKyivTime_acrossTheDaylightSavingChange() {
        // Kyiv leaves summer time on 25 October 2026 at 01:00 UTC: 00:30 UTC is 03:30 (UTC+3), 01:30 UTC is 03:30
        // again (UTC+2)
        String beforeChange = renderer.orderPlaced(ORDER_ID, BigDecimal.ONE, "UAH",
                Instant.parse("2026-10-25T00:30:00Z")).body();
        String afterChange = renderer.orderPlaced(ORDER_ID, BigDecimal.ONE, "UAH",
                Instant.parse("2026-10-25T01:30:00Z")).body();
        String winter = renderer.orderPlaced(ORDER_ID, BigDecimal.ONE, "UAH",
                Instant.parse("2026-12-01T12:00:00Z")).body();

        assertThat(beforeChange).contains("25 October 2026, 03:30");
        assertThat(afterChange).contains("25 October 2026, 03:30");
        assertThat(winter).contains("1 December 2026, 14:00");
    }

    @Test
    void paymentReceived_isAReceiptWithAmountAndTime() {
        RenderedEmail email = renderer.paymentReceived(ORDER_ID, new BigDecimal("1499.9"), "UAH",
                Instant.parse("2026-10-15T12:00:00Z"));

        assertThat(email.subject()).isEqualTo("VoltStore — Payment received for order #" + ORDER_ID);
        assertThat(email.body())
                .contains("#" + ORDER_ID)
                .contains("Amount paid: 1499.90 UAH")
                .contains("Paid at: 15 October 2026, 15:00 (Europe/Kyiv)")
                .contains("tracking number");
    }

    @Test
    void amounts_neverUseScientificNotation() {
        String body = renderer.paymentReceived(ORDER_ID, new BigDecimal("1E+3"), "UAH", Instant.EPOCH).body();

        assertThat(body).contains("Amount paid: 1000.00 UAH").doesNotContain("E+");
    }

    @Test
    void orderShipped_givesTheTtnButNoCarrier() {
        RenderedEmail email = renderer.orderShipped(ORDER_ID, "20450000123456");

        assertThat(email.subject()).isEqualTo("VoltStore — Order #" + ORDER_ID + " has been shipped");
        assertThat(email.body()).contains("#" + ORDER_ID).contains("Tracking number (TTN): 20450000123456")
                .doesNotContainIgnoringCase("nova poshta");
    }

    @Test
    void orderDelivered_confirmsWithTheTtn() {
        RenderedEmail email = renderer.orderDelivered(ORDER_ID, "20450000123456");

        assertThat(email.subject()).isEqualTo("VoltStore — Order #" + ORDER_ID + " delivered");
        assertThat(email.body()).contains("#" + ORDER_ID).contains("20450000123456").contains("has been delivered")
                .contains("Thank you");
    }

    @Test
    void orderCancelled_mentionsTheRefundOnlyConditionally() {
        RenderedEmail email = renderer.orderCancelled(ORDER_ID);

        assertThat(email.subject()).isEqualTo("VoltStore — Order #" + ORDER_ID + " cancelled");
        assertThat(email.body()).contains("#" + ORDER_ID)
                .contains("If the order was already paid, the refund has been initiated. It may take a few business "
                        + "days to arrive.");
    }

    @Test
    void orderExpired_saysNothingWasCharged() {
        RenderedEmail email = renderer.orderExpired(ORDER_ID);

        assertThat(email.subject())
                .isEqualTo("VoltStore — Order #" + ORDER_ID + " expired, payment deadline missed");
        assertThat(email.body()).contains("#" + ORDER_ID).contains("nothing was charged")
                .contains("items have been released");
    }

    @Test
    void everyEmail_isPlainTextWithTheSignature() {
        List<RenderedEmail> emails = List.of(
                renderer.orderPlaced(ORDER_ID, BigDecimal.TEN, "UAH", Instant.EPOCH),
                renderer.paymentReceived(ORDER_ID, BigDecimal.TEN, "UAH", Instant.EPOCH),
                renderer.orderShipped(ORDER_ID, "1"),
                renderer.orderDelivered(ORDER_ID, "1"),
                renderer.orderCancelled(ORDER_ID),
                renderer.orderExpired(ORDER_ID));

        assertThat(emails).allSatisfy(email -> {
            assertThat(email.subject()).startsWith("VoltStore — ");
            assertThat(email.body()).startsWith("Hello,").contains("VoltStore\nThis is an automated message")
                    .doesNotContain("<", "@", "%s");
        });
    }
}
