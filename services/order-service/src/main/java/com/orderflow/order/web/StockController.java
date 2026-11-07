package com.orderflow.order.web;

import com.orderflow.order.stock.StockService;
import com.orderflow.order.stock.StockInfo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/stock")
@Tag(name = "Stock", description = "Stock levels from inventory-service (a convenience; orders do not depend on it)")
public class StockController {
    private final StockService service;

    public StockController(StockService service) {
        this.service = service;
    }

    @GetMapping("/{sku}")
    @Operation(summary = "Stock of one SKU",
            description = "Returns available and reserved units; available is null when inventory-service cannot be reached.")
    public StockInfo get(@PathVariable String sku) {
        return service.get(sku);
    }
}
