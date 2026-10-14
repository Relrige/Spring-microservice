package ua.edu.ukma.springers.voltstore.inventory.events;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StockEventsTest {
    private static final UUID PRODUCT_ID = UUID.randomUUID();

    @Test
    void forTransition_zeroToPositive_isReplenished() {
        var event = StockEvents.forTransition(PRODUCT_ID, 0, 5).orElseThrow();

        assertThat(event).isInstanceOf(ProductStockReplenishedEvent.class);
        assertThat(event.productId()).isEqualTo(PRODUCT_ID);
        assertThat(event.eventId()).isNotNull();
        assertThat(event.timestamp()).isNotNull();
        assertThat(event.eventType()).isEqualTo("ProductStockReplenished");
    }

    @Test
    void forTransition_positiveToZero_isDepleted() {
        var event = StockEvents.forTransition(PRODUCT_ID, 5, 0).orElseThrow();

        assertThat(event).isInstanceOf(ProductStockDepletedEvent.class);
        assertThat(event.eventType()).isEqualTo("ProductStockDepleted");
    }

    @ParameterizedTest
    @CsvSource({"5,8", "8,5", "1,2", "0,0", "3,3"})
    void forTransition_noCrossing_isEmpty(int before, int after) {
        assertThat(StockEvents.forTransition(PRODUCT_ID, before, after)).isEmpty();
    }

    @Test
    void forTransition_generatesNewEventIdEachTime() {
        var first = StockEvents.forTransition(PRODUCT_ID, 0, 1).orElseThrow();
        var second = StockEvents.forTransition(PRODUCT_ID, 0, 1).orElseThrow();

        assertThat(first.eventId()).isNotEqualTo(second.eventId());
    }

    @Test
    void classOf_mapsEventTypeToPayloadClass() {
        assertThat(StockEvents.classOf("ProductStockReplenished")).isEqualTo(ProductStockReplenishedEvent.class);
        assertThat(StockEvents.classOf("ProductStockDepleted")).isEqualTo(ProductStockDepletedEvent.class);
        assertThatThrownBy(() -> StockEvents.classOf("SomethingElse")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void jsonRoundTrip_throughClassOf_restoresTheSameEvent() {
        JsonMapper mapper = JsonMapper.builder().build();
        StockEvent original = ProductStockDepletedEvent.forProduct(PRODUCT_ID);

        String json = mapper.writeValueAsString(original);
        StockEvent restored = mapper.readValue(json, StockEvents.classOf(original.eventType()));

        assertThat(restored).isEqualTo(original);
    }

    @Test
    void events_requireAllFields() {
        assertThatThrownBy(() -> new ProductStockReplenishedEvent(null, PRODUCT_ID, Instant.now()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ProductStockReplenishedEvent(UUID.randomUUID(), null, Instant.now()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ProductStockDepletedEvent(UUID.randomUUID(), PRODUCT_ID, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ProductStockDepletedEvent(null, PRODUCT_ID, Instant.now()))
                .isInstanceOf(NullPointerException.class);
    }
}
