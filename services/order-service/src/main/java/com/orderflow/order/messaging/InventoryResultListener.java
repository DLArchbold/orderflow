package com.orderflow.order.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderflow.order.config.KafkaConfig;
import com.orderflow.order.service.InventoryResultHandler;
import java.util.UUID;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** PLAN step 15c: reads inventory.results and moves the order to CONFIRMED or REJECTED. */
@Component
public class InventoryResultListener {

    @JsonIgnoreProperties(ignoreUnknown = true)
    record InventoryResult(UUID orderId, String result) {}

    private final InventoryResultHandler handler;
    private final ObjectMapper mapper;

    public InventoryResultListener(InventoryResultHandler handler, ObjectMapper mapper) {
        this.handler = handler;
        this.mapper = mapper;
    }

    @KafkaListener(topics = KafkaConfig.INVENTORY_RESULTS_TOPIC, groupId = "order-service")
    public void onResult(String payload,
            @org.springframework.messaging.handler.annotation.Header(name = "correlation-id", required = false) byte[] correlationId) {
        if (correlationId != null) {
            org.slf4j.MDC.put("correlationId", new String(correlationId, java.nio.charset.StandardCharsets.UTF_8));
        }
        try {
            InventoryResult r = mapper.readValue(payload, InventoryResult.class);
            if (r.orderId() == null || r.result() == null) {
                throw new IllegalArgumentException("inventory result without orderId or result");
            }
            handler.apply(r.orderId(), r.result());
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("unreadable inventory result", e);
        } finally {
            org.slf4j.MDC.remove("correlationId");
        }
    }
}
