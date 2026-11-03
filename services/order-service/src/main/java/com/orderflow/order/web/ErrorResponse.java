package com.orderflow.order.web;

public class ErrorResponse {
    private final String message;

    public ErrorResponse(String message) {
        this.message = message;
    }

    // Jackson uses this getter to write the "message" JSON property
    public String getMessage() {
        return message;
    }
}
