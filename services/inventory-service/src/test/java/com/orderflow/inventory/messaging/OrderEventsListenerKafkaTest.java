package com.orderflow.inventory.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.orderflow.inventory.config.KafkaConfig;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
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
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

/**
 * PLAN step 14d: the real wiring. The same record delivered twice through Kafka reserves stock once,
 * and exactly one result per order reaches inventory.results.
 */
@SpringBootTest
@Testcontainers
class OrderEventsListenerKafkaTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Container
    static KafkaContainer kafka = new KafkaContainer("apache/kafka:3.8.0");

    @DynamicPropertySource
    static void kafkaProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
        registry.add("spring.kafka.producer.properties.max.block.ms", () -> "2000");
        registry.add("spring.kafka.producer.properties.request.timeout.ms", () -> "2000");
        registry.add("spring.kafka.producer.properties.delivery.timeout.ms", () -> "5000");
        registry.add("orderflow.outbox.poll-ms", () -> "200");
    }

    @Autowired JdbcClient jdbc;

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

    private int processed() {
        return jdbc.sql("SELECT count(*) FROM processed_messages").query(Integer.class).single();
    }

    @Test
    void theSameRecordDeliveredTwiceReservesStockOnce() {
        UUID orderA = UUID.randomUUID();
        UUID orderB = UUID.randomUUID();
        String messageA = UUID.randomUUID().toString();
        String messageB = UUID.randomUUID().toString();

        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(props)) {
            // the same record twice (same key, same message-id header, same JSON), then a different order
            for (int i = 0; i < 2; i++) {
                send(producer, orderA, messageA, order(orderA, "SKU-1", 3));
            }
            send(producer, orderB, messageB, order(orderB, "SKU-2", 5));
            producer.flush();
        }

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            assertThat(processed()).isEqualTo(2);
            assertThat(available("SKU-1")).isEqualTo(97);
            assertThat(available("SKU-2")).isEqualTo(95);
        });
        // a late second reservation would show up here
        await().during(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(6)).untilAsserted(() -> {
            assertThat(processed()).isEqualTo(2);
            assertThat(available("SKU-1")).isEqualTo(97);
            assertThat(available("SKU-2")).isEqualTo(95);
        });

        try (KafkaConsumer<String, String> consumer = newConsumer()) {
            consumer.subscribe(List.of(KafkaConfig.INVENTORY_RESULTS_TOPIC));
            List<ConsumerRecord<String, String>> all = new ArrayList<>();
            Instant deadline = Instant.now().plusSeconds(15);
            while (Instant.now().isBefore(deadline) && !(has(all, orderA) && has(all, orderB))) {
                consumer.poll(Duration.ofMillis(500)).forEach(all::add);
            }
            Instant quietUntil = Instant.now().plusSeconds(2);
            while (Instant.now().isBefore(quietUntil)) {
                consumer.poll(Duration.ofMillis(500)).forEach(all::add);
            }
            for (UUID id : List.of(orderA, orderB)) {
                List<String> values = all.stream().filter(r -> id.toString().equals(r.key()))
                        .map(ConsumerRecord::value).toList();
                assertThat(values).as("results for order %s", id).hasSize(1);
                assertThat(values.get(0)).contains("\"result\":\"RESERVED\"");
                // the correlation id of the incoming record is carried to the result record (PLAN step 21c)
                org.apache.kafka.common.header.Header corr = all.stream().filter(r -> id.toString().equals(r.key()))
                        .findFirst().orElseThrow().headers().lastHeader("correlation-id");
                assertThat(corr).as("correlation-id header on the result").isNotNull();
                assertThat(new String(corr.value(), StandardCharsets.UTF_8)).isEqualTo("corr-" + id);
            }
        }
    }

    private static boolean has(List<ConsumerRecord<String, String>> all, UUID id) {
        return all.stream().anyMatch(r -> id.toString().equals(r.key()));
    }

    private static void send(KafkaProducer<String, String> producer, UUID key, String messageId, String json) {
        ProducerRecord<String, String> record = new ProducerRecord<>(KafkaConfig.ORDERS_EVENTS_TOPIC, key.toString(), json);
        record.headers().add("message-id", messageId.getBytes(StandardCharsets.UTF_8));
        record.headers().add("correlation-id", ("corr-" + key).getBytes(StandardCharsets.UTF_8));   // PLAN step 21c
        try {
            producer.send(record).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static KafkaConsumer<String, String> newConsumer() {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        return new KafkaConsumer<>(props);
    }
}
