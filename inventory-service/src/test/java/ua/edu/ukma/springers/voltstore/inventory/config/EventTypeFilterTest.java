package ua.edu.ukma.springers.voltstore.inventory.config;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.listener.adapter.RecordFilterStrategy;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

// Per-aggregate source topics carry several event types; each listener keeps only its own
class EventTypeFilterTest {
    private final RecordFilterStrategy<String, String> orderShippedOnly = KafkaConfig.onlyEventType("OrderShipped");

    @Test
    void keepsTheListenersEventType() {
        assertThat(orderShippedOnly.filter(record("OrderShipped"))).isFalse();
    }

    @Test
    void discardsOtherEventTypesAndRecordsWithoutType() {
        assertThat(orderShippedOnly.filter(record("OrderCancelled"))).isTrue();
        assertThat(orderShippedOnly.filter(record("orderShipped"))).isTrue();
        assertThat(orderShippedOnly.filter(record(null))).isTrue();
    }

    @Test
    void deadLetterTopicsAreOwnedByInventory() {
        assertThat(KafkaTopics.deadLetterTopicOf("catalog.product-events"))
                .isEqualTo("inventory.catalog.product-events.dlt");
    }

    private static ConsumerRecord<String, String> record(String eventType) {
        ConsumerRecord<String, String> record = new ConsumerRecord<>("order.order-events", 0, 0L, "key", "{}");
        if (eventType != null) {
            record.headers().add(KafkaTopics.EVENT_TYPE_HEADER, eventType.getBytes(StandardCharsets.UTF_8));
        }
        return record;
    }
}
