package ua.edu.ukma.springers.voltstore.delivery.events;

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
import ua.edu.ukma.springers.voltstore.delivery.config.KafkaTopics;
import ua.edu.ukma.springers.voltstore.delivery.exceptions.PoisonMessageException;
import ua.edu.ukma.springers.voltstore.delivery.services.ShipmentService;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The one consumer of Order's topic (group {@code delivery-service}). {@code OrderPaid} and {@code OrderShipped}
 * share the topic and are told apart by the {@code eventType} header, so a single listener dispatches by it; every
 * other type is acknowledged and skipped. Two listener containers in the same group on the same topic would split
 * the partitions between them, and each would drop the other's event type.
 * <p>
 * <b>Offsets</b> are committed after the listener returned (ack mode {@code batch}: after every record of a poll),
 * so only after the database transaction committed. A crash in between redelivers the record; the handlers are
 * idempotent by state (the unique {@code orderId}), so no processed-events table is needed.
 * <p>
 * <b>Failures:</b> a payload that is not JSON or breaks a required field is a {@link PoisonMessageException}: no
 * retries, straight to {@code delivery.order.order-events.dlt}. Other failures (e.g. the database briefly down) are
 * retried with back-off by the error handler in {@code KafkaConfig}, then sent to the same DLT.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderEventsListener {
    private final ShipmentService shipmentService;
    private final JsonMapper jsonMapper;
    private final Validator validator;

    // The raw record (not a converted parameter): the JSON is read here, by the type named in the eventType header
    @KafkaListener(topics = "${delivery.kafka.topics.order-events}")
    public void onOrderEvent(ConsumerRecord<String, String> record) {
        Header header = record.headers().lastHeader(KafkaTopics.EVENT_TYPE_HEADER);
        String eventType = header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
        if (OrderPaidEvent.TYPE.equals(eventType)) {
            shipmentService.createFromOrderPaid(read(record.value(), OrderPaidEvent.class));
        } else {
            log.debug("Skipping order event key={} of type {}", record.key(), eventType);
        }
    }

    private <T> T read(String payload, Class<T> type) {
        T event;
        try {
            event = jsonMapper.readValue(payload, type);
        } catch (JacksonException e) {
            throw new PoisonMessageException("Unreadable " + type.getSimpleName() + ": " + e.getOriginalMessage(), e);
        }
        if (event == null) {
            throw new PoisonMessageException("Empty " + type.getSimpleName(), null);
        }
        Set<ConstraintViolation<T>> violations = validator.validate(event);
        if (!violations.isEmpty()) {
            String details = violations.stream()
                    .map(violation -> violation.getPropertyPath() + " " + violation.getMessage())
                    .sorted()
                    .collect(Collectors.joining(", "));
            throw new PoisonMessageException("Invalid " + type.getSimpleName() + ": " + details, null);
        }
        return event;
    }
}
