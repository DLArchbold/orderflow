package com.orderflow.order.stock;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** Replaces the real RestClient with one bound to a stub server, so tests control what "inventory-service" answers. */
@TestConfiguration
class InventoryStubConfig {
    static final RestClient.Builder BUILDER = RestClient.builder().baseUrl("http://inventory.test");

    @Bean
    MockRestServiceServer inventoryStub() {
        return MockRestServiceServer.bindTo(BUILDER).build();
    }

    @Bean
    @Primary
    RestClient stubRestClient(MockRestServiceServer inventoryStub) {
        return BUILDER.build();
    }
}
