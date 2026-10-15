package ua.edu.ukma.springers.voltstore.delivery.carrier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The scheduler is a mock: a test captures what the gateway scheduled and runs it by hand, so "after the delay"
 * needs no waiting.
 */
@SuppressWarnings("unchecked")
class MockCarrierGatewayTest {
    private static final Instant NOW = Instant.parse("2026-10-15T12:00:00Z");
    private static final Duration DELAY = Duration.ofSeconds(30);
    private static final ConsignmentRequest REQUEST = new ConsignmentRequest(UUID.randomUUID(), "NOVA_POSHTA",
            new Recipient("Olena", "+380501234567", "Kyiv"), new Parcel(1500, 40, 30, 20),
            new BigDecimal("1500.00"), "UAH");

    private final TaskScheduler scheduler = mock(TaskScheduler.class);
    private final CarrierEventHandler handler = mock(CarrierEventHandler.class);
    private final ObjectProvider<CarrierEventHandler> handlerProvider = mock(ObjectProvider.class);

    @BeforeEach
    void setUp() {
        when(handlerProvider.getObject()).thenReturn(handler);
    }

    @Test
    void register_returnsA14DigitTtn() {
        MockCarrierGateway gateway = gateway(0.0);

        List<String> ttns = IntStream.range(0, 100).mapToObj(i -> gateway.register(REQUEST)).toList();

        assertThat(ttns).allMatch(ttn -> ttn.matches("[1-9]\\d{13}"));
        assertThat(ttns).doesNotHaveDuplicates();
    }

    @Test
    void failureRateZero_neverFails() {
        MockCarrierGateway gateway = gateway(0.0);

        for (int i = 0; i < 1000; i++) {
            gateway.register(REQUEST);
            gateway.cancel("20450000000001");
        }
    }

    @Test
    void failureRateOne_alwaysFails() {
        MockCarrierGateway gateway = gateway(1.0);

        for (int i = 0; i < 100; i++) {
            assertThatThrownBy(() -> gateway.register(REQUEST)).isInstanceOf(CarrierException.class);
            assertThatThrownBy(() -> gateway.cancel("20450000000001")).isInstanceOf(CarrierException.class);
        }
    }

    @Test
    void handOver_neverFails_andReportsTheDeliveryOnceAfterTheDelay() {
        MockCarrierGateway gateway = gateway(1.0);

        gateway.handOver("20450000000001");

        Runnable report = scheduledAt(NOW.plus(DELAY));
        verify(handler, never()).onDelivered(any());
        report.run();
        verify(handler).onDelivered("20450000000001");
    }

    @Test
    void secondHandOverOfTheSameTtn_schedulesNothingNew() {
        MockCarrierGateway gateway = gateway(0.0);

        gateway.handOver("20450000000001");
        gateway.handOver("20450000000001");

        verify(scheduler, times(1)).schedule(any(Runnable.class), any(Instant.class));
    }

    @Test
    void throwingHandler_isCalledAgainAfterTheSameDelay() {
        MockCarrierGateway gateway = gateway(0.0);
        doThrow(new IllegalStateException("database down")).doNothing().when(handler).onDelivered("20450000000001");
        gateway.handOver("20450000000001");

        scheduledAt(NOW.plus(DELAY)).run();

        ArgumentCaptor<Runnable> retry = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler, times(2)).schedule(retry.capture(), any(Instant.class));
        retry.getAllValues().get(1).run();
        verify(handler, times(2)).onDelivered("20450000000001");
    }

    @Test
    void invalidConfiguration_failsAtStartup() {
        new ApplicationContextRunner()
                .withUserConfiguration(PropertiesOnly.class)
                .withPropertyValues("delivery.carrier.mock.failure-rate=1.5", "delivery.carrier.mock.latency=0",
                        "delivery.carrier.mock.delivery-delay=30s")
                .run(context -> assertThat(context).hasFailed());
        new ApplicationContextRunner()
                .withUserConfiguration(PropertiesOnly.class)
                .withPropertyValues("delivery.carrier.mock.failure-rate=0.5", "delivery.carrier.mock.latency=0",
                        "delivery.carrier.mock.delivery-delay=30s")
                .run(context -> assertThat(context).hasNotFailed()
                        .getBean(MockCarrierProperties.class).extracting(MockCarrierProperties::failureRate)
                        .isEqualTo(0.5));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(MockCarrierProperties.class)
    static class PropertiesOnly {
    }

    private MockCarrierGateway gateway(double failureRate) {
        return new MockCarrierGateway(new MockCarrierProperties(failureRate, Duration.ZERO, DELAY), new Random(42),
                scheduler, Clock.fixed(NOW, ZoneOffset.UTC), handlerProvider);
    }

    private Runnable scheduledAt(Instant expected) {
        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        ArgumentCaptor<Instant> at = ArgumentCaptor.forClass(Instant.class);
        verify(scheduler).schedule(task.capture(), at.capture());
        assertThat(at.getValue()).isEqualTo(expected);
        return task.getValue();
    }
}
