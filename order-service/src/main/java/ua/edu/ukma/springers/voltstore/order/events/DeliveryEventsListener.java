package ua.edu.ukma.springers.voltstore.order.events;

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
import ua.edu.ukma.springers.voltstore.order.config.KafkaTopics;
import ua.edu.ukma.springers.voltstore.order.exceptions.PoisonMessageException;
import ua.edu.ukma.springers.voltstore.order.services.ShipmentProgressService;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The one consumer of Delivery's topic (group {@code order-service}), the same pattern as Delivery's consumer of
 * Order's topic. {@code TTNGenerated} and {@code OrderDelivered} share the topic and are told apart by the
 * {@code eventType} header, so a single listener dispatches by it; every other type is acknowledged and skipped. Two
 * listener containers in the same group on the same topic would split the partitions between them, and each would
 * drop the other's event type.
 * <p>
 * <b>Offsets</b> are committed after the listener returned (ack mode {@code batch}), so only after the database
 * transaction committed. A crash in between redelivers the record; the handlers are idempotent by state, so no
 * processed-events table is needed.
 * <p>
 * <b>Failures:</b> a payload that is not JSON or breaks a required field is a {@link PoisonMessageException}: no
 * retries, straight to {@code order.delivery.shipment-events.dlt}. Other failures are retried with back-off by the
 * error handler in {@code KafkaConfig}, then sent to the same DLT.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeliveryEventsListener {
    private final ShipmentProgressService shipmentProgress;
    private final JsonMapper jsonMapper;
    private final Validator validator;

    // The raw record (not a converted parameter): the JSON is read here, once the eventType header said what it is
    @KafkaListener(topics = "${order.kafka.topics.delivery-events}")
    public void onDeliveryEvent(ConsumerRecord<String, String> record) {
        Header header = record.headers().lastHeader(KafkaTopics.EVENT_TYPE_HEADER);
        String eventType = header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
        if (ShipmentMilestoneEvent.TTN_GENERATED.equals(eventType)) {
            shipmentProgress.ttnGenerated(read(record.value()));
        } else if (ShipmentMilestoneEvent.ORDER_DELIVERED.equals(eventType)) {
            shipmentProgress.delivered(read(record.value()));
        } else {
            log.debug("Skipping delivery event key={} of type {}", record.key(), eventType);
        }
    }

    private ShipmentMilestoneEvent read(String payload) {
        ShipmentMilestoneEvent event;
        try {
            event = jsonMapper.readValue(payload, ShipmentMilestoneEvent.class);
        } catch (JacksonException e) {
            throw new PoisonMessageException("Unreadable delivery event: " + e.getOriginalMessage(), e);
        }
        if (event == null) {
            throw new PoisonMessageException("Empty delivery event", null);
        }
        Set<ConstraintViolation<ShipmentMilestoneEvent>> violations = validator.validate(event);
        if (!violations.isEmpty()) {
            String details = violations.stream()
                    .map(violation -> violation.getPropertyPath() + " " + violation.getMessage())
                    .sorted()
                    .collect(Collectors.joining(", "));
            throw new PoisonMessageException("Invalid delivery event: " + details, null);
        }
        return event;
    }
}
