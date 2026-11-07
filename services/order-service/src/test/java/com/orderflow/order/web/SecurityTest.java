package com.orderflow.order.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * PLAN step 18b: the API needs a token, the documentation and health endpoints do not. Keycloak does not need to run:
 * jwt() skips token decoding, and the decoder is only created when the first real token arrives.
 */
@SpringBootTest(properties = {"orderflow.outbox.enabled=false", "spring.kafka.listener.auto-startup=false"})
@AutoConfigureMockMvc
@Testcontainers
class SecurityTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired MockMvc mvc;

    @Test
    void listWithoutTokenIs401() throws Exception {
        mvc.perform(get("/api/orders")).andExpect(status().isUnauthorized());
    }

    @Test
    void listWithATokenIs200() throws Exception {
        mvc.perform(get("/api/orders").with(jwt())).andExpect(status().isOk());
    }

    @Test
    void documentationAndHealthNeedNoToken() throws Exception {
        mvc.perform(get("/v3/api-docs")).andExpect(status().isOk());
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
        // /swagger-ui.html redirects to the UI page; what matters is that it is not a 401
        int swagger = mvc.perform(get("/swagger-ui.html")).andReturn().getResponse().getStatus();
        assertThat(swagger).isIn(200, 302);
    }

    // PLAN step 18d: the browser's preflight carries no token and must be answered for the frontend origin only
    @Test
    void preflightFromTheFrontendOriginIsAllowed() throws Exception {
        mvc.perform(options("/api/orders")
                        .header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "authorization,content-type"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"));
    }

    @Test
    void preflightFromAnotherOriginIsRejected() throws Exception {
        mvc.perform(options("/api/orders")
                        .header("Origin", "http://evil.example")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isForbidden());
    }
}
