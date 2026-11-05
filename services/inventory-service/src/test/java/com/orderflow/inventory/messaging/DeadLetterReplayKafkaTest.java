package com.orderflow.inventory.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.orderflow.inventory.config.KafkaConfig;
import com.orderflow.inventory.service.InventoryService;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

/**
 * PLAN step 16c: a record that fails until a "fix" lands in the DLT; after the fix, the replay endpoint sends it
 * back and the order is reserved exactly once. A second call replays nothing. Own class (own DLT), so records
 * dead-lettered by other tests cannot change the counts.
 */
@SpringBootTest(properties = "orderflow.admin.enabled=true")
@AutoConfigureMockMvc
@Testcontainers
class DeadLetterReplayKafkaTest {

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
    @Autowired MockMvc mvc;
    @SpyBean InventoryService inventoryService;

    private int dltSize(String key) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        int n = 0;
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of(KafkaConfig.ORDERS_EVENTS_DLT));
            for (int i = 0; i < 2; i++) {
                for (var r : consumer.poll(Duration.ofMillis(1000))) {
                    if (key.equals(r.key())) n++;
                }
            }
        }
        return n;
    }

    private int available() {
        return jdbc.sql("SELECT available FROM stock WHERE sku = 'SKU-1'").query(Integer.class).single();
    }

    @Test
    void afterTheFixAReplayedDeadLetterReservesTheOrderExactlyOnce() throws Exception {
        jdbc.sql("UPDATE stock SET available = 100, reserved = 0").update();
        UUID orderId = UUID.randomUUID();
        String json = "{\"id\":\"" + orderId + "\",\"customerId\":\"alice\",\"status\":\"PENDING\","
                + "\"items\":[{\"sku\":\"SKU-1\",\"quantity\":3}]}";

        // a bug: every attempt fails until we "fix" it
        doThrow(new IllegalStateException("bug")).when(inventoryService).reserve(any(), any());

        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(props)) {
            ProducerRecord<String, String> record = new ProducerRecord<>(
                    KafkaConfig.ORDERS_EVENTS_TOPIC, orderId.toString(), json);
            record.headers().add("message-id", UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8));
            producer.send(record).get(10, TimeUnit.SECONDS);
        }
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(dltSize(orderId.toString())).isEqualTo(1));

        Mockito.reset(inventoryService);   // the fix: reserve works again

        mvc.perform(post("/admin/dlt/replay")).andExpect(status().isOk()).andExpect(content().string("1"));

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(available()).isEqualTo(97));
        await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertThat(available()).isEqualTo(97);
            assertThat(jdbc.sql("SELECT count(*) FROM reservations WHERE order_id = ?").param(orderId)
                    .query(Integer.class).single()).isEqualTo(1);
        });

        // replaying again finds nothing: the group's committed offset marks the dead letter as replayed
        mvc.perform(post("/admin/dlt/replay")).andExpect(status().isOk()).andExpect(content().string("0"));
    }
}
