package ua.edu.ukma.springers.voltstore.notification.jobs;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.List;

/**
 * Settings of the sender job ({@code notification.sender}); the run interval is read by {@code @Scheduled}.
 *
 * @param batchSize notifications claimed per run
 * @param lease     how far a claim moves {@code next_attempt_at} forward, so no other replica picks the row while this
 *                  attempt runs; far longer than the SMTP timeouts
 * @param backoff   waits before the retries after the first, second, ... failed attempt; one more failure after the
 *                  last of them makes the notification {@code FAILED} (4 back-offs → 5 attempts)
 */
@Validated
@ConfigurationProperties(prefix = "notification.sender")
public record SenderJobProperties(
        @Positive int batchSize,
        @NotNull Duration lease,
        @NotEmpty List<@NotNull Duration> backoff
) {
}
