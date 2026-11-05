package com.orderflow.order.service;

import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** PLAN step 15c: applies an inventory result (RESERVED / REJECTED) to the order, idempotently. */
@Service
@Transactional
public class InventoryResultHandler {

    public void apply(UUID orderId, String result) {
        // TODO (15c)
    }
}
