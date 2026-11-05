package com.orderflow.order.stock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** PLAN step 17c: the cache sits in front of the breaker; a hit never reaches inventory, a fallback is never cached. */
@SpringBootTest(properties = {"orderflow.outbox.enabled=false", "spring.kafka.listener.auto-startup=false"})
@Import(InventoryStubConfig.class)
@Testcontainers
class StockServiceCacheTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redis = new GenericContainer<>("redis:7").withExposedPorts(6379);

    @Autowired StockService service;
    @Autowired MockRestServiceServer stub;
    @Autowired CacheManager caches;

    @BeforeEach
    void reset() {
        stub.reset();
        caches.getCache("stock").clear();
    }

    @Test
    void twoReadsCauseOneCallToInventory() {
        stub.expect(once(), requestTo("http://inventory.test/stock/SKU-1"))
                .andRespond(withSuccess("{\"sku\":\"SKU-1\",\"available\":7,\"reserved\":3}", MediaType.APPLICATION_JSON));

        assertThat(service.get("SKU-1").available()).isEqualTo(7);
        assertThat(service.get("SKU-1").available()).isEqualTo(7);

        stub.verify();   // exactly one request reached the stub
    }

    @Test
    void aFailureIsNotCached() {
        // two expected requests, in order: a failure, then a success
        stub.expect(once(), requestTo("http://inventory.test/stock/SKU-2")).andRespond(withServerError());
        stub.expect(once(), requestTo("http://inventory.test/stock/SKU-2"))
                .andRespond(withSuccess("{\"sku\":\"SKU-2\",\"available\":5,\"reserved\":0}", MediaType.APPLICATION_JSON));

        assertThat(service.get("SKU-2")).isEqualTo(StockInfo.unknown("SKU-2"));
        assertThat(service.get("SKU-2").available()).isEqualTo(5);   // the second read went to inventory again
        stub.verify();
    }
}
