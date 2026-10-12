package ua.edu.ukma.springers.voltstore.inventory.config;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.support.converter.ConversionException;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Checks the retry classification of the listener error handler without a broker. {@code handleOne} returns
 * false when the record will be redelivered (retry) and true once it has been handed to the recoverer.
 * The mocked container reports "not running", so the back-off pauses end early.
 */
class KafkaErrorHandlerTest {
    private final List<Exception> recovered = new CopyOnWriteArrayList<>();
    private final DefaultErrorHandler handler = KafkaConfig.errorHandler((record, exception) -> recovered.add(exception));
    private final ConsumerRecord<String, String> record = new ConsumerRecord<>("catalog.product-events", 0, 5L, "key", "{bad");
    private final Consumer<?, ?> consumer = mock(Consumer.class);
    private final MessageListenerContainer container = mock(MessageListenerContainer.class);

    @Test
    void conversionFailure_isNotRetried_andGoesStraightToRecoverer() {
        Exception failure = new ListenerExecutionFailedException("listener failed",
                new ConversionException("Failed to convert from JSON", record, new IllegalArgumentException("bad json")));

        assertThat(handler.handleOne(failure, record, consumer, container)).isTrue();
        assertThat(recovered).containsExactly(failure);
    }

    @Test
    void databaseFailure_isRetriedThreeTimes_thenGoesToRecoverer() {
        Exception failure = new ListenerExecutionFailedException("listener failed",
                new DataAccessResourceFailureException("database unavailable"));

        for (int retry = 1; retry <= KafkaConfig.MAX_RETRIES; retry++) {
            assertThat(handler.handleOne(failure, record, consumer, container)).as("attempt %d", retry).isFalse();
        }
        assertThat(recovered).isEmpty();

        assertThat(handler.handleOne(failure, record, consumer, container)).isTrue();
        assertThat(recovered).containsExactly(failure);
    }
}
