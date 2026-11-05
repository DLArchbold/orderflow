package com.orderflow.inventory.service;

import org.springframework.stereotype.Service;

/** PLAN step 14e: idempotent, all-or-nothing stock reservation. */
@Service
public class InventoryService {

    public void reserve(String messageId, String orderJson) {
        // TODO (14e)
    }
}
