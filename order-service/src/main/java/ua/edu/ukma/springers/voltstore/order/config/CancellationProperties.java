package ua.edu.ukma.springers.voltstore.order.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.List;

/**
 * Finishing interrupted cancellations (EP-ORD-JOB-02, decision D3).
 *
 * @param retryInterval        how often the job looks for interrupted cancellations
 * @param batchSize            how many of them one run looks at, the longest waiting first
 * @param backoff              wait after the request or the n-th failed attempt; the last value repeats forever
 * @param manualAttentionAfter failed attempts after which the order is flagged for a person (retries continue)
 */
@Validated
@ConfigurationProperties(prefix = "order.cancellation")
public record CancellationProperties(@NotNull Duration retryInterval, @Min(1) int batchSize,
                                     @NotEmpty List<Duration> backoff, @Min(1) int manualAttentionAfter) {

    /** How long to wait after the last attempt, given the failed attempts so far (0 = only the first run). */
    public Duration backoffAfter(int failedAttempts) {
        return backoff.get(Math.min(Math.max(failedAttempts - 1, 0), backoff.size() - 1));
    }
}
