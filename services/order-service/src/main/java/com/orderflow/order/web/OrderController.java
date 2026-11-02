package com.orderflow.order.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * PHASE 1, steps 4-6 of docs/PLAN.md: replace every 501 below with a real implementation
 * (entities + repository first, then these methods). Check each one in Swagger UI as you go.
 */
@RestController
@RequestMapping("/api/orders")
@Tag(name = "Orders")
public class OrderController {

    @GetMapping
    @Operation(summary = "List orders")
    public ResponseEntity<List<OrderResponse>> list() {
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();   // TODO
    }

    @PostMapping
    @Operation(summary = "Create an order (starts as PENDING)")
    public ResponseEntity<OrderResponse> create(@Valid @RequestBody CreateOrderRequest request) {
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();   // TODO return 201
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get one order")
    public ResponseEntity<OrderResponse> get(@PathVariable UUID id) {
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();   // TODO 404 if missing
    }

    @PostMapping("/{id}/confirm")
    @Operation(summary = "Confirm an order (temporary stand-in for the Phase 2 inventory check)")
    public ResponseEntity<OrderResponse> confirm(@PathVariable UUID id) {
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();   // TODO 409 if not allowed
    }

    @PostMapping("/{id}/cancel")
    @Operation(summary = "Cancel an order")
    public ResponseEntity<OrderResponse> cancel(@PathVariable UUID id) {
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();   // TODO 409 if not allowed
    }
}
