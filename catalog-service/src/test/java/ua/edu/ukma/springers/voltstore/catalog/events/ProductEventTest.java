package ua.edu.ukma.springers.voltstore.catalog.events;

import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.serializer.JacksonJsonDeserializer;
import org.springframework.kafka.support.serializer.JacksonJsonSerializer;
import org.springframework.test.util.ReflectionTestUtils;
import ua.edu.ukma.springers.voltstore.catalog.entities.Product;
import ua.edu.ukma.springers.voltstore.catalog.entities.ProductStatus;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class ProductEventTest {
    private static final UUID CATEGORY_ID = UUID.randomUUID();

    @Test
    void of_copiesTheProductStateAndGeneratesEventIdAndTimestamp() {
        UUID productId = UUID.randomUUID();
        Product product = Product.createNew("Laptop", "A laptop", CATEGORY_ID, new BigDecimal("999.99"));
        product.activate();
        ReflectionTestUtils.setField(product, "id", productId);
        ReflectionTestUtils.setField(product, "version", 7L);

        ProductEvent first = ProductEvent.of(product);
        ProductEvent second = ProductEvent.of(product);

        assertThat(first.productId()).isEqualTo(productId);
        assertThat(first.title()).isEqualTo("Laptop");
        assertThat(first.categoryId()).isEqualTo(CATEGORY_ID);
        assertThat(first.basePrice()).isEqualTo(new BigDecimal("999.99"));
        assertThat(first.status()).isEqualTo(ProductStatus.ACTIVE);
        assertThat(first.version()).isEqualTo(7L);
        assertThat(first.eventId()).isNotNull().isNotEqualTo(second.eventId());
        assertThat(first.timestamp()).isNotNull();
    }

    @Test
    void constructor_rejectsMissingFields() {
        UUID id = UUID.randomUUID();
        BigDecimal price = BigDecimal.TEN;
        Instant now = Instant.now();

        assertThatNullPointerException().isThrownBy(() -> new ProductEvent(null, id, "T", id, price, ProductStatus.ACTIVE, 0, now))
                .withMessageContaining("eventId");
        assertThatNullPointerException().isThrownBy(() -> new ProductEvent(id, null, "T", id, price, ProductStatus.ACTIVE, 0, now))
                .withMessageContaining("productId");
        assertThatNullPointerException().isThrownBy(() -> new ProductEvent(id, id, null, id, price, ProductStatus.ACTIVE, 0, now))
                .withMessageContaining("title");
        assertThatNullPointerException().isThrownBy(() -> new ProductEvent(id, id, "T", id, price, null, 0, now))
                .withMessageContaining("status");
        assertThatNullPointerException().isThrownBy(() -> new ProductEvent(id, id, "T", id, price, ProductStatus.ACTIVE, 0, null))
                .withMessageContaining("timestamp");
    }

    @Test
    void serializesTheFullStateWithIsoTimestampAndRoundTrips() {
        ProductEvent event = new ProductEvent(UUID.randomUUID(), UUID.randomUUID(), "Laptop", CATEGORY_ID,
                new BigDecimal("999.99"), ProductStatus.NOT_ACTIVE, 2, Instant.parse("2026-10-10T12:00:00.123Z"));

        try (JacksonJsonSerializer<ProductEvent> serializer = new JacksonJsonSerializer<>();
             JacksonJsonDeserializer<ProductEvent> deserializer = new JacksonJsonDeserializer<>(ProductEvent.class, false)) {
            byte[] bytes = serializer.serialize("topic", event);
            String json = new String(bytes, StandardCharsets.UTF_8);

            assertThat(json).contains("\"timestamp\":\"2026-10-10T12:00:00.123Z\"");
            assertThat(json).contains("\"eventId\":\"" + event.eventId() + "\"");
            assertThat(json).contains("\"title\":\"Laptop\"", "\"basePrice\":999.99", "\"status\":\"NOT_ACTIVE\"",
                    "\"version\":2", "\"categoryId\":\"" + CATEGORY_ID + "\"");
            assertThat(json).doesNotContain("description", "stockStatus");
            assertThat(deserializer.deserialize("topic", bytes)).isEqualTo(event);
        }
    }
}
