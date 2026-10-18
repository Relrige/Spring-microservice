package ua.edu.ukma.springers.voltstore.order.clients;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import ua.edu.ukma.springers.voltstore.order.clients.ClientsProperties.Endpoint;

import java.time.Duration;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ClientsPropertiesTest {
    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void aPaymentReadTimeoutShorterThan6s_isRejected() {
        Set<ConstraintViolation<ClientsProperties>> violations = VALIDATOR.validate(withPaymentReadTimeout("5s"));

        assertThat(violations).extracting(ConstraintViolation::getMessage)
                .containsExactly("clients.payment.read-timeout must be at least 6s (Payment's own worst case is about 4.5 s)");
    }

    @Test
    void sixSecondsOrMore_isAccepted() {
        assertThat(VALIDATOR.validate(withPaymentReadTimeout("6s"))).isEmpty();
        assertThat(VALIDATOR.validate(withPaymentReadTimeout("8s"))).isEmpty();
    }

    private static ClientsProperties withPaymentReadTimeout(String readTimeout) {
        Endpoint other = new Endpoint("http://localhost:1", Duration.ofSeconds(2), Duration.ofSeconds(3));
        Endpoint payment = new Endpoint("http://localhost:2", Duration.ofSeconds(2), Duration.parse("PT" + readTimeout.toUpperCase()));
        return new ClientsProperties(other, other, payment, other);
    }
}
