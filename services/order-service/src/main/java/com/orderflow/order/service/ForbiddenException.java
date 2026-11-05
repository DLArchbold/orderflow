package com.orderflow.order.service;

import java.util.UUID;

/** The caller is authenticated but the order belongs to someone else (mapped to 403; see phase2/docs/security.md). */
public class ForbiddenException extends RuntimeException {
    public ForbiddenException(UUID orderId) {
        super("You are not allowed to access order " + orderId);
    }
}
