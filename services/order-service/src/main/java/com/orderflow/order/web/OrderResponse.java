package com.orderflow.order.web;

import java.util.List;
import java.util.UUID;

//Instead of writing a normal class with private fields, a constructor, getters, equals(), hashCode(), and toString() methods by hand, a record lets you declare all of that in a single line
//For why this class of type record is needed, see claude_exports\why-order-response-class-of-type-record-is-needed.txt
public record OrderResponse(UUID id, String customerId, String status, List<Item> items) {
    public record Item(String sku, int quantity) {}
}
