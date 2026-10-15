package ua.edu.ukma.springers.voltstore.delivery.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

/**
 * A clock the test moves by hand, so back-offs are "waited" without sleeping. Starts at the real current time,
 * truncated to microseconds (the precision of PostgreSQL timestamps), so stored values compare equal.
 */
public class MutableClock extends Clock {
    private volatile Instant now = realNow();

    // Replaces the service's system clock in a Spring test context
    @TestConfiguration(proxyBeanMethods = false)
    public static class Config {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock();
        }
    }

    public void advance(Duration duration) {
        now = now.plus(duration);
    }

    public void reset() {
        now = realNow();
    }

    private static Instant realNow() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    @Override
    public Instant instant() {
        return now;
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }
}
