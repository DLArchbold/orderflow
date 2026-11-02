package com.orderflow.order.domain;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OrderTest {
    private Order newOrder() {
        return new Order(UUID.randomUUID(), List.of(new Order.Line("SKU-1", 2)));
    }

    @Test void startsPending() { assertEquals(OrderStatus.PENDING, newOrder().status()); }

    @Test void pendingCanBeConfirmed() {
        Order o = newOrder();
        o.confirm();
        assertEquals(OrderStatus.CONFIRMED, o.status());
    }

    @Test void confirmedCanBeCancelled() {
        Order o = newOrder();
        o.confirm();
        o.cancel();
        assertEquals(OrderStatus.CANCELLED, o.status());
    }

    @Test void rejectedIsTerminal() {
        Order o = newOrder();
        o.reject();
        assertThrows(IllegalStateException.class, o::confirm);
        assertThrows(IllegalStateException.class, o::cancel);
    }

    @Test void cannotConfirmTwice() {
        Order o = newOrder();
        o.confirm();
        assertThrows(IllegalStateException.class, o::confirm);
    }

    @Test void rejectsEmptyOrBadLines() {
        assertThrows(IllegalArgumentException.class, () -> new Order(UUID.randomUUID(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new Order.Line("SKU-1", 0));
    }
}
