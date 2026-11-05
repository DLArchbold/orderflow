package com.orderflow.order.stream;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * AFTER_COMMIT matters: an event sent from inside the transaction could reach the browser before the new status is
 * visible to a query, and the UI would then show a stale list.
 */
@Component
public class OrderStatusBroadcaster {
    public record StatusEvent(java.util.UUID orderId, String status) {}

    private final SseHub hub;

    public OrderStatusBroadcaster(SseHub hub) {
        this.hub = hub;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onChange(OrderStatusChanged changed) {
        hub.send(changed.customerId(), new StatusEvent(changed.orderId(), changed.status()));
    }
}
