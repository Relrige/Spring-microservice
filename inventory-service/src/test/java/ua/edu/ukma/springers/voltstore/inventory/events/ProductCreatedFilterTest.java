package ua.edu.ukma.springers.voltstore.inventory.events;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class ProductCreatedFilterTest {
    private final ProductCreatedFilter filter = new ProductCreatedFilter();

    @Test
    void productCreated_isKept() {
        assertThat(filter.filter(recordWithType("ProductCreated"))).isFalse();
    }

    @Test
    void otherEventType_isDiscarded() {
        assertThat(filter.filter(recordWithType("ProductUpdated"))).isTrue();
    }

    @Test
    void missingEventTypeHeader_isDiscarded() {
        assertThat(filter.filter(new ConsumerRecord<>("catalog.product-events", 0, 0L, "key", "{}"))).isTrue();
    }

    private static ConsumerRecord<String, String> recordWithType(String eventType) {
        ConsumerRecord<String, String> consumerRecord = new ConsumerRecord<>("catalog.product-events", 0, 0L, "key", "{}");
        consumerRecord.headers().add(ProductCreatedFilter.EVENT_TYPE_HEADER, eventType.getBytes(StandardCharsets.UTF_8));
        return consumerRecord;
    }
}
