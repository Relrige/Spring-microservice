package ua.edu.ukma.springers.voltstore.customer.events;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;
import ua.edu.ukma.springers.voltstore.customer.config.KafkaTopics;
import ua.edu.ukma.springers.voltstore.customer.exceptions.PoisonMessageException;
import ua.edu.ukma.springers.voltstore.customer.services.CartService;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The one consumer of Order's topic (group {@code customer-service}). Order publishes several event types on the
 * topic, told apart by the {@code eventType} header; only {@code OrderPaid} matters here (EP-CUST-EVT-01: take the
 * ordered products out of the customer's cart), every other type is acknowledged and skipped.
 * <p>
 * <b>Idempotent by state:</b> removing products that are no longer in the cart (or a cart that does not exist)
 * changes nothing, so a redelivered event is harmless and no processed-events table is needed. Lines are removed, not
 * reduced by the ordered quantity: a reduction applied twice would take away items added later. <b>Offsets</b> are
 * committed after the listener returned (ack mode {@code batch}), so only after the transaction committed.
 * <p>
 * <b>Failures:</b> a payload that is not JSON or has no {@code orderId} or {@code items} is a
 * {@link PoisonMessageException}: no retries, straight to {@code customer.order.order-events.dlt}. Other failures (e.g. the database briefly down) are
 * retried with back-off by the error handler in {@code KafkaConfig}, then sent to the same DLT.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderEventsListener {
    private final CartService cartService;
    private final JsonMapper jsonMapper;
    private final Validator validator;

    // The raw record (not a converted parameter): the type to read is chosen by the eventType header
    @KafkaListener(topics = "${customer.kafka.topics.order-events}")
    public void onOrderEvent(ConsumerRecord<String, String> record) {
        Header header = record.headers().lastHeader(KafkaTopics.EVENT_TYPE_HEADER);
        String eventType = header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
        if (!OrderPaidEvent.TYPE.equals(eventType)) {
            log.debug("Skipping order event key={} of type {}", record.key(), eventType);
            return;
        }
        OrderPaidEvent event = read(record.value());
        if (event.customerId() == null) {
            // A guest order: the guest cart lives only in the browser, there is nothing to clear here
            log.debug("Skipping OrderPaid of guest order {}", event.orderId());
            return;
        }
        // Only the ordered products: items added to the cart after the order was submitted stay
        cartService.removeOrderedProducts(event.customerId(), event.productIds());
        log.info("Removed the {} products of paid order {} from the cart of customer {}", event.items().size(),
                event.orderId(), event.customerId());
    }

    private OrderPaidEvent read(String payload) {
        OrderPaidEvent event;
        try {
            event = jsonMapper.readValue(payload, OrderPaidEvent.class);
        } catch (JacksonException e) {
            throw new PoisonMessageException("Unreadable OrderPaid: " + e.getOriginalMessage(), e);
        }
        if (event == null) {
            throw new PoisonMessageException("Empty OrderPaid", null);
        }
        Set<ConstraintViolation<OrderPaidEvent>> violations = validator.validate(event);
        if (!violations.isEmpty()) {
            String details = violations.stream()
                    .map(violation -> violation.getPropertyPath() + " " + violation.getMessage())
                    .sorted()
                    .collect(Collectors.joining(", "));
            throw new PoisonMessageException("Invalid OrderPaid: " + details, null);
        }
        return event;
    }
}
