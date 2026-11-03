package com.orderflow.order.web;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;


//Instead of writing a normal class with private fields, a constructor, getters, equals(), hashCode(), and toString() methods by hand, a record lets you declare all of that in a single line
public record CreateOrderRequest(
        @Schema(description = "ID of the customer placing the order", example = "CUST-1001")
        @NotBlank String customerId,
        @Schema(description = "Items to order; at least one is required")
        @NotEmpty @Valid List<Item> items) {

    public record Item(
            @Schema(description = "Product SKU", example = "SKU-123")
            @NotBlank String sku,
            @Schema(description = "Number of units; must be at least 1", example = "2")
            @Min(1) int quantity) {}
}
