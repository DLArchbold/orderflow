package com.orderflow.order.web;

import java.util.List;
import java.util.UUID;

public record OrderResponse(UUID id, String customerId, String status, List<Item> items) {
    public record Item(String sku, int quantity) {}
}
