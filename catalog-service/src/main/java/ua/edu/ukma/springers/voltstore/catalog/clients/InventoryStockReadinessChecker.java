package ua.edu.ukma.springers.voltstore.catalog.clients;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.retry.RetryException;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import ua.edu.ukma.springers.voltstore.catalog.exceptions.InventoryUnavailableException;
import ua.edu.ukma.springers.voltstore.catalog.services.StockReadiness;
import ua.edu.ukma.springers.voltstore.catalog.services.StockReadinessChecker;

import java.time.Duration;
import java.util.UUID;

/**
 * Asks Inventory Service (EP-INV-04) whether a product may be activated.
 * <ul>
 *   <li>{@code 204} → ready; {@code 404} → not ready, {@code stock_item_not_found}; {@code 422 { reason }} →
 *   not ready with that reason (unknown codes pass through). These are answers, never retried.</li>
 *   <li>Connection error, timeout or {@code 5xx} → transient: retried {@code maxRetries} times (task 22), then
 *   {@link InventoryUnavailableException} (503).</li>
 *   <li>Any other status (e.g. {@code 401}/{@code 403}: wrong token or route rule) → configuration problem,
 *   logged as an error, not retried, {@link InventoryUnavailableException}.</li>
 * </ul>
 */
@Slf4j
@Component
public class InventoryStockReadinessChecker implements StockReadinessChecker {
    static final String STOCK_ITEM_NOT_FOUND = "stock_item_not_found";
    // A 422 without a reason still means "not ready"; Catalog must not invent a 503 for it
    static final String UNKNOWN_REASON = "unknown";

    private final InventoryClient inventoryClient;
    private final RetryTemplate retryTemplate;

    public InventoryStockReadinessChecker(InventoryClient inventoryClient, InventoryClientProperties properties) {
        this.inventoryClient = inventoryClient;
        // Spring Framework 7's built-in retry (no extra library); only TransientFailure is retried
        Duration retryDelay = properties.retryDelay();
        this.retryTemplate = new RetryTemplate(RetryPolicy.builder()
                .maxRetries(properties.maxRetries())
                .delay(retryDelay)
                .jitter(retryDelay.dividedBy(2))
                .includes(TransientFailure.class)
                .build());
    }

    @Override
    public StockReadiness check(UUID productId) {
        try {
            return retryTemplate.execute(() -> checkOnce(productId));
        } catch (RetryException e) {
            Throwable last = e.getLastException();
            if (last instanceof InventoryUnavailableException unavailable) {
                throw unavailable;
            }
            throw new InventoryUnavailableException("Inventory Service is unavailable", last);
        }
    }

    private StockReadiness checkOnce(UUID productId) {
        try {
            inventoryClient.checkReadiness(productId);
            return StockReadiness.isReady();
        } catch (RestClientResponseException e) {
            HttpStatus status = HttpStatus.resolve(e.getStatusCode().value());
            if (status == HttpStatus.NOT_FOUND) {
                return StockReadiness.notReady(STOCK_ITEM_NOT_FOUND);
            }
            if (status == HttpStatus.UNPROCESSABLE_CONTENT) {
                return StockReadiness.notReady(reasonOf(e));
            }
            if (e.getStatusCode().is5xxServerError()) {
                log.warn("Readiness check for product {} failed: Inventory answered {}", productId, e.getStatusCode());
                throw new TransientFailure(e);
            }
            log.error("Readiness check for product {}: unexpected status {} from Inventory "
                    + "(check the internal token and Inventory's route rules)", productId, e.getStatusCode());
            throw new InventoryUnavailableException("Unexpected response from Inventory Service", e);
        } catch (RestClientException e) {
            // Connection refused, connect or read timeout, broken response
            log.warn("Readiness check for product {} failed: {}", productId, e.getMessage());
            throw new TransientFailure(e);
        }
    }

    private static String reasonOf(RestClientResponseException e) {
        try {
            ReadinessProblem problem = e.getResponseBodyAs(ReadinessProblem.class);
            return problem != null && problem.reason() != null && !problem.reason().isBlank()
                    ? problem.reason() : UNKNOWN_REASON;
        } catch (RuntimeException unreadableBody) {
            return UNKNOWN_REASON;
        }
    }

    // A failure worth retrying (the next attempt may succeed)
    private static final class TransientFailure extends RuntimeException {
        TransientFailure(Throwable cause) {
            super(cause);
        }
    }
}
