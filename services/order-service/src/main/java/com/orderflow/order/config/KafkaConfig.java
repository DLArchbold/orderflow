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
    @Bean
    public NewTopic ordersEventsTopic() {
        return TopicBuilder.name(ORDERS_EVENTS_TOPIC).partitions(1).replicas(1).build();
    }
}
