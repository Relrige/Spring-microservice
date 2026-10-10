package ua.edu.ukma.springers.voltstore.catalog.support;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Raw test consumer that reads a topic as plain strings, independent of the application's deserializers,
 * so tests can check the exact wire format. It starts at the current end of every partition, so it only
 * sees messages produced after it was created.
 */
public class TopicProbe implements AutoCloseable {
    private final KafkaConsumer<String, String> consumer;
    private final List<ConsumerRecord<String, String>> received = new ArrayList<>();

    public TopicProbe(String bootstrapServers, String topic) {
        consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false));
        List<TopicPartition> partitions = consumer.partitionsFor(topic, Duration.ofSeconds(30)).stream()
                .map(PartitionInfo::partition)
                .map(partition -> new TopicPartition(topic, partition))
                .toList();
        consumer.assign(partitions);
        consumer.seekToEnd(partitions);
        // seekToEnd is lazy; position() resolves it now, before the test produces anything
        partitions.forEach(consumer::position);
    }

    /** Polls once and returns everything received so far. */
    public List<ConsumerRecord<String, String>> poll() {
        consumer.poll(Duration.ofMillis(200)).forEach(received::add);
        return List.copyOf(received);
    }

    @Override
    public void close() {
        consumer.close();
    }
}
