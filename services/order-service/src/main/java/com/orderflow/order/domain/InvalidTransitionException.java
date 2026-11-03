package com.orderflow.order.domain;

public class InvalidTransitionException extends RuntimeException {
    public InvalidTransitionException(OrderStatus from, OrderStatus to) {
        super("Illegal transition " + from + " -> " + to);
    }
}
