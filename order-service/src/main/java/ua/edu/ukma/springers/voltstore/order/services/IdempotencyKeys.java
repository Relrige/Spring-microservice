package ua.edu.ukma.springers.voltstore.order.services;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionOperations;
import tools.jackson.databind.json.JsonMapper;
import ua.edu.ukma.springers.voltstore.order.dto.OrderItemRequest;
import ua.edu.ukma.springers.voltstore.order.dto.PlaceOrderRequest;
import ua.edu.ukma.springers.voltstore.order.entities.IdempotencyKey;
import ua.edu.ukma.springers.voltstore.order.exceptions.IdempotencyKeyMismatchException;
import ua.edu.ukma.springers.voltstore.order.exceptions.RequestInProgressException;
import ua.edu.ukma.springers.voltstore.order.repositories.IdempotencyKeyRepository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

/**
 * The {@code Idempotency-Key} of EP-ORD-02 (decisions D4–D9): claim before Catalog is called, complete with the
 * order, release when the request fails. Claim and release are each their own short transaction; completion joins
 * the caller's.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IdempotencyKeys {
    private final IdempotencyKeyRepository keys;
    private final TransactionOperations transactions;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    /**
     * @return empty when this request owns the key and runs; the stored order's ID when the same request already
     * succeeded
     * @throws IdempotencyKeyMismatchException the key was used for another request (checked before the status)
     * @throws RequestInProgressException      the first request with this key is still running
     */
    public Optional<UUID> claim(UUID customerId, UUID key, PlaceOrderRequest request) {
        String hash = hash(request);
        Integer claimed = transactions.execute(tx -> keys.claim(customerId, key, hash, clock.instant()));
        if (claimed != null && claimed == 1) {
            return Optional.empty();
        }
        IdempotencyKey existing = transactions.execute(tx ->
                keys.findById(new IdempotencyKey.Id(customerId, key)).orElse(null));
        if (existing == null) {
            // Released (the first request failed) between the two statements: a retry after Retry-After claims it
            throw new RequestInProgressException();
        }
        if (!existing.getRequestHash().equals(hash)) {
            throw new IdempotencyKeyMismatchException();
        }
        if (existing.getStatus() == IdempotencyKey.Status.IN_PROGRESS) {
            throw new RequestInProgressException();
        }
        return Optional.of(existing.getOrderId());
    }

    /** Joins the transaction that stores the order: either both are committed or neither. */
    public void complete(UUID customerId, UUID key, UUID orderId) {
        if (keys.complete(customerId, key, orderId) == 0) {
            log.warn("The Idempotency-Key claim for order {} was gone when the order was stored; a repeat with "
                    + "this key will be processed as a new request", orderId);
        }
    }

    /** Best effort: a claim left behind is purged by EP-ORD-JOB-05 and only answers 409 until then. */
    public void releaseQuietly(UUID customerId, UUID key) {
        try {
            transactions.executeWithoutResult(tx -> keys.release(customerId, key));
        } catch (RuntimeException e) {
            log.warn("The Idempotency-Key claim of a failed submit could not be released ({}); EP-ORD-JOB-05 purges "
                    + "it", e.getMessage());
        }
    }

    /**
     * D9: SHA-256 hex of the parsed request, serialised as JSON with the items sorted by product, so the item order
     * and the client's JSON formatting do not matter. Record components serialise in declaration order.
     */
    public String hash(PlaceOrderRequest request) {
        PlaceOrderRequest canonical = new PlaceOrderRequest(request.items().stream()
                .sorted(Comparator.comparing(OrderItemRequest::productId))
                .toList(), request.deliveryDetails());
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(jsonMapper.writeValueAsString(canonical).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
