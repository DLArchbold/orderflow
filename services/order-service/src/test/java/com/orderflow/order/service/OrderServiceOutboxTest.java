package com.orderflow.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.orderflow.order.outbox.OutboxEvent;
import com.orderflow.order.outbox.OutboxRepository;
import com.orderflow.order.persistence.OrderRepository;
import com.orderflow.order.web.CreateOrderRequest;
import com.orderflow.order.web.OrderResponse;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * PLAN step 12d: an order and its outbox row must be written in the same transaction.
 * Runs against a throwaway PostgreSQL container (Docker must be running); Flyway applies V1..V3 to it.
 */
@SpringBootTest
@Testcontainers
class OrderServiceOutboxTest {

    // @ServiceConnection points spring.datasource.* at this container, overriding application.yml
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired OrderService orderService;
    @Autowired OrderRepository orderRepository;
    @Autowired OutboxRepository outboxRepository;
    @Autowired PlatformTransactionManager txManager;

    private final CreateOrderRequest request = new CreateOrderRequest(
            "CUST-1", List.of(new CreateOrderRequest.Item("SKU-1", 2)));

    @BeforeEach
    void cleanTables() {
        outboxRepository.deleteAll();
        orderRepository.deleteAll();
    }

    //In order for the Outbox pattern to work, an order and outbox row must be written 
    //within the same transaction just in case the Kafka event does not reach the broker;
    //in that case even if Kafka broker is down, OutBoxPublisher will still publish it to 
    // the the Kafka broker and the latter will still poll the DB for orders that are still 
    // in the PENDING status.
    @Test
    void rollbackLeavesNeitherOrderNorOutboxRow() {
        // create() joins this outer transaction, so throwing afterwards rolls back everything it wrote
        assertThatThrownBy(() -> new TransactionTemplate(txManager).execute(status -> {
            orderService.create(request);
            throw new RuntimeException("force rollback");
        })).hasMessage("force rollback");

        assertThat(orderRepository.count()).isZero();
        assertThat(outboxRepository.count()).isZero();
    }

    @Test
    void commitWritesOrderAndOutboxRow() {
        OrderResponse created = orderService.create(request);

        assertThat(orderRepository.count()).isEqualTo(1);
        //OutboxEvent will be published to Kafka by OutboxPublisher
        List<OutboxEvent> events = outboxRepository.findAll();

        //For every insertion of OutboxEvent an OrderEntity must be inserted as well
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getType()).isEqualTo("OrderCreated");
        
        //Aggregate ID (which is used to group events of same Topics together) of OutboxEvent record must be equal to ID of Order
        assertThat(events.get(0).getAggregateId()).isEqualTo(created.id().toString());
        assertThat(events.get(0).getPublishedAt()).isNull();
    }
}
