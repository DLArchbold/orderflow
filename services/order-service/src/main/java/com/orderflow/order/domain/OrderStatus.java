package com.orderflow.order.domain;

import java.util.Set;

public enum OrderStatus {
    PENDING, CONFIRMED, REJECTED, CANCELLED;

    public Set<OrderStatus> allowedNext() {
        return switch (this) {
            case PENDING -> Set.of(CONFIRMED, REJECTED, CANCELLED);
            case CONFIRMED, REJECTED, CANCELLED -> Set.of();
        };
    }
}
