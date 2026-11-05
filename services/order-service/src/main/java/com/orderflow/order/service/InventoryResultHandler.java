package com.orderflow.order.service;

import com.orderflow.order.domain.OrderStatus;
import com.orderflow.order.persistence.OrderEntity;
import com.orderflow.order.persistence.OrderRepository;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * PLAN step 15c: applies an inventory result (RESERVED / REJECTED) to the order. "Apply only if PENDING" makes it
 * idempotent: repeating a result gives the same final state, so no processed_messages table is needed here.
 * Unknown orders and orders that are no longer PENDING are ignored (ADR 0003); throwing would block the partition.
 */
@Service
@Transactional
public class InventoryResultHandler {
    private static final Logger log = LoggerFactory.getLogger(InventoryResultHandler.class);

    private final OrderRepository orders;
    private final org.springframework.context.ApplicationEventPublisher events;

    public InventoryResultHandler(OrderRepository orders, org.springframework.context.ApplicationEventPublisher events) {
        this.orders = orders;
        this.events = events;
    }

    public void apply(UUID orderId, String result) {
        Optional<OrderEntity> found = orders.findById(orderId);
        if (found.isEmpty()) {
            log.warn("Inventory result {} for unknown order {} ignored", result, orderId);
            return;
        }
        OrderEntity order = found.get();
        if (order.getStatus() != OrderStatus.PENDING) {
            if (order.getStatus() == OrderStatus.CANCELLED && "RESERVED".equals(result)) {
                log.warn("Stock was reserved for CANCELLED order {}; it stays reserved (known limit, ADR 0003)", orderId);
            } else {
                log.info("Inventory result {} for order {} in status {} ignored", result, orderId, order.getStatus());
            }
            return;
        }
        switch (result) {
            case "RESERVED" -> order.confirm();   // dirty checking persists the change at commit
            case "REJECTED" -> order.reject();
            default -> throw new IllegalArgumentException("unknown inventory result: " + result);
        }
        // delivered to browsers only after this transaction commits (OrderStatusBroadcaster)
        events.publishEvent(new com.orderflow.order.stream.OrderStatusChanged(
                order.getId(), order.getCustomerId(), order.getStatus().name()));
    }
}
