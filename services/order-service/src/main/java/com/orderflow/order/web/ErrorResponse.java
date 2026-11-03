package com.orderflow.order.web;

import io.swagger.v3.oas.annotations.media.Schema;

public class ErrorResponse {
    @Schema(description = "Human-readable explanation of what went wrong",
            example = "items: must not be empty")
            
    private final String message;

    public ErrorResponse(String message) {
        this.message = message;
    }

    // Jackson uses this getter to write the "message" JSON property
    public String getMessage() {
        return message;
    }
}
