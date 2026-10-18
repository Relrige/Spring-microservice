package ua.edu.ukma.springers.voltstore.notification.email;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;

/**
 * The texts of the six emails (spec EP-NOTIF-EVT-01…05), rendered when the event is consumed. Pure: no I/O, so it
 * is unit-tested without Spring.
 * <p>
 * Text blocks in code rather than template files or a template engine: there are six short plain-text messages
 * with a few placeholders each, the compiler checks every placeholder's argument, and a text change is reviewed
 * together with its test. Template files would only pay off with translations or non-developers editing the texts.
 * <p>
 * Bodies contain no address or other personal data: only the order number, amounts, times and the tracking number.
 */
@Component
public class EmailRenderer {
    private static final String SUBJECT_PREFIX = "VoltStore — ";
    private static final String SIGNATURE = """

            —
            VoltStore
            This is an automated message, please do not reply.
            """;

    private final ZoneId timeZone;
    private final DateTimeFormatter timeFormat;

    public EmailRenderer(@Value("${notification.time-zone}") ZoneId timeZone) {
        this.timeZone = timeZone;
        this.timeFormat = DateTimeFormatter.ofPattern("d MMMM yyyy, HH:mm", Locale.ENGLISH).withZone(timeZone);
    }

    public RenderedEmail orderPlaced(UUID orderId, BigDecimal totalAmount, String currency,
                                     Instant reservationExpiresAt) {
        return email("Order #" + orderId + " placed, awaiting payment", """
                Hello,

                thank you for your order #%s at VoltStore.

                Order total: %s

                Your order is awaiting payment. Please pay for it in your account (My orders) before %s.
                If it is not paid by then, the order is cancelled automatically and the reserved items are released.
                """.formatted(orderId, amount(totalAmount, currency), time(reservationExpiresAt)));
    }

    public RenderedEmail paymentReceived(UUID orderId, BigDecimal totalAmount, String currency, Instant paidAt) {
        return email("Payment received for order #" + orderId, """
                Hello,

                we have received your payment for order #%s.

                Amount paid: %s
                Paid at: %s

                Your parcel is now being prepared. You will receive the tracking number as soon as it is shipped.
                """.formatted(orderId, amount(totalAmount, currency), time(paidAt)));
    }

    public RenderedEmail orderShipped(UUID orderId, String ttn) {
        return email("Order #" + orderId + " has been shipped", """
                Hello,

                your order #%s has been handed over to the carrier.

                Tracking number (TTN): %s

                You can follow the parcel with this number on the carrier's website.
                """.formatted(orderId, ttn));
    }

    public RenderedEmail orderDelivered(UUID orderId, String ttn) {
        return email("Order #" + orderId + " delivered", """
                Hello,

                your order #%s (tracking number %s) has been delivered.

                Thank you for shopping at VoltStore. We hope you enjoy your purchase!
                """.formatted(orderId, ttn));
    }

    // Used for unpaid and paid orders alike: the event does not say whether a refund was made (spec D3)
    public RenderedEmail orderCancelled(UUID orderId) {
        return email("Order #" + orderId + " cancelled", """
                Hello,

                your order #%s has been cancelled.

                If the order was already paid, the refund has been initiated. \
                It may take a few business days to arrive.
                """.formatted(orderId));
    }

    public RenderedEmail orderExpired(UUID orderId) {
        return email("Order #" + orderId + " expired, payment deadline missed", """
                Hello,

                your order #%s was not paid in time, so it has expired.

                The reserved items have been released and nothing was charged. \
                You are welcome to place a new order at any time.
                """.formatted(orderId));
    }

    private static RenderedEmail email(String subject, String body) {
        return new RenderedEmail(SUBJECT_PREFIX + subject, body + SIGNATURE);
    }

    // Two decimals and the currency code, never scientific notation (1E+3)
    private static String amount(BigDecimal amount, String currency) {
        return amount.setScale(2, RoundingMode.HALF_UP).toPlainString() + " " + currency;
    }

    // e.g. "25 October 2026, 03:30 (Europe/Kyiv)"
    private String time(Instant instant) {
        return timeFormat.format(instant) + " (" + timeZone.getId() + ")";
    }
}
