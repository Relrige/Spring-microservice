package ua.edu.ukma.springers.voltstore.payment.services;

import org.junit.jupiter.api.Test;
import ua.edu.ukma.springers.voltstore.payment.gateway.MockPaymentGateway;
import ua.edu.ukma.springers.voltstore.payment.gateway.PaymentGateway;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Anti-Corruption Layer guard: the service depends on the {@link PaymentGateway} port only, never on an adapter, so
 * a real provider can replace the mock without touching the business logic.
 */
class GatewayIsolationTest {

    @Test
    void paymentService_dependsOnThePortOnly() {
        Stream<Class<?>> dependencies = Stream.concat(
                Arrays.stream(PaymentService.class.getDeclaredFields()).map(Field::getType),
                Arrays.stream(PaymentService.class.getDeclaredConstructors())
                        .map(Constructor::getParameterTypes).flatMap(Arrays::stream));

        assertThat(dependencies.toList())
                .contains(PaymentGateway.class)
                .noneMatch(type -> type != PaymentGateway.class && PaymentGateway.class.isAssignableFrom(type))
                .doesNotContain(MockPaymentGateway.class);
    }
}
