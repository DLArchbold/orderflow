package com.orderflow.order.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaConfig {

    /** Topic the OutboxPublisher writes to and inventory-service listens on. */
    public static final String ORDERS_EVENTS_TOPIC = "orders.events";

    // KafkaAdmin creates this topic at startup if it is missing (it only logs a warning if Kafka is down).
    //How does kafkaAdmin start? See notes\kafka\how_kafka_admin_statrts.txt
    //Why is this NewTopic Bean important? See bottom of 13b in PLAN.
    /** Topic inventory-service writes results to and order-service listens on. */
    public static final String INVENTORY_RESULTS_TOPIC = "inventory.results";

    public static final String INVENTORY_RESULTS_DLT = "inventory.results.DLT";

    @Bean
    public NewTopic inventoryResultsDlt() {
        return TopicBuilder.name(INVENTORY_RESULTS_DLT).partitions(1).replicas(1).build();
    }

    @Bean
    public NewTopic inventoryResultsTopic() {
        return TopicBuilder.name(INVENTORY_RESULTS_TOPIC).partitions(1).replicas(1).build();
    }

    @Bean
    public NewTopic ordersEventsTopic() {
        return TopicBuilder.name(ORDERS_EVENTS_TOPIC).partitions(1).replicas(1).build();
    }
}
