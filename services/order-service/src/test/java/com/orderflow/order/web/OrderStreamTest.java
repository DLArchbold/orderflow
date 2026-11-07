package com.orderflow.order.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.orderflow.order.outbox.OutboxRepository;
import com.orderflow.order.persistence.OrderRepository;
import com.orderflow.order.service.InventoryResultHandler;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * PLAN step 20a: a user who opens GET /api/orders/stream receives {"orderId","status"} when an inventory result
 * is applied to one of their orders; another user receives nothing.
 */
@SpringBootTest(properties = {"orderflow.outbox.enabled=false", "spring.kafka.listener.auto-startup=false"})
@AutoConfigureMockMvc
@Testcontainers
class OrderStreamTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired MockMvc mvc;
    @Autowired InventoryResultHandler handler;
    @Autowired OrderRepository orderRepository;
    @Autowired OutboxRepository outboxRepository;

    static RequestPostProcessor customer(String subject) {
        return jwt().jwt(j -> j.subject(subject)).authorities(new SimpleGrantedAuthority("ROLE_customer"));
    }

    @BeforeEach
    void cleanTables() {
        outboxRepository.deleteAll();
        orderRepository.deleteAll();
    }

    @Test
    void theOwnerReceivesTheStatusChangeAndAnotherUserDoesNot() throws Exception {
        String body = mvc.perform(post("/api/orders").with(customer("alice")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"sku\":\"SKU-1\",\"quantity\":1}]}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String orderId = JsonPath.read(body, "$.id");

        MvcResult alice = mvc.perform(get("/api/orders/stream").with(customer("alice")))
                .andExpect(request().asyncStarted()).andReturn();
        MvcResult bob = mvc.perform(get("/api/orders/stream").with(customer("bob")))
                .andExpect(request().asyncStarted()).andReturn();

        handler.apply(UUID.fromString(orderId), "RESERVED");

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(alice.getResponse().getContentAsString())
                        .contains("data:{\"orderId\":\"" + orderId + "\",\"status\":\"CONFIRMED\"}"));
        assertThat(bob.getResponse().getContentAsString()).doesNotContain(orderId);
    }
}
