package com.orderflow.order.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.orderflow.order.config.KafkaConfig;
import com.orderflow.order.persistence.OrderRepository;
import com.orderflow.order.service.OrderService;
import com.orderflow.order.web.CreateOrderRequest;
import com.orderflow.order.web.OrderResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

/**
 * PLAN step 13c: once an order commits, its OrderCreated event must reach Kafka exactly once.
 * Runs against real PostgreSQL (Non-production, substitutes application.yml params with random port and container just for this test) 
 * and Kafka (Non-production, substitutes application.yml params with random port and container just for this test) containers (Docker must be running).
 * Written to FAIL until OutboxPublisher.publishPending() is implemented (step 13e): no record arrives.
 */
@SpringBootTest   // publisher left on (orderflow.outbox.enabled defaults to true) and pointed at the Kafka container
@Testcontainers
class OutboxPublisherKafkaTest {

    //Non-production Postgres containers just for test, substitutes container/port used in Production in by application.yml just for this test
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    // same image as the compose file
    @Container
    static KafkaContainer kafka = new KafkaContainer("apache/kafka:3.8.0");

    // these override application.yml, which points at localhost:9092 (your compose broker)
    @DynamicPropertySource
    static void kafkaProperties(DynamicPropertyRegistry registry) {
        //spring.kafka.* namespace is Kafkas. Some properties like.bootstrap-servers are reserved and others like
        //.properties.max.block.ms are user-defined and not validated by Spring which causes Kfaka to log it as unknown config.
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
        registry.add("spring.kafka.producer.properties.max.block.ms", () -> "2000");
        // Kafka requires delivery.timeout.ms >= linger.ms + request.timeout.ms (default 30000), so shorten request.timeout.ms too
        registry.add("spring.kafka.producer.properties.request.timeout.ms", () -> "2000");
        registry.add("spring.kafka.producer.properties.delivery.timeout.ms", () -> "5000");
        //Ground truth of orderflow.outbox.* is in OutboxPublisher; See notes\outbox\what_is_orderflow_outbox_property_and_its_uses
        registry.add("orderflow.outbox.poll-ms", () -> "200");   // poll fast so the test does not wait long
    }

    @Autowired OrderService orderService;
    @Autowired OrderRepository orderRepository;
    @Autowired OutboxRepository outboxRepository;

    private final CreateOrderRequest request = new CreateOrderRequest(
            "CUST-1", List.of(new CreateOrderRequest.Item("SKU-1", 2)));

    @BeforeEach
    void cleanTables() {
        //Only clears data from substituted dummy Postgres container and not the production application.yml one.
        outboxRepository.deleteAll();
        orderRepository.deleteAll();
    }

    @Test
    //eventArrivesAfterCommit alone would pass even if the event were sent twice. 
    // The extra quiet period is what gives the test its name, eventArrivesExactlyOnceAfterCommit, 
    // and catches a publisher that re-sends a row it has already sent. 
    // (A 2 s window cannot prove there is never a duplicate, but it catches the obvious bugs.)
    void eventArrivesExactlyOnceAfterCommit() {
        OrderResponse created = orderService.create(request);
        String orderId = created.id().toString();

        try (KafkaConsumer<String, String> consumer = newConsumer()) {
            consumer.subscribe(List.of(KafkaConfig.ORDERS_EVENTS_TOPIC));

            // wait up to 15 s for the record with this order's key
            List<ConsumerRecord<String, String>> matching = new ArrayList<>();
            Instant deadline = Instant.now().plusSeconds(15);
            while (matching.isEmpty() && Instant.now().isBefore(deadline)) {
                // Poll for up to 15 s and keep only the records whose key equals the new order's id
                collectForKey(consumer, orderId, matching, Duration.ofMillis(500));
            }

            //Assert exactly one such record, whose value contains the order id, 
            // then poll another 2 s and assert no second record arrived.
            // assert(matching) wraps value in AssertJ assertion object;
            // .as("records for order %s", orderId) attaches a description which appears if a later assertion fails.
            assertThat(matching).as("records for order %s", orderId).hasSize(1);
            assertThat(matching.get(0).value()).contains(orderId);

            // keep listening for 2 s: a duplicate would show up here
            Instant quietUntil = Instant.now().plusSeconds(2);
            while (Instant.now().isBefore(quietUntil)) {
                collectForKey(consumer, orderId, matching, Duration.ofMillis(500));
            }
            assertThat(matching).as("duplicate records for order %s", orderId).hasSize(1);
        }
        
        //proves the publisher marked the row only after sending, 
        // which is what stops the row being sent again on the next poll. 
        // await() is needed because `markPublished()` is saved when the publisher's transaction commits, 
        // which can be slightly after the record is visible on the topic
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(outboxRepository.findAll()).hasSize(1)
                        .allSatisfy(e -> assertThat(e.getPublishedAt()).isNotNull()));
    }

    // PLAN step 13d. Kafka is paused (not stopped) to simulate an outage: a stopped and restarted container
    // would get a new random port that the already-created producer does not know about.
    @Test
    void unpublishedRowIsRetriedAndDeliveredAfterKafkaReturns() {
        String orderId;

        kafka.getDockerClient().pauseContainerCmd(kafka.getContainerId()).exec();
        try {
            orderId = orderService.create(request).id().toString();

            // During the outage the row must stay unpublished for 5 s (about 25 polls at 200 ms).
            // This holds even for the no-op publisher, so it is not what makes the test fail today.
            await().during(Duration.ofSeconds(5)).atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                    assertThat(outboxRepository.findAll()).hasSize(1)
                            .allSatisfy(e -> assertThat(e.getPublishedAt()).isNull()));
        } finally {
            // always bring Kafka back, even if an assertion above failed, so later tests are not stuck with a paused broker
            kafka.getDockerClient().unpauseContainerCmd(kafka.getContainerId()).exec();
        }

        // After Kafka returns, a later poll must deliver the row that failed earlier and mark it published.
        // This is the assertion that fails until OutboxPublisher.publishPending() is implemented (step 13e).
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(outboxRepository.findAll()).hasSize(1)
                        .allSatisfy(e -> assertThat(e.getPublishedAt()).isNotNull()));

        // The record must be on the topic exactly once: the failed attempts during the outage must not have produced copies.
        try (KafkaConsumer<String, String> consumer = newConsumer()) {
            consumer.subscribe(List.of(KafkaConfig.ORDERS_EVENTS_TOPIC));

            List<ConsumerRecord<String, String>> matching = new ArrayList<>();
            Instant deadline = Instant.now().plusSeconds(15);
            while (matching.isEmpty() && Instant.now().isBefore(deadline)) {
                collectForKey(consumer, orderId, matching, Duration.ofMillis(500));
            }
            Instant quietUntil = Instant.now().plusSeconds(2);
            while (Instant.now().isBefore(quietUntil)) {
                collectForKey(consumer, orderId, matching, Duration.ofMillis(500));
            }
            assertThat(matching).as("records for order %s", orderId).hasSize(1);
        }
    }

    private static void collectForKey(KafkaConsumer<String, String> consumer, String key,
                                      List<ConsumerRecord<String, String>> into, Duration timeout) {
        // What it does: asks Kafka once for new records, keeps only those whose key equals `key`
        // (the order id), and appends them to the caller's list. The caller calls it repeatedly
        // in a loop (see eventArrivesExactlyOnceAfterCommit), so one call is one "look" at the topic.
        //
        // Why `into` is a parameter (Java passes object references by value, modifies original list either way
        // , returning or not, ori copy always modified): the method returns nothing. It adds to the list the caller
        // passed in, so matches from several calls pile up in one place, and the test can count
        // them at the end (1 = delivered once, 2 = duplicate). 
        //
        // Why filter by key: the OutboxPublisher sends each record with key = aggregateId = order id.
        // The Kafka container and its orders.events topic are shared by every test in this class,
        // so the topic may also hold records from other orders. Without the filter, those would be
        // counted and the assertions would depend on which tests ran before.
        //
        // What poll(timeout) does: it blocks for up to `timeout` waiting for records and then
        // returns whatever arrived, which may be nothing (an empty batch, not an error). The first
        // polls of a new consumer often return empty while it joins its group and gets the topic's
        // partition assigned, which is why the caller loops until a deadline instead of polling once.
        //
        // The consumer commits no offsets and is used by one thread only (KafkaConsumer is not
        // thread-safe). It reads from "earliest" with a random group id, so it sees every record
        // on the topic, including ones sent before it subscribed.
        for (ConsumerRecord<String, String> record : consumer.poll(timeout)) {
            //Filtering by key (the order id, equals with aggregate id from OutboxPublisher)
            //  ignores records from other tests or other runs
            if (key.equals(record.key())) {
                into.add(record);
            }
        }
    }

    private static KafkaConsumer<String, String> newConsumer() {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        //The random group id means no committed offsets from an earlier run
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID());
        // `earliest` means a record sent before the consumer joined is still read
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        return new KafkaConsumer<>(props);
    }
}
