package com.orderflow.order.stock;

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

    public StockInfo get(String sku) {
        return client.get().uri("/stock/{sku}", sku).retrieve().body(StockInfo.class);
    }
}
