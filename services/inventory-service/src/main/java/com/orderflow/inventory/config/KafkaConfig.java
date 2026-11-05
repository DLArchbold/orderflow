package com.orderflow.inventory.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaConfig {
    public static final String ORDERS_EVENTS_TOPIC = "orders.events";
    public static final String ORDERS_EVENTS_DLT = "orders.events.DLT";
    public static final String INVENTORY_RESULTS_TOPIC = "inventory.results";

    // orders.events is declared here too so inventory starts even before order-service has.
    @Bean NewTopic ordersEventsTopic() { return TopicBuilder.name(ORDERS_EVENTS_TOPIC).partitions(1).replicas(1).build(); }
    @Bean NewTopic ordersEventsDlt() { return TopicBuilder.name(ORDERS_EVENTS_DLT).partitions(1).replicas(1).build(); }
    @Bean NewTopic inventoryResultsTopic() { return TopicBuilder.name(INVENTORY_RESULTS_TOPIC).partitions(1).replicas(1).build(); }
}
