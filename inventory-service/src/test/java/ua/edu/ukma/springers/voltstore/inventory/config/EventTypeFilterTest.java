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
        assertThat(orderShippedOnly.filter(consumerRecord("OrderShipped"))).isFalse();
    }

    @Test
    void discardsOtherEventTypesAndRecordsWithoutType() {
        assertThat(orderShippedOnly.filter(consumerRecord("OrderCancelled"))).isTrue();
        assertThat(orderShippedOnly.filter(consumerRecord("orderShipped"))).isTrue();
        assertThat(orderShippedOnly.filter(consumerRecord(null))).isTrue();
    }

    @Test
    void productEvents_bothTypesShareOneFilter() {
        RecordFilterStrategy<String, String> productEventsOnly = new KafkaConfig().productEventsOnly();

        assertThat(productEventsOnly.filter(consumerRecord("ProductCreated"))).isFalse();
        assertThat(productEventsOnly.filter(consumerRecord("ProductUpdated"))).isFalse();
        assertThat(productEventsOnly.filter(consumerRecord("ProductDeleted"))).isTrue();
        assertThat(productEventsOnly.filter(consumerRecord(null))).isTrue();
    }

    @Test
    void deadLetterTopicsAreOwnedByInventory() {
        assertThat(KafkaTopics.deadLetterTopicOf("catalog.product-events"))
                .isEqualTo("inventory.catalog.product-events.dlt");
    }

    private static ConsumerRecord<String, String> consumerRecord(String eventType) {
        ConsumerRecord<String, String> consumerRecord = new ConsumerRecord<>("order.order-events", 0, 0L, "key", "{}");
        if (eventType != null) {
            consumerRecord.headers().add(KafkaTopics.EVENT_TYPE_HEADER, eventType.getBytes(StandardCharsets.UTF_8));
        }
        return consumerRecord;
    }
}
