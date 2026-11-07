package com.orderflow.order.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * PLAN step 21a: orderflow.orders.created counts committed creates; orderflow.outbox.lag.seconds is the age of the
 * oldest unpublished outbox row (0 when there is none). The lag gauge is the health signal of the outbox pattern:
 * if the publisher stops, orders keep being accepted and only this number shows the problem.
 */
@SpringBootTest(properties = {"orderflow.outbox.enabled=false", "spring.kafka.listener.auto-startup=false"})
@AutoConfigureMockMvc
@Testcontainers
class MetricsTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired MockMvc mvc;
    @Autowired MeterRegistry meters;
    @Autowired JdbcClient jdbc;

    @BeforeEach
    void clean() {
        jdbc.sql("DELETE FROM outbox").update();
    }

    @Test
    void theOrdersCreatedCounterIncrementsAfterOneCreate() throws Exception {
        double before = meters.counter("orderflow.orders.created").count();

        mvc.perform(post("/api/orders").with(jwt().jwt(j -> j.subject("alice"))).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"sku\":\"SKU-1\",\"quantity\":1}]}"))
                .andExpect(status().isCreated());

        assertThat(meters.counter("orderflow.orders.created").count()).isEqualTo(before + 1);
    }

    @Test
    void theOutboxLagIsZeroWithNothingUnpublished() {
        assertThat(meters.get("orderflow.outbox.lag.seconds").gauge().value()).isZero();
    }

    @Test
    void theOutboxLagIsAboutTheAgeOfTheOldestUnpublishedRow() {
        jdbc.sql("INSERT INTO outbox (id, aggregate_id, type, payload, created_at) "
                        + "VALUES (?, 'x', 'OrderCreated', '{}', now() - interval '30 seconds')")
                .param(UUID.randomUUID()).update();

        assertThat(meters.get("orderflow.outbox.lag.seconds").gauge().value()).isBetween(29.0, 45.0);
    }
}
