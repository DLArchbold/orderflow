package com.orderflow.order.stock;

import java.io.Serializable;

/** Stock levels of one SKU as seen through inventory-service. {@code available == null} means "unknown". */
public record StockInfo(String sku, Integer available, Integer reserved) implements Serializable {

    /** Fallback value: we do not know, which is not the same as 0 (sold out). */
    public static StockInfo unknown(String sku) {
        return new StockInfo(sku, null, null);
    }
}
