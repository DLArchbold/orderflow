package com.orderflow.inventory.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderflow.inventory.outbox.OutboxEvent;
import com.orderflow.inventory.outbox.OutboxRepository;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * PLAN step 14e: idempotent, all-or-nothing stock reservation. The processed_messages insert, the stock
 * updates, the reservations and the result event (outbox row) all commit in ONE transaction.
 */
@Service
public class InventoryService {

    @JsonIgnoreProperties(ignoreUnknown = true)
    record OrderEvent(@JsonProperty("id") UUID orderId, List<Item> items) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Item(String sku, int quantity) {}

    record Result(String orderId, String result, String reason) {}

    static final String REJECT_REASON = "insufficient or unknown sku";

    private final JdbcClient jdbc;
    private final OutboxRepository outbox;
    private final ObjectMapper mapper;
    private final com.orderflow.inventory.outbox.OutboxTrace trace;

    public InventoryService(JdbcClient jdbc, OutboxRepository outbox, ObjectMapper mapper,
                            com.orderflow.inventory.outbox.OutboxTrace trace) {
        this.trace = trace;
        this.jdbc = jdbc;
        this.outbox = outbox;
        this.mapper = mapper;
    }

    @Transactional
    public void reserve(String messageId, String orderJson) {
        // The insert is both the check and the lock: a concurrent copy of the same message waits for this
        // transaction and then inserts 0 rows.
        int inserted = jdbc.sql("INSERT INTO processed_messages (message_id) VALUES (?) ON CONFLICT DO NOTHING")
                .param(messageId).update();
        if (inserted == 0) {
            return;
        }

        OrderEvent order = parse(orderJson);
        // Sum lines per SKU and sort by SKU, so two orders touching the same SKUs lock rows in the same order.
        Map<String, Integer> wanted = new TreeMap<>();
        for (Item item : order.items()) {
            wanted.merge(item.sku(), item.quantity(), Integer::sum);
        }

        // Lock first, check every line, update only when all lines fit: a rejection then needs no rollback,
        // so the processed_messages row and the REJECTED outbox row survive.
        Map<String, Integer> available = new TreeMap<>();
        if (!wanted.isEmpty()) {
            jdbc.sql("SELECT sku, available FROM stock WHERE sku IN (:skus) ORDER BY sku FOR UPDATE")
                    .param("skus", wanted.keySet())
                    .query((rs, i) -> {
                        available.put(rs.getString("sku"), rs.getInt("available"));
                        return null;
                    }).list();
        }
        boolean fits = !wanted.isEmpty() && wanted.entrySet().stream()
                .allMatch(e -> available.getOrDefault(e.getKey(), -1) >= e.getValue());

        if (fits) {
            wanted.forEach((sku, qty) -> {
                int updated = jdbc.sql("UPDATE stock SET available = available - ?, reserved = reserved + ? "
                                + "WHERE sku = ? AND available >= ?")
                        .param(qty).param(qty).param(sku).param(qty).update();
                if (updated != 1) {
                    throw new IllegalStateException("stock changed under lock for " + sku);
                }
                jdbc.sql("INSERT INTO reservations (order_id, sku, quantity) VALUES (?, ?, ?)")
                        .param(order.orderId()).param(sku).param(qty).update();
            });
            writeResult(order, "StockReserved", "RESERVED", null);
        } else {
            writeResult(order, "StockRejected", "REJECTED", REJECT_REASON);
        }
    }

    private OrderEvent parse(String json) {
        try {
            OrderEvent order = mapper.readValue(json, OrderEvent.class);
            if (order.orderId() == null || order.items() == null) {
                throw new IllegalArgumentException("order event without id or items");
            }
            return order;
        } catch (JsonProcessingException e) {
            // Not retryable (step 16): the whole transaction, including the processed_messages row, rolls back.
            throw new IllegalArgumentException("unreadable order event", e);
        }
    }

    private void writeResult(OrderEvent order, String type, String result, String reason) {
        try {
            String payload = mapper.writeValueAsString(new Result(order.orderId().toString(), result, reason));
            OutboxEvent event = new OutboxEvent(order.orderId().toString(), type, payload);
            // the consumed record's span and correlation id travel with the result event
            event.attachContext(trace.capture(), org.slf4j.MDC.get("correlationId"));
            outbox.save(event);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
