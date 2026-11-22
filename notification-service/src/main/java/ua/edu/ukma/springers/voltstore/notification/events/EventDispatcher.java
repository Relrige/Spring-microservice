package ua.edu.ukma.springers.voltstore.notification.events;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import ua.edu.ukma.springers.voltstore.notification.config.KafkaTopics;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Routes the records of one topic to the handler of their {@code eventType} header. Records of any other type, or
 * without the header, are skipped (logged at {@code DEBUG} and acknowledged). Only the record's coordinates are
 * logged, never its value.
 */
@Slf4j
class EventDispatcher {
    private final Map<String, EventHandler<?>> handlers;
    private final EventReader reader;

    EventDispatcher(List<? extends EventHandler<?>> handlers, EventReader reader) {
        // Fails at startup if two handlers claim the same type
        this.handlers = handlers.stream().collect(Collectors.toUnmodifiableMap(EventHandler::eventType,
                Function.identity()));
        this.reader = reader;
    }

    void dispatch(ConsumerRecord<String, String> consumerRecord) {
        Header header = consumerRecord.headers().lastHeader(KafkaTopics.EVENT_TYPE_HEADER);
        String eventType = header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
        EventHandler<?> handler = eventType == null ? null : handlers.get(eventType);
        if (handler == null) {
            log.debug("Skipping {} key={} offset={} of type {}", consumerRecord.topic(), consumerRecord.key(), consumerRecord.offset(),
                    eventType);
            return;
        }
        handle(handler, consumerRecord);
    }

    private <T> void handle(EventHandler<T> handler, ConsumerRecord<String, String> consumerRecord) {
        handler.handle(reader.read(consumerRecord.value(), handler.payloadType()));
    }
}
