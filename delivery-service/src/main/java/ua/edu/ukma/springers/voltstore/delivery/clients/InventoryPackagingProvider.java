package ua.edu.ukma.springers.voltstore.delivery.clients;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import ua.edu.ukma.springers.voltstore.delivery.entities.ShipmentItem;
import ua.edu.ukma.springers.voltstore.delivery.exceptions.PackagingUnavailableException;
import ua.edu.ukma.springers.voltstore.delivery.services.ItemPackaging;
import ua.edu.ukma.springers.voltstore.delivery.services.PackagingProvider;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Fetches packaging from Inventory's internal EP-INV-05, once per distinct product, and classifies failures for the
 * TTN generation job:
 * <ul>
 *   <li>{@code 404} → not retryable, {@code stock_item_not_found:{productId}}.</li>
 *   <li>{@code 422} ({@code reason = packaging_data_missing}) → not retryable,
 *   {@code packaging_data_missing:{productId}}. Only a worker fixing the data (EP-INV-03) helps.</li>
 *   <li>Connection error, timeout, {@code 5xx} → retryable, {@code inventory_unavailable}.</li>
 *   <li>Any other status (e.g. {@code 401}/{@code 403}: wrong internal token or route rule) → a configuration
 *   problem, logged as an error; retryable, {@code inventory_unavailable}, so the shipment recovers once the
 *   configuration is fixed.</li>
 * </ul>
 * There is no retry inside the call: the job's attempts are the retries. The internal token never appears in a log
 * line or exception message.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InventoryPackagingProvider implements PackagingProvider {
    static final String INVENTORY_UNAVAILABLE = "inventory_unavailable";

    private final InventoryClient inventoryClient;

    @Override
    public List<ItemPackaging> packagingFor(List<ShipmentItem> items) {
        Map<UUID, InventoryPackaging> byProduct = new HashMap<>();
        for (ShipmentItem item : items) {
            byProduct.computeIfAbsent(item.productId(), this::fetch);
        }
        return items.stream().map(item -> {
            InventoryPackaging packaging = byProduct.get(item.productId());
            return new ItemPackaging(item.productId(), item.quantity(), packaging.grossWeightGrams(),
                    packaging.lengthCm(), packaging.widthCm(), packaging.heightCm());
        }).toList();
    }

    private InventoryPackaging fetch(UUID productId) {
        try {
            InventoryPackaging packaging = inventoryClient.getPackaging(productId);
            if (packaging == null) {
                throw new PackagingUnavailableException(INVENTORY_UNAVAILABLE, true, null);
            }
            return packaging;
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().isSameCodeAs(HttpStatus.NOT_FOUND)) {
                throw new PackagingUnavailableException("stock_item_not_found:" + productId, false, e);
            }
            if (e.getStatusCode().isSameCodeAs(HttpStatus.UNPROCESSABLE_CONTENT)) {
                throw new PackagingUnavailableException("packaging_data_missing:" + productId, false, e);
            }
            if (e.getStatusCode().is5xxServerError()) {
                log.warn("Packaging lookup of product {} failed: Inventory answered {}", productId,
                        e.getStatusCode());
            } else {
                log.error("Packaging lookup of product {}: unexpected status {} from Inventory "
                        + "(check the internal token and Inventory's route rules)", productId, e.getStatusCode());
            }
            throw new PackagingUnavailableException(INVENTORY_UNAVAILABLE, true, e);
        } catch (RestClientException e) {
            // Connection refused, connect or read timeout, broken response
            log.warn("Packaging lookup of product {} failed: {}", productId, e.getMessage());
            throw new PackagingUnavailableException(INVENTORY_UNAVAILABLE, true, e);
        }
    }
}
