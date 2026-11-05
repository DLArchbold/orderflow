package com.orderflow.inventory.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * PLAN step 14c: the stock rules are database rules, so they are tested on a real PostgreSQL without Kafka
 * (publisher and listener are switched off).
 */
@SpringBootTest(properties = {"orderflow.outbox.enabled=false", "spring.kafka.listener.auto-startup=false"})
@Testcontainers
class InventoryServiceTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired InventoryService inventoryService;
    @Autowired JdbcClient jdbc;

    @BeforeEach
    void resetState() {
        jdbc.sql("DELETE FROM reservations").update();
        jdbc.sql("DELETE FROM processed_messages").update();
        jdbc.sql("DELETE FROM outbox").update();
        jdbc.sql("UPDATE stock SET available = 100, reserved = 0").update();
    }

    private static String order(UUID id, String... skuQty) {
        StringBuilder items = new StringBuilder();
        for (int i = 0; i < skuQty.length; i += 2) {
            if (i > 0) items.append(',');
            items.append("{\"sku\":\"").append(skuQty[i]).append("\",\"quantity\":").append(skuQty[i + 1]).append('}');
        }
        return "{\"id\":\"" + id + "\",\"customerId\":\"alice\",\"status\":\"PENDING\",\"items\":[" + items + "]}";
    }

    private int available(String sku) {
        return jdbc.sql("SELECT available FROM stock WHERE sku = ?").param(sku).query(Integer.class).single();
    }

    private int reserved(String sku) {
        return jdbc.sql("SELECT reserved FROM stock WHERE sku = ?").param(sku).query(Integer.class).single();
    }

    private int count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Integer.class).single();
    }

    private List<String> results(UUID orderId) {
        return jdbc.sql("SELECT payload::jsonb ->> 'result' FROM outbox WHERE aggregate_id = ?")
                .param(orderId.toString()).query(String.class).list();
    }

    @Test
    void reservingMovesStockAndWritesOneRowOfEachKind() {
        UUID orderId = UUID.randomUUID();
        inventoryService.reserve("m-1", order(orderId, "SKU-1", "2"));

        assertThat(available("SKU-1")).isEqualTo(98);
        assertThat(reserved("SKU-1")).isEqualTo(2);
        assertThat(count("reservations")).isEqualTo(1);
        assertThat(count("processed_messages")).isEqualTo(1);
        assertThat(count("outbox")).isEqualTo(1);
        assertThat(jdbc.sql("SELECT type FROM outbox").query(String.class).single()).isEqualTo("StockReserved");
        assertThat(results(orderId)).containsExactly("RESERVED");
    }

    @Test
    void sameMessageIdTwiceReservesOnce() {
        UUID orderId = UUID.randomUUID();
        inventoryService.reserve("m-1", order(orderId, "SKU-1", "2"));
        inventoryService.reserve("m-1", order(orderId, "SKU-1", "2"));

        assertThat(available("SKU-1")).isEqualTo(98);
        assertThat(count("reservations")).isEqualTo(1);
        assertThat(count("processed_messages")).isEqualTo(1);
        assertThat(count("outbox")).isEqualTo(1);
    }

    @Test
    void insufficientStockIsRejectedAndTheMessageIdIsStillRecorded() {
        UUID orderId = UUID.randomUUID();
        inventoryService.reserve("m-1", order(orderId, "SKU-1", "101"));

        assertThat(available("SKU-1")).isEqualTo(100);
        assertThat(reserved("SKU-1")).isZero();
        assertThat(count("reservations")).isZero();
        assertThat(count("processed_messages")).isEqualTo(1);
        assertThat(jdbc.sql("SELECT type FROM outbox").query(String.class).single()).isEqualTo("StockRejected");
        assertThat(results(orderId)).containsExactly("REJECTED");

        // a re-delivered rejection must not emit a second result
        inventoryService.reserve("m-1", order(orderId, "SKU-1", "101"));
        assertThat(count("outbox")).isEqualTo(1);
    }

    @Test
    void aFailingLineLeavesEveryLineUntouched() {
        UUID orderId = UUID.randomUUID();
        inventoryService.reserve("m-1", order(orderId, "SKU-1", "2", "SKU-2", "101"));

        assertThat(available("SKU-1")).isEqualTo(100);
        assertThat(reserved("SKU-1")).isZero();
        assertThat(available("SKU-2")).isEqualTo(100);
        assertThat(count("reservations")).isZero();
        assertThat(results(orderId)).containsExactly("REJECTED");
    }

    @Test
    void twoThreadsReservingTheLastUnitGiveExactlyOneReservation() throws Exception {
        jdbc.sql("UPDATE stock SET available = 1 WHERE sku = 'SKU-1'").update();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        Future<?> fa = pool.submit(() -> { await(go); inventoryService.reserve("m-a", order(a, "SKU-1", "1")); });
        Future<?> fb = pool.submit(() -> { await(go); inventoryService.reserve("m-b", order(b, "SKU-1", "1")); });
        go.countDown();
        fa.get();
        fb.get();
        pool.shutdown();

        List<String> all = new java.util.ArrayList<>(results(a));
        all.addAll(results(b));
        assertThat(all).containsExactlyInAnyOrder("RESERVED", "REJECTED");
        assertThat(available("SKU-1")).isZero();
        assertThat(reserved("SKU-1")).isEqualTo(1);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
