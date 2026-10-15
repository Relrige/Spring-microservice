package ua.edu.ukma.springers.voltstore.payment.gateway;

import org.junit.jupiter.api.Test;
import ua.edu.ukma.springers.voltstore.payment.exceptions.PaymentGatewayTimeoutException;
import ua.edu.ukma.springers.voltstore.payment.support.Concurrently;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MockPaymentGatewayTest {
    private static final BigDecimal AMOUNT = new BigDecimal("100.00");
    private static final PaymentCard APPROVED = card("4242424242424242");
    private static final PaymentCard INSUFFICIENT_FUNDS = card("4000000000000002");
    private static final PaymentCard LOST_ANSWER = card("4000000000005000");

    private final MockPaymentGateway gateway = new MockPaymentGateway(withTimeout(Duration.ZERO));

    @Test
    void card4242_isApproved() {
        GatewayResult result = gateway.charge(charge("k1", APPROVED));

        assertThat(result).isInstanceOfSatisfying(GatewayResult.Approved.class,
                approved -> assertThat(approved.gatewayTransactionId()).startsWith("mock_ch_"));
        assertThat(gateway.approvedChargeCount()).isEqualTo(1);
    }

    @Test
    void card0002_isDeclinedForInsufficientFunds() {
        assertThat(gateway.charge(charge("k1", INSUFFICIENT_FUNDS)))
                .isEqualTo(new GatewayResult.Declined(DeclineReason.INSUFFICIENT_FUNDS));
        assertThat(gateway.approvedChargeCount()).isZero();
    }

    @Test
    void anyOtherCard_isDeclined() {
        assertThat(gateway.charge(charge("k1", card("5555555555554444"))))
                .isEqualTo(new GatewayResult.Declined(DeclineReason.CARD_DECLINED));
    }

    @Test
    void card5000_chargesButLosesTheAnswer_andARepeatWithTheKeyGetsTheApproval() {
        assertThatThrownBy(() -> gateway.charge(charge("k1", LOST_ANSWER)))
                .isInstanceOf(PaymentGatewayTimeoutException.class);
        assertThat(gateway.approvedChargeCount()).as("the money was taken despite the timeout").isEqualTo(1);

        GatewayResult repeat = gateway.charge(charge("k1", LOST_ANSWER));

        assertThat(repeat).isInstanceOf(GatewayResult.Approved.class);
        assertThat(gateway.approvedChargeCount()).as("the repeat did not charge again").isEqualTo(1);
    }

    @Test
    void card5000_waitsForTheConfiguredDelayBeforeReportingTheTimeout() {
        MockPaymentGateway slowGateway = new MockPaymentGateway(withTimeout(Duration.ofMillis(200)));
        long start = System.nanoTime();

        assertThatThrownBy(() -> slowGateway.charge(charge("k1", LOST_ANSWER)))
                .isInstanceOf(PaymentGatewayTimeoutException.class);

        assertThat(Duration.ofNanos(System.nanoTime() - start)).isGreaterThanOrEqualTo(Duration.ofMillis(200));
    }

    @Test
    void repeatWithTheSameKeyAndParameters_returnsTheStoredResultWithoutChargingAgain() {
        GatewayResult first = gateway.charge(charge("k1", APPROVED));

        GatewayResult repeat = gateway.charge(new ChargeCommand("k1", new BigDecimal("100"), "UAH", APPROVED));

        assertThat(repeat).as("100 and 100.00 are the same amount").isEqualTo(first);
        assertThat(gateway.approvedChargeCount()).isEqualTo(1);
    }

    @Test
    void repeatOfADecline_returnsTheSameDecline_soANewAttemptNeedsANewKey() {
        gateway.charge(charge("k1", INSUFFICIENT_FUNDS));

        assertThat(gateway.charge(charge("k1", INSUFFICIENT_FUNDS)))
                .isEqualTo(new GatewayResult.Declined(DeclineReason.INSUFFICIENT_FUNDS));
        assertThat(gateway.charge(charge("k2", APPROVED))).isInstanceOf(GatewayResult.Approved.class);
    }

    @Test
    void sameKeyWithOtherParameters_isAMismatch() {
        gateway.charge(charge("k1", APPROVED));

        assertThat(gateway.charge(charge("k1", card("5555555555554444"))))
                .isInstanceOf(GatewayResult.IdempotencyKeyMismatch.class);
        assertThat(gateway.charge(new ChargeCommand("k1", new BigDecimal("1.00"), "UAH", APPROVED)))
                .isInstanceOf(GatewayResult.IdempotencyKeyMismatch.class);
        assertThat(gateway.approvedChargeCount()).isEqualTo(1);
    }

    @Test
    void concurrentChargesWithOneKey_chargeOnce_andAllGetTheSameResult() throws Exception {
        List<Object> results = Concurrently.run(16, () -> gateway.charge(charge("k1", APPROVED)));

        assertThat(results).allSatisfy(result -> assertThat(result).isInstanceOf(GatewayResult.Approved.class));
        assertThat(results).containsOnly(results.getFirst());
        assertThat(gateway.approvedChargeCount()).isEqualTo(1);
    }

    @Test
    void refundOfAnApprovedCharge_isApprovedOnce() {
        String chargeId = approvedChargeId();

        GatewayResult refund = gateway.refund(new RefundCommand("refund:1", chargeId, AMOUNT, "UAH"));
        GatewayResult repeat = gateway.refund(new RefundCommand("refund:1", chargeId, AMOUNT, "UAH"));

        assertThat(refund).isInstanceOfSatisfying(GatewayResult.Approved.class,
                approved -> assertThat(approved.gatewayTransactionId()).startsWith("mock_re_"));
        assertThat(repeat).isEqualTo(refund);
        assertThat(gateway.approvedRefundCount()).isEqualTo(1);
    }

    @Test
    void refundOfAnUnknownCharge_isRejected() {
        assertThat(gateway.refund(new RefundCommand("refund:1", "mock_ch_unknown", AMOUNT, "UAH")))
                .isEqualTo(new GatewayResult.Declined(DeclineReason.REFUND_REJECTED));
        assertThat(gateway.approvedRefundCount()).isZero();
    }

    private String approvedChargeId() {
        return ((GatewayResult.Approved) gateway.charge(charge("charge:1", APPROVED))).gatewayTransactionId();
    }

    private static GatewayProperties withTimeout(Duration timeout) {
        return new GatewayProperties("mock", timeout, 0, Duration.ZERO);
    }

    private static ChargeCommand charge(String key, PaymentCard card) {
        return new ChargeCommand(key, AMOUNT, "UAH", card);
    }

    private static PaymentCard card(String number) {
        return new PaymentCard(number, 12, 2030, "123");
    }
}
