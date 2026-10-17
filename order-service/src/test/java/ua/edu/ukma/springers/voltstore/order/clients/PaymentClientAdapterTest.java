package ua.edu.ukma.springers.voltstore.order.clients;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import ua.edu.ukma.springers.voltstore.order.ports.Payments;
import ua.edu.ukma.springers.voltstore.order.ports.Payments.AlreadyCharged;
import ua.edu.ukma.springers.voltstore.order.ports.Payments.AlreadyRefunded;
import ua.edu.ukma.springers.voltstore.order.ports.Payments.AttemptInProgress;
import ua.edu.ukma.springers.voltstore.order.ports.Payments.ChargeResult;
import ua.edu.ukma.springers.voltstore.order.ports.Payments.ChargeUnavailable;
import ua.edu.ukma.springers.voltstore.order.ports.Payments.Declined;
import ua.edu.ukma.springers.voltstore.order.ports.Payments.Paid;
import ua.edu.ukma.springers.voltstore.order.ports.Payments.PaymentCard;
import ua.edu.ukma.springers.voltstore.order.ports.Payments.RefundFailed;
import ua.edu.ukma.springers.voltstore.order.ports.Payments.Refunded;

import java.math.BigDecimal;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class PaymentClientAdapterTest extends ClientsTestBase {
    private static final String CHARGE = "/payments/charge";
    private static final String REFUND = "/payments/refund";
    private static final UUID ORDER = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID TRANSACTION = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final BigDecimal AMOUNT = new BigDecimal("1499.90");
    private static final PaymentCard CARD = new PaymentCard("4242424242424242", 12, 2035, "987");

    @Autowired
    private Payments payments;

    @Test
    void charge_200_isPaid_andSendsPaymentsRequestShape() {
        stubFor(post(urlEqualTo(CHARGE)).willReturn(okJson(
                "{\"transactionId\":\"" + TRANSACTION + "\",\"status\":\"SUCCESS\"}")));

        assertThat(charge()).isEqualTo(new Paid(TRANSACTION));

        verify(postRequestedFor(urlEqualTo(CHARGE))
                .withHeader("X-Internal-Token", equalTo(TOKEN))
                .withRequestBody(equalToJson("""
                        {"orderId":"%s","amount":1499.90,"currency":"UAH",
                         "cardDetails":{"number":"4242424242424242","expiryMonth":12,"expiryYear":2035,"cvv":"987"}}
                        """.formatted(ORDER))));
    }

    @Test
    void charge_402_isDeclinedWithPaymentsReason() {
        stubProblem(CHARGE, 402, "Payment declined", "\"reason\":\"insufficient_funds\"");

        assertThat(charge()).isEqualTo(new Declined("insufficient_funds"));
    }

    @Test
    void charge_409AlreadyCharged_countsAsPaid() {
        stubProblem(CHARGE, 409, "Order already charged", null);

        assertThat(charge()).isEqualTo(new AlreadyCharged());
    }

    @Test
    void charge_409AttemptInProgress_isToldApartByTheTitle() {
        stubProblem(CHARGE, 409, "Payment attempt in progress", null);

        assertThat(charge()).isEqualTo(new AttemptInProgress());
    }

    @Test
    void charge_504_isUnavailable_andNotRetried() {
        stubFor(post(urlEqualTo(CHARGE)).willReturn(aResponse().withStatus(504)));

        assertThat(charge()).isInstanceOf(ChargeUnavailable.class);
        verify(1, postRequestedFor(urlEqualTo(CHARGE)));
    }

    @Test
    void charge_unexpected400_isUnavailable_notAnException() {
        stubProblem(CHARGE, 400, "Validation failed", null);

        assertThat(charge()).isInstanceOf(ChargeUnavailable.class);
    }

    @Test
    void charge_neverLogsTheCard(CapturedOutput output) {
        stubFor(post(urlEqualTo(CHARGE)).willReturn(aResponse().withStatus(502)));

        charge();

        assertThat(output.getAll()).doesNotContain("4242424242424242").doesNotContain("\"987\"");
        assertThat(CARD.toString()).isEqualTo("PaymentCard[**** **** **** 4242]");
    }

    @Test
    void refund_200_isRefunded_409_isAlreadyRefunded() {
        stubFor(post(urlEqualTo(REFUND)).willReturn(okJson(
                "{\"transactionId\":\"" + TRANSACTION + "\",\"status\":\"SUCCESS\"}")));
        assertThat(payments.refund(ORDER, AMOUNT, "UAH")).isEqualTo(new Refunded());
        verify(postRequestedFor(urlEqualTo(REFUND)).withRequestBody(equalToJson(
                "{\"orderId\":\"" + ORDER + "\",\"amount\":1499.90,\"currency\":\"UAH\"}")));

        stubProblem(REFUND, 409, "Order already refunded", null);
        assertThat(payments.refund(ORDER, AMOUNT, "UAH")).isEqualTo(new AlreadyRefunded());
    }

    @Test
    void refund_rejectedByTheProvider_isFailedWithTheReason() {
        stubProblem(REFUND, 502, "Refund rejected", "\"reason\":\"refund_rejected\"");

        assertThat(payments.refund(ORDER, AMOUNT, "UAH")).isEqualTo(new RefundFailed("refund_rejected"));
    }

    @Test
    void refund_paymentDownOr422_isFailed() {
        stubFor(post(urlEqualTo(REFUND)).willReturn(serviceUnavailable()));
        assertThat(payments.refund(ORDER, AMOUNT, "UAH")).isInstanceOf(RefundFailed.class);

        stubProblem(REFUND, 422, "No successful charge", null);
        assertThat(payments.refund(ORDER, AMOUNT, "UAH")).isInstanceOf(RefundFailed.class);
    }

    private ChargeResult charge() {
        return payments.charge(ORDER, AMOUNT, "UAH", CARD);
    }

    private static void stubProblem(String path, int status, String title, String extraProperty) {
        String body = "{\"status\":" + status + ",\"title\":\"" + title + "\""
                + (extraProperty == null ? "" : "," + extraProperty) + "}";
        stubFor(post(urlEqualTo(path)).willReturn(aResponse().withStatus(status)
                .withHeader("Content-Type", "application/problem+json").withBody(body)));
    }
}
