package ua.edu.ukma.springers.voltstore.payment.gateway;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentCardTest {
    private static final PaymentCard CARD = new PaymentCard("4242424242424242", 7, 2031, "987");

    @Test
    void toString_showsOnlyTheLastFourDigits() {
        assertThat(CARD.toString()).isEqualTo("PaymentCard[**** 4242]")
                .doesNotContain("4242424242424242").doesNotContain("987").doesNotContain("2031");
    }

    @Test
    void chargeCommand_printsTheCardMasked() {
        ChargeCommand command = new ChargeCommand("k1", BigDecimal.TEN, "UAH", CARD);

        assertThat(command.toString()).contains("**** 4242").doesNotContain("4242424242424242").doesNotContain("987");
    }
}
