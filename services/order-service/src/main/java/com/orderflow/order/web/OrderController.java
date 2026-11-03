package com.orderflow.order.web;

import com.orderflow.order.service.OrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Thin HTTP layer: delegates to OrderService. Errors (404, 409, 400) are turned into
 * JSON messages by GlobalExceptionHandler.
 */
@RestController
@RequestMapping("/api/orders")
@Tag(name = "Orders", description = "Create, view, confirm and cancel orders")
public class OrderController {

    private static final String ID_EXAMPLE = "3fa85f64-5717-4562-b3fc-2c963f66afa6";

    private final OrderService service;
    public OrderController (OrderService service){
        //Utilize Spring's Dependency Injection to assign service reference here to OrderService Singleton instance
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "List orders",
            description = "Returns all orders together with their line items.")
    @ApiResponse(responseCode = "200", description = "The orders (empty list if there are none)")
    public ResponseEntity<List<OrderResponse>> list() {
        return ResponseEntity.ok(service.list());
    }

    @PostMapping
    @Operation(summary = "Create an order (starts as PENDING)",
            description = "Creates an order with the given items. The new order always starts with status PENDING. "
                    + "The Location header points to the new order.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Order created with status PENDING"),
            @ApiResponse(responseCode = "400",
                    description = "Invalid input, e.g. blank customerId, empty items, or quantity below 1",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<OrderResponse> create(@Valid @RequestBody CreateOrderRequest request) {
        OrderResponse created = service.create(request);
        return ResponseEntity.created(URI.create("/api/orders/" + created.id())).body(created);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get one order", description = "Returns a single order by its ID.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The order"),
            @ApiResponse(responseCode = "400", description = "The ID is not a valid UUID",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "No order with this ID",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<OrderResponse> get(
            @Parameter(description = "Order ID", example = ID_EXAMPLE) @PathVariable UUID id) {
        return ResponseEntity.ok(service.get(id));
    }

    @PostMapping("/{id}/confirm")
    @Operation(summary = "Confirm an order (temporary stand-in for the Phase 2 inventory check)",
            description = "Moves the order from PENDING to CONFIRMED. Only PENDING orders can be confirmed.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Order confirmed (status CONFIRMED)"),
            @ApiResponse(responseCode = "400", description = "The ID is not a valid UUID",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "No order with this ID",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "The order is not PENDING, so it cannot be confirmed",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<OrderResponse> confirm(
            @Parameter(description = "Order ID", example = ID_EXAMPLE) @PathVariable UUID id) {
        return ResponseEntity.ok(service.confirm(id));
    }

    @PostMapping("/{id}/cancel")
    @Operation(summary = "Cancel an order",
            description = "Moves the order to CANCELLED. Only PENDING or CONFIRMED orders can be cancelled.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Order cancelled (status CANCELLED)"),
            @ApiResponse(responseCode = "400", description = "The ID is not a valid UUID",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "No order with this ID",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "The order is already CANCELLED or REJECTED",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<OrderResponse> cancel(
            @Parameter(description = "Order ID", example = ID_EXAMPLE) @PathVariable UUID id) {
        return ResponseEntity.ok(service.cancel(id));
    }
}
