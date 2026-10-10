package ua.edu.ukma.springers.voltstore.catalog.events;

import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.serializer.JacksonJsonDeserializer;
import org.springframework.kafka.support.serializer.JacksonJsonSerializer;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class ProductCreatedEventTest {

    @Test
    void forProduct_generatesEventIdAndTimestamp() {
        UUID productId = UUID.randomUUID();

        ProductCreatedEvent first = ProductCreatedEvent.forProduct(productId);
        ProductCreatedEvent second = ProductCreatedEvent.forProduct(productId);

        assertThat(first.productId()).isEqualTo(productId);
        assertThat(first.eventId()).isNotNull().isNotEqualTo(second.eventId());
        assertThat(first.timestamp()).isNotNull();
    }

    @Test
    void constructor_rejectsMissingFields() {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();

        assertThatNullPointerException().isThrownBy(() -> new ProductCreatedEvent(null, id, now))
                .withMessageContaining("eventId");
        assertThatNullPointerException().isThrownBy(() -> new ProductCreatedEvent(id, null, now))
                .withMessageContaining("productId");
        assertThatNullPointerException().isThrownBy(() -> new ProductCreatedEvent(id, id, null))
                .withMessageContaining("timestamp");
    }

    @Test
    void serializesTimestampAsIsoUtcInstantAndRoundTrips() {
        ProductCreatedEvent event = new ProductCreatedEvent(UUID.randomUUID(), UUID.randomUUID(),
                Instant.parse("2026-10-10T12:00:00.123Z"));

        try (JacksonJsonSerializer<ProductCreatedEvent> serializer = new JacksonJsonSerializer<>();
             JacksonJsonDeserializer<ProductCreatedEvent> deserializer = new JacksonJsonDeserializer<>(ProductCreatedEvent.class, false)) {
            byte[] bytes = serializer.serialize("topic", event);
            String json = new String(bytes, StandardCharsets.UTF_8);

            assertThat(json).contains("\"timestamp\":\"2026-10-10T12:00:00.123Z\"");
            assertThat(json).contains("\"eventId\":\"" + event.eventId() + "\"");
            assertThat(deserializer.deserialize("topic", bytes)).isEqualTo(event);
        }
    }

    @Test
    void deserialization_ignoresUnknownProperties() {
        String json = "{\"eventId\":\"" + UUID.randomUUID() + "\",\"productId\":\"" + UUID.randomUUID()
                + "\",\"timestamp\":\"2026-10-10T12:00:00Z\",\"addedInV2\":\"x\"}";

        try (JacksonJsonDeserializer<ProductCreatedEvent> deserializer = new JacksonJsonDeserializer<>(ProductCreatedEvent.class, false)) {
            ProductCreatedEvent event = deserializer.deserialize("topic", json.getBytes(StandardCharsets.UTF_8));

            assertThat(event.timestamp()).isEqualTo(Instant.parse("2026-10-10T12:00:00Z"));
        }
    }
}
