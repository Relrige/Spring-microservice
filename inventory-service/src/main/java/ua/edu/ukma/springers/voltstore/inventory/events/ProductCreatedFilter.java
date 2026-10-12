package ua.edu.ukma.springers.voltstore.inventory.events;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.kafka.listener.adapter.RecordFilterStrategy;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * Lets only {@code ProductCreated} records through to {@link ProductCreatedListener}. Catalog's topic may carry
 * other product events later; they are discarded here, before JSON conversion, so a payload of another shape
 * is never mistaken for a poison message. Discarded records are committed like processed ones.
 */
@Slf4j
@Component(ProductCreatedFilter.BEAN_NAME)
public class ProductCreatedFilter implements RecordFilterStrategy<String, String> {
    public static final String BEAN_NAME = "productCreatedFilter";
    public static final String EVENT_TYPE_HEADER = "eventType";

    /**
     * @return true to discard the record
     */
    @Override
    public boolean filter(ConsumerRecord<String, String> consumerRecord) {
        Header typeHeader = consumerRecord.headers().lastHeader(EVENT_TYPE_HEADER);
        if (typeHeader == null) {
            // Catalog always sets the header, so a missing one points at a producer bug
            log.warn("Product event without {} header skipped: key={} partition={} offset={}", EVENT_TYPE_HEADER,
                    consumerRecord.key(), consumerRecord.partition(), consumerRecord.offset());
            return true;
        }
        String eventType = new String(typeHeader.value(), StandardCharsets.UTF_8);
        if (!ProductCreatedEvent.TYPE.equals(eventType)) {
            log.debug("Product event of type {} ignored: key={} partition={} offset={}", eventType,
                    consumerRecord.key(), consumerRecord.partition(), consumerRecord.offset());
            return true;
        }
        return false;
    }
}
