package com.orderflow.order.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class InventoryClientConfig {

    /** Timeouts are the first resilience measure: a call without one can hold a request thread as long as inventory stays silent. */
    @Bean
    RestClient inventoryRestClient(RestClient.Builder builder,
            @Value("${orderflow.inventory.base-url:http://localhost:8081}") String baseUrl) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(500);
        factory.setReadTimeout(1000);
        return builder.baseUrl(baseUrl).requestFactory(factory).build();
    }
}
