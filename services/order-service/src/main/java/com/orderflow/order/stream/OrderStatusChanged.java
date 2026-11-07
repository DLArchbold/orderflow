package com.orderflow.order.stream;

import java.util.UUID;

/** Published inside the transaction that changes an order's status; delivered to browsers only after it commits. */
public record OrderStatusChanged(UUID orderId, String customerId, String status) {}
