package com.orderflow.inventory.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;

import com.orderflow.inventory.config.KafkaConfig;
import com.orderflow.inventory.service.InventoryService;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

/**
 * PLAN step 16a: a poison message goes to orders.events.DLT without blocking the partition, and a transient
 * failure is retried instead of being dead-lettered.
 */
@SpringBootTest
@Testcontainers
class ConsumerFailureKafkaTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Container
    static KafkaContainer kafka = new KafkaContainer("apache/kafka:3.8.0");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
        registry.add("spring.kafka.producer.properties.max.block.ms", () -> "2000");
        registry.add("spring.kafka.producer.properties.request.timeout.ms", () -> "2000");
        registry.add("spring.kafka.producer.properties.delivery.timeout.ms", () -> "5000");
        registry.add("orderflow.outbox.poll-ms", () -> "200");
        registry.add("orderflow.consumer.backoff-initial-ms", () -> "100");
        registry.add("orderflow.consumer.max-attempts", () -> "3");
    }

    @Autowired JdbcClient jdbc;
    @SpyBean InventoryService inventoryService;

    @BeforeEach
    void resetState() {
        jdbc.sql("DELETE FROM reservations").update();
        jdbc.sql("DELETE FROM processed_messages").update();
        jdbc.sql("DELETE FROM outbox").update();
        jdbc.sql("UPDATE stock SET available = 100, reserved = 0").update();
    }

    private static String order(UUID id, String sku, int qty) {
        return "{\"id\":\"" + id + "\",\"customerId\":\"alice\",\"status\":\"PENDING\","
                + "\"items\":[{\"sku\":\"" + sku + "\",\"quantity\":" + qty + "}]}";
    }

    private int available(String sku) {
        return jdbc.sql("SELECT available FROM stock WHERE sku = ?").param(sku).query(Integer.class).single();
    }

    private static void send(String key, String messageId, String value) {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(props)) {
            ProducerRecord<String, String> record = new ProducerRecord<>(KafkaConfig.ORDERS_EVENTS_TOPIC, key, value);
            record.headers().add("message-id", messageId.getBytes(StandardCharsets.UTF_8));
            producer.send(record).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Reads the whole DLT with a fresh consumer and returns the records with this key. */
    private static List<ConsumerRecord<String, String>> dltRecordsFor(String key) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        List<ConsumerRecord<String, String>> found = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of(KafkaConfig.ORDERS_EVENTS_DLT));
            consumer.poll(Duration.ofMillis(1000)).forEach(r -> { if (key.equals(r.key())) found.add(r); });
            consumer.poll(Duration.ofMillis(500)).forEach(r -> { if (key.equals(r.key())) found.add(r); });
        }
        return found;
    }

    @Test
    void aPoisonMessageGoesToTheDltAndDoesNotBlockTheOrdersBehindIt() {
        String poisonKey = "poison-" + UUID.randomUUID();
        UUID orderId = UUID.randomUUID();

        send(poisonKey, UUID.randomUUID().toString(), "not json");
        send(orderId.toString(), UUID.randomUUID().toString(), order(orderId, "SKU-1", 3));

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(dltRecordsFor(poisonKey)).hasSize(1));
        // the valid order behind the poison record is still reserved: the partition is not blocked
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(available("SKU-1")).isEqualTo(97));
    }

    @Test
    void aTransientFailureIsRetriedAndNothingReachesTheDlt() {
        UUID orderId = UUID.randomUUID();
        doThrow(new IllegalStateException("database blip"))
                .doThrow(new IllegalStateException("database blip"))
                .doCallRealMethod()
                .when(inventoryService).reserve(any(), any());

        send(orderId.toString(), UUID.randomUUID().toString(), order(orderId, "SKU-2", 4));

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(available("SKU-2")).isEqualTo(96));
        assertThat(dltRecordsFor(orderId.toString())).isEmpty();
    }
}
