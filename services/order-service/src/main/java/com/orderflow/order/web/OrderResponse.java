package com.orderflow.order.web;

import java.util.List;
import java.util.UUID;

import com.orderflow.order.persistence.OrderEntity;
import io.swagger.v3.oas.annotations.media.Schema;

//Instead of writing a normal class with private fields, a constructor, getters, equals(), hashCode(), and toString() methods by hand, a record lets you declare all of that in a single line
//For why this class of type record is needed, see claude_exports\why-order-response-class-of-type-record-is-needed.txt
public record OrderResponse(
        @Schema(description = "Unique order ID", example = "3fa85f64-5717-4562-b3fc-2c963f66afa6")
        UUID id,
        @Schema(description = "ID of the customer who placed the order", example = "CUST-1001")
        String customerId,
        @Schema(description = "Current order status", example = "PENDING",
                allowableValues = {"PENDING", "CONFIRMED", "REJECTED", "CANCELLED"})
        String status,
        @Schema(description = "Line items in the order")
        List<Item> items) {

    public record Item(
            @Schema(description = "Product SKU", example = "SKU-123")
            String sku,
            @Schema(description = "Number of units", example = "2")
            int quantity) {}

    public static OrderResponse from(OrderEntity e){
        List<Item> lineItems = e.getLines().stream().map(l -> new Item(l.getSku(), l.getQuantity())).toList();
        return new OrderResponse(e.getId(), e.getCustomerId(), e.getStatus().name(), lineItems);
    }
}
