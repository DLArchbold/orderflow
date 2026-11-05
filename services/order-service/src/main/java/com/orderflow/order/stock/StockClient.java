package com.orderflow.order.stock;

import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * PLAN step 17a: synchronous call to inventory-service. Not part of order creation, so orders are still accepted
 * when inventory-service is down (ADR 0005); the asynchronous path keeps orders correct.
 */
@Component
public class StockClient {
    private final RestClient client;

    public StockClient(RestClient inventoryRestClient) {
        this.client = inventoryRestClient;
    }

    /**
     * PLAN step 17b: the timeout bounds one call, the breaker stops calling a dead service at all, the bulkhead
     * limits how many threads this dependency can use. The fallback says "unknown", not 0 (which would mean sold out).
     */
    @CircuitBreaker(name = "inventory", fallbackMethod = "unknown")
    @Bulkhead(name = "inventory")
    public StockInfo get(String sku) {
        return client.get().uri("/stock/{sku}", sku).retrieve().body(StockInfo.class);
    }

    StockInfo unknown(String sku, Throwable cause) {
        return StockInfo.unknown(sku);
    }
}
