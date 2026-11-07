package com.orderflow.order.web;

import com.orderflow.order.stream.SseHub;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/orders")
@Tag(name = "Orders")
public class OrderStreamController {
    private final SseHub hub;

    public OrderStreamController(SseHub hub) {
        this.hub = hub;
    }

    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "Live status changes of your orders (server-sent events)",
            description = "Sends {\"orderId\",\"status\"} whenever one of your orders changes status. Missed events are not replayed: refetch the list after reconnecting.")
    public SseEmitter stream(Authentication auth) {
        return hub.register(auth.getName());
    }
}
