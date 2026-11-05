package com.orderflow.order.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

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
 * PLAN step 21c: a client can quote a correlation id in a support request. The filter reads X-Correlation-Id (or
 * generates one) and returns it in the response header; the id is also put in the log MDC and carried to Kafka.
 */
@SpringBootTest(properties = {"orderflow.outbox.enabled=false", "spring.kafka.listener.auto-startup=false"})
@AutoConfigureMockMvc
@Testcontainers
class CorrelationIdFilterTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired MockMvc mvc;

    private String correlationIdOf(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request).andReturn().getResponse().getHeader("X-Correlation-Id");
    }

    @Test
    void aGivenCorrelationIdIsReturned() throws Exception {
        assertThat(correlationIdOf(get("/actuator/health").header("X-Correlation-Id", "support-req-123")))
                .isEqualTo("support-req-123");
    }

    @Test
    void oneIsGeneratedWhenTheClientSendsNone() throws Exception {
        assertThat(correlationIdOf(get("/actuator/health"))).isNotBlank();
    }

    @Test
    void anUnsafeValueIsReplacedNotEchoed() throws Exception {
        String unsafe = "x y<script>" + "a".repeat(200);
        assertThat(correlationIdOf(get("/actuator/health").header("X-Correlation-Id", unsafe)))
                .isNotBlank().isNotEqualTo(unsafe).doesNotContain("<");
    }
}
