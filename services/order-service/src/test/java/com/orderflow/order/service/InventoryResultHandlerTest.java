package com.orderflow.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.orderflow.order.domain.OrderStatus;
import com.orderflow.order.outbox.OutboxRepository;
import com.orderflow.order.persistence.OrderRepository;
import com.orderflow.order.web.CreateOrderRequest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * PLAN step 15b: the handler applies "only if PENDING", so repeating a result gives the same final state and
 * order-service needs no processed_messages table of its own. Unknown or finished orders are ignored: throwing
 * would block the partition on a record that can never succeed.
 */
@SpringBootTest(properties = {"orderflow.outbox.enabled=false", "spring.kafka.listener.auto-startup=false"})
@Testcontainers
class InventoryResultHandlerTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired OrderService orderService;
    @Autowired InventoryResultHandler handler;
    @Autowired OrderRepository orderRepository;
    @Autowired OutboxRepository outboxRepository;

    private final CreateOrderRequest request = new CreateOrderRequest(
            List.of(new CreateOrderRequest.Item("SKU-1", 1)));

    private static final Caller ADMIN = new Caller("test", true);

    @BeforeEach
    void cleanTables() {
        outboxRepository.deleteAll();
        orderRepository.deleteAll();
    }

    private UUID newOrder() {
        return orderService.create("CUST-1", request).id();
    }

    private OrderStatus status(UUID id) {
        return OrderStatus.valueOf(String.valueOf(orderService.get(id, ADMIN).status()));
    }

    @Test
    void reservedResultConfirmsAPendingOrder() {
        UUID id = newOrder();
        handler.apply(id, "RESERVED");
        assertThat(status(id)).isEqualTo(OrderStatus.CONFIRMED);
    }

    @Test
    void rejectedResultRejectsAPendingOrder() {
        UUID id = newOrder();
        handler.apply(id, "REJECTED");
        assertThat(status(id)).isEqualTo(OrderStatus.REJECTED);
    }

    @Test
    void theSameResultAppliedTwiceLeavesTheSameState() {
        UUID id = newOrder();
        handler.apply(id, "RESERVED");
        assertThatCode(() -> handler.apply(id, "RESERVED")).doesNotThrowAnyException();
        assertThat(status(id)).isEqualTo(OrderStatus.CONFIRMED);
    }

    @Test
    void aResultForAnUnknownOrderIsIgnored() {
        assertThatCode(() -> handler.apply(UUID.randomUUID(), "RESERVED")).doesNotThrowAnyException();
    }

    @Test
    void aResultForACancelledOrderIsIgnored() {
        UUID id = newOrder();
        orderService.cancel(id, ADMIN);
        assertThatCode(() -> handler.apply(id, "RESERVED")).doesNotThrowAnyException();
        assertThat(status(id)).isEqualTo(OrderStatus.CANCELLED);
    }
}
