package com.orderflow.order.stock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.client.ExpectedCount.manyTimes;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * PLAN step 17b: five failures open the breaker; an open breaker answers the fallback without reaching
 * inventory-service; after the wait a succeeding call closes it again.
 */
@SpringBootTest(properties = {
        "orderflow.outbox.enabled=false",
        "spring.kafka.listener.auto-startup=false",
        "resilience4j.circuitbreaker.instances.inventory.wait-duration-in-open-state=1s"})
@Import(InventoryStubConfig.class)
@Testcontainers
class StockClientResilienceTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired StockClient client;
    @Autowired MockRestServiceServer stub;
    @Autowired CircuitBreakerRegistry breakers;

    @BeforeEach
    void reset() {
        stub.reset();
        breakers.circuitBreaker("inventory").reset();
    }

    @Test
    void fiveFailuresOpenTheBreakerAndTheFallbackIsServedWithoutCallingInventory() {
        stub.expect(times(5), requestTo("http://inventory.test/stock/SKU-1")).andRespond(withServerError());

        for (int i = 0; i < 5; i++) {
            assertThat(client.get("SKU-1")).isEqualTo(StockInfo.unknown("SKU-1"));
        }
        assertThat(breakers.circuitBreaker("inventory").getState()).isEqualTo(CircuitBreaker.State.OPEN);

        // the stub expects no sixth request: reaching it would throw
        assertThat(client.get("SKU-1")).isEqualTo(StockInfo.unknown("SKU-1"));
        stub.verify();
    }

    @Test
    void afterTheWaitASucceedingCallClosesTheBreakerAgain() throws Exception {
        stub.expect(times(5), requestTo("http://inventory.test/stock/SKU-1")).andRespond(withServerError());
        for (int i = 0; i < 5; i++) {
            client.get("SKU-1");
        }
        assertThat(breakers.circuitBreaker("inventory").getState()).isEqualTo(CircuitBreaker.State.OPEN);

        stub.reset();
        stub.expect(manyTimes(), requestTo("http://inventory.test/stock/SKU-1"))
                .andRespond(withSuccess("{\"sku\":\"SKU-1\",\"available\":9,\"reserved\":1}", MediaType.APPLICATION_JSON));

        // open -> half-open happens on the first call after the 1 s wait; three permitted successes close it
        await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(300)).untilAsserted(() -> {
            client.get("SKU-1");
            assertThat(breakers.circuitBreaker("inventory").getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        });
        assertThat(client.get("SKU-1").available()).isEqualTo(9);
    }
}
