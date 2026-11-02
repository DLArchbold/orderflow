package com.orderflow.order.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;


//Instead of writing a normal class with private fields, a constructor, getters, equals(), hashCode(), and toString() methods by hand, a record lets you declare all of that in a single line
public record CreateOrderRequest(
        @NotBlank String customerId,
        @NotEmpty @Valid List<Item> items) {

    public record Item(@NotBlank String sku, @Min(1) int quantity) {}
}
