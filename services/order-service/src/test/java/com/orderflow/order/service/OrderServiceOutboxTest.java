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
// Should have no Kafka container here, and application.yml points at localhost:9092 (a real broker if compose is up),
// so switch the OutboxPublisher off: nothing may publish these rows or mark them published.
//Ground truth of orderflow.outbox.* is in OutboxProperties;See notes\outbox\what_is_orderflow_outbox_property_and_its_uses
@SpringBootTest(properties = "orderflow.outbox.enabled=false")
@Testcontainers
class OrderServiceOutboxTest {

    // @ServiceConnection points spring.datasource.* (which is localhost:5433 in Swagger and production) 
    // at this container, overriding application.yml
    //These 3 lines override data source during testing to use a random postgres container and run 
    // all migrations and listen on rnadom port instead of the production one.
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired OrderService orderService;
    @Autowired OrderRepository orderRepository;
    @Autowired OutboxRepository outboxRepository;
    @Autowired PlatformTransactionManager txManager;

    private final CreateOrderRequest request = new CreateOrderRequest(
            "CUST-1", List.of(new CreateOrderRequest.Item("SKU-1", 2)));

    
    //So tests don't see each other's rows as datasource overriden in "postgres" lines on top.
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
        //Here, orderService.create doesn't start its own Transaction but only joins the one
        //started by TransactionTemplate. At step 12e when inserting an OutboxEvent, 
        // this test will make sure that both will not insert if neither inserts when TransactionTemplate
        // fails.
        assertThatThrownBy(() -> new TransactionTemplate(txManager).execute(status -> {
            // create() joins this outer transaction, so throwing afterwards rolls back everything it wrote
            orderService.create(request);
            throw new RuntimeException("force rollback");
        })).hasMessage("force rollback");

        //In order to prove order and outbox insertions share the same transaction
        // insert order and then throw an error; if it was implemented wrongly
        // then order will still have 1 row without accompanying outbox row.
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
        //All tests below this will fail as OrderService.create() still needs to use 
        //OutboxRepository to create an OutboxEvent first with the same params as the created Order
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getType()).isEqualTo("OrderCreated");

        //Aggregate ID (which is used to group events of same Topics together) of OutboxEvent record must be equal to ID of Order
        assertThat(events.get(0).getAggregateId()).isEqualTo(created.id().toString());
        assertThat(events.get(0).getPublishedAt()).isNull();
    }
}
