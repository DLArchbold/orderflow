package com.orderflow.order.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Title/description shown at the top of Swagger UI (http://localhost:8080/swagger-ui.html). */
@Configuration
public class SwaggerConfig {
    @Bean
    OpenAPI orderflowApi() {
        return new OpenAPI().info(new Info()
            .title("OrderFlow Order API")
            .version("0.1.0")
            .description("Create, view, confirm and cancel orders. "
                + "An order starts as PENDING; PENDING can become CONFIRMED, REJECTED or CANCELLED, "
                + "CONFIRMED can become CANCELLED, and REJECTED/CANCELLED are final. "
                + "Errors are returned as {\"message\": \"...\"} with status 400, 404 or 409."));
    }
}
