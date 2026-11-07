package com.orderflow.inventory.web;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** PLAN step 17a: read-only stock lookup used by order-service (a convenience for the UI, not part of order creation). */
@RestController
public class StockController {

    public record StockView(String sku, int available, int reserved) {}

    private final JdbcClient jdbc;

    public StockController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/stock/{sku}")
    public StockView get(@PathVariable String sku) {
        return jdbc.sql("SELECT sku, available, reserved FROM stock WHERE sku = ?")
                .param(sku)
                .query((rs, i) -> new StockView(rs.getString("sku"), rs.getInt("available"), rs.getInt("reserved")))
                .optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown SKU " + sku));
    }
}
