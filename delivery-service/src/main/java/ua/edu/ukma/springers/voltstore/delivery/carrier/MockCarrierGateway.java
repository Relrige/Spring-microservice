package ua.edu.ukma.springers.voltstore.delivery.carrier;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.random.RandomGenerator;

/**
 * The only {@link CarrierGateway}: an in-process stand-in for a postal carrier (VoltStore has no real one). No
 * network, no extra application.
 * <ul>
 *   <li>{@code register} returns a random 14-digit TTN (the length Ukrainian carriers use); {@code cancel} accepts any
 *   TTN. Both take {@code latency} and fail with probability {@code failureRate}, so the retry paths can be
 *   demonstrated.</li>
 *   <li>{@code handOver} never fails. It schedules the delivery report ({@link CarrierEventHandler#onDelivered}) after
 *   {@code deliveryDelay}; a TTN whose report is already scheduled is not scheduled again. If the handler throws
 *   (e.g. the database is down), the report is rescheduled after the same delay.</li>
 * </ul>
 * The schedule lives in memory and is lost when the pod stops; Delivery hands {@code IN_TRANSIT} parcels over again
 * at startup, which is why {@code handOver} and the report must be idempotent.
 */
@Slf4j
@Component
@EnableConfigurationProperties(MockCarrierProperties.class)
public class MockCarrierGateway implements CarrierGateway {
    private static final long MIN_TTN = 10_000_000_000_000L;
    private static final long MAX_TTN_EXCLUSIVE = 100_000_000_000_000L;

    private final MockCarrierProperties properties;
    private final RandomGenerator random;
    private final TaskScheduler scheduler;
    private final Clock clock;
    // Looked up lazily: the handler is a service that itself depends on this gateway
    private final ObjectProvider<CarrierEventHandler> eventHandler;
    // TTNs whose delivery report is scheduled and not yet accepted
    private final Set<String> scheduledReports = ConcurrentHashMap.newKeySet();

    @Autowired
    public MockCarrierGateway(MockCarrierProperties properties, TaskScheduler scheduler, Clock clock,
                              ObjectProvider<CarrierEventHandler> eventHandler) {
        // SecureRandom is thread-safe: concurrent registrations (job runs, manual retries) must not get the same TTN,
        // which RandomGenerator.getDefault() can produce when shared between threads
        this(properties, new SecureRandom(), scheduler, clock, eventHandler);
    }

    // Tests pass a seeded or stubbed generator, so failures and TTNs are deterministic
    MockCarrierGateway(MockCarrierProperties properties, RandomGenerator random, TaskScheduler scheduler, Clock clock,
                       ObjectProvider<CarrierEventHandler> eventHandler) {
        this.properties = properties;
        this.random = random;
        this.scheduler = scheduler;
        this.clock = clock;
        this.eventHandler = eventHandler;
    }

    @Override
    public String register(ConsignmentRequest request) {
        simulateCall("register", "order " + request.orderId());
        String ttn = Long.toString(random.nextLong(MIN_TTN, MAX_TTN_EXCLUSIVE));
        log.info("Mock carrier: registered order {} ({} g, {}x{}x{} cm, carrier {}) as TTN {}", request.orderId(),
                request.parcel().weightGrams(), request.parcel().lengthCm(), request.parcel().widthCm(),
                request.parcel().heightCm(), request.carrier(), ttn);
        return ttn;
    }

    @Override
    public void cancel(String ttn) {
        simulateCall("cancel", "TTN " + ttn);
        log.info("Mock carrier: cancelled TTN {}", ttn);
    }

    @Override
    public void handOver(String ttn) {
        if (!scheduledReports.add(ttn)) {
            log.info("Mock carrier: TTN {} is already in transit, nothing new scheduled", ttn);
            return;
        }
        log.info("Mock carrier: collected TTN {}, delivery report in {}", ttn, properties.deliveryDelay());
        scheduleReport(ttn);
    }

    private void scheduleReport(String ttn) {
        scheduler.schedule(() -> report(ttn), clock.instant().plus(properties.deliveryDelay()));
    }

    private void report(String ttn) {
        try {
            eventHandler.getObject().onDelivered(ttn);
            // Accepted: forget it, so the set does not grow forever (a later duplicate report is harmless anyway)
            scheduledReports.remove(ttn);
            log.info("Mock carrier: reported TTN {} delivered", ttn);
        } catch (RuntimeException e) {
            log.warn("Mock carrier: delivery report for TTN {} was not accepted, retrying in {}: {}", ttn,
                    properties.deliveryDelay(), e.toString());
            scheduleReport(ttn);
        }
    }

    private void simulateCall(String operation, String subject) {
        if (!properties.latency().isZero()) {
            try {
                Thread.sleep(properties.latency());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new CarrierException("interrupted");
            }
        }
        if (random.nextDouble() < properties.failureRate()) {
            log.info("Mock carrier: {} for {} failed (simulated)", operation, subject);
            throw new CarrierException("simulated carrier failure");
        }
    }
}
