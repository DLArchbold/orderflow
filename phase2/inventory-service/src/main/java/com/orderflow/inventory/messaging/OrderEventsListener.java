package com.orderflow.inventory.messaging;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * PLAN step 6: in ONE transaction: insert message id into processed_messages (skip if it already exists),
 * reserve stock, write the result event. PLAN step 8: retries + dead-letter topic.
 */
@Component
public class OrderEventsListener {

    @KafkaListener(topics = "orders.events", groupId = "inventory-service")
    public void onOrderEvent(String payload) {
        // TODO
    }
}
