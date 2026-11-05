package com.orderflow.order.stock;

import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

/**
 * PLAN step 17c: Redis cache in front of the circuit breaker, so a hit never reaches inventory-service and the
 * fallback (available == null) is never cached. Staleness: only the 30 s TTL (ADR 0005).
 */
@Service
public class StockService {
    private final StockClient client;

    public StockService(StockClient client) {
        this.client = client;
    }

    @Cacheable(cacheNames = "stock", key = "#sku", unless = "#result.available == null")
    public StockInfo get(String sku) {
        return client.get(sku);
    }
}
