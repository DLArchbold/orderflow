package com.orderflow.inventory.messaging;

import com.orderflow.inventory.config.KafkaConfig;
import com.orderflow.inventory.service.InventoryService;
import java.nio.charset.StandardCharsets;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

/**
 * PLAN step 14e: only delegates. The container commits the offset after this method returns, so a crash
 * between the database commit and the offset commit re-delivers the record, which processed_messages absorbs.
 * A missing message-id header throws (step 16 sends such records to the dead-letter topic).
 */
@Component
public class OrderEventsListener {

    private final InventoryService inventoryService;

    public OrderEventsListener(InventoryService inventoryService) {
        this.inventoryService = inventoryService;
    }

    @KafkaListener(topics = KafkaConfig.ORDERS_EVENTS_TOPIC, groupId = "inventory-service")
    public void onOrderEvent(@Payload String payload, @Header("message-id") byte[] messageId) {
        inventoryService.reserve(new String(messageId, StandardCharsets.UTF_8), payload);
    }
}
