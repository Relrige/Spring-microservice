package ua.edu.ukma.springers.voltstore.inventory.events;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.kafka.core.KafkaTemplate;
import ua.edu.ukma.springers.voltstore.inventory.config.KafkaTopics;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KafkaStockEventPublisherTest {
    private static final UUID PRODUCT_ID = UUID.randomUUID();

    @Mock
    private ApplicationEventPublisher applicationEventPublisher;

    @Mock
    private KafkaTemplate<String, Object> kafkaTemplate;

    @InjectMocks
    private KafkaStockEventPublisher publisher;

    @Test
    @SuppressWarnings("unchecked")
    void send_usesProductIdAsKeyTopicAndEventTypeHeader() {
        var event = ProductStockReplenishedEvent.forProduct(PRODUCT_ID);
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(null));

        publisher.send(event);

        ArgumentCaptor<ProducerRecord<String, Object>> captor = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate).send(captor.capture());
        ProducerRecord<String, Object> record = captor.getValue();
        assertThat(record.topic()).isEqualTo(KafkaTopics.STOCK_EVENTS);
        assertThat(record.key()).isEqualTo(PRODUCT_ID.toString());
        assertThat(record.value()).isSameAs(event);
        assertThat(new String(record.headers().lastHeader("eventType").value(), StandardCharsets.UTF_8))
                .isEqualTo("ProductStockReplenished");
    }

    @Test
    @SuppressWarnings("unchecked")
    void send_whenTemplateThrows_doesNotPropagate() {
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenThrow(new IllegalStateException("broker down"));

        assertThatCode(() -> publisher.send(ProductStockDepletedEvent.forProduct(PRODUCT_ID)))
                .doesNotThrowAnyException();
    }

    @Test
    @SuppressWarnings("unchecked")
    void send_whenAsyncSendFails_doesNotPropagate() {
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("not acknowledged")));

        assertThatCode(() -> publisher.send(ProductStockDepletedEvent.forProduct(PRODUCT_ID)))
                .doesNotThrowAnyException();
    }

    @Test
    void publish_delegatesToApplicationEventsSoDeliveryWaitsForCommit() {
        var event = ProductStockDepletedEvent.forProduct(PRODUCT_ID);

        publisher.publish(event);

        verify(applicationEventPublisher).publishEvent(event);
    }
}
