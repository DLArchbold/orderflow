package com.orderflow.order.web;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.orderflow.order.outbox.OutboxRepository;
import com.orderflow.order.persistence.OrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * PLAN step 18c: the owner comes from the verified token (sub), never from the body. A customer sees and cancels
 * only their own orders; an admin sees everything.
 */
@SpringBootTest(properties = {"orderflow.outbox.enabled=false", "spring.kafka.listener.auto-startup=false"})
@AutoConfigureMockMvc
@Testcontainers
class OrderOwnershipTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired MockMvc mvc;
    @Autowired OrderRepository orderRepository;
    @Autowired OutboxRepository outboxRepository;

    static RequestPostProcessor customer(String subject) {
        return jwt().jwt(j -> j.subject(subject)).authorities(new SimpleGrantedAuthority("ROLE_customer"));
    }

    static RequestPostProcessor admin() {
        return jwt().jwt(j -> j.subject("ada")).authorities(new SimpleGrantedAuthority("ROLE_admin"));
    }

    @BeforeEach
    void cleanTables() {
        outboxRepository.deleteAll();
        orderRepository.deleteAll();
    }

    private String createAs(RequestPostProcessor user, String body) throws Exception {
        String response = mvc.perform(post("/api/orders").with(user).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(response, "$.id");
    }

    private static final String ITEMS = "\"items\":[{\"sku\":\"SKU-1\",\"quantity\":1}]";

    @Test
    void theOwnerIsTheTokenSubjectEvenIfTheBodyNamesSomeoneElse() throws Exception {
        mvc.perform(post("/api/orders").with(customer("alice")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":\"mallory\"," + ITEMS + "}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.customerId").value("alice"));
    }

    @Test
    void aCustomerListsOnlyTheirOwnOrders() throws Exception {
        createAs(customer("alice"), "{" + ITEMS + "}");
        createAs(customer("alice"), "{" + ITEMS + "}");
        createAs(customer("bob"), "{" + ITEMS + "}");

        mvc.perform(get("/api/orders").with(customer("alice")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].customerId").value("alice"))
                .andExpect(jsonPath("$[1].customerId").value("alice"));
    }

    @Test
    void anotherCustomerGets403OnGetAndCancel() throws Exception {
        String id = createAs(customer("alice"), "{" + ITEMS + "}");

        mvc.perform(get("/api/orders/" + id).with(customer("bob"))).andExpect(status().isForbidden());
        mvc.perform(post("/api/orders/" + id + "/cancel").with(customer("bob"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/orders/" + id).with(customer("alice"))).andExpect(status().isOk());
    }

    @Test
    void anAdminSeesAndReadsEverything() throws Exception {
        String id = createAs(customer("alice"), "{" + ITEMS + "}");
        createAs(customer("bob"), "{" + ITEMS + "}");

        mvc.perform(get("/api/orders").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
        mvc.perform(get("/api/orders/" + id).with(admin())).andExpect(status().isOk());
    }

    @Test
    void theListIsPagedNewestFirst() throws Exception {
        String first = createAs(customer("alice"), "{" + ITEMS + "}");
        createAs(customer("alice"), "{" + ITEMS + "}");
        String newest = createAs(customer("alice"), "{" + ITEMS + "}");

        mvc.perform(get("/api/orders?size=2").with(customer("alice")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(newest));
        mvc.perform(get("/api/orders?page=1&size=2").with(customer("alice")))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(first));
        mvc.perform(get("/api/orders?size=0").with(customer("alice"))).andExpect(status().isBadRequest());
    }
}
