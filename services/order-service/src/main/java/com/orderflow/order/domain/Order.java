package com.orderflow.order.domain;

import java.util.List;
import java.util.UUID;

/** Pure domain aggregate (no framework types). The JPA mapping is added in PLAN step 2. */
public class Order {
    public record Line(String sku, int quantity) {
        public Line {
            if (sku == null || sku.isBlank()) throw new IllegalArgumentException("sku required");
            if (quantity <= 0) throw new IllegalArgumentException("quantity must be positive");
        }
    }

    private final UUID id;
    private final List<Line> lines;
    private OrderStatus status = OrderStatus.PENDING;

    public Order(UUID id, List<Line> lines) {
        if (lines == null || lines.isEmpty()) throw new IllegalArgumentException("at least one line required");
        this.id = id;
        this.lines = List.copyOf(lines);
    }

    public UUID id() { return id; }
    public List<Line> lines() { return lines; }
    public OrderStatus status() { return status; }

    public void confirm() { transitionTo(OrderStatus.CONFIRMED); }
    public void reject() { transitionTo(OrderStatus.REJECTED); }
    public void cancel() { transitionTo(OrderStatus.CANCELLED); }

    private void transitionTo(OrderStatus next) {
        if (!status.allowedNext().contains(next)) {
            throw new IllegalStateException("Illegal transition " + status + " -> " + next);
        }
        status = next;
    }
}
