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
        return new OpenAPI()
            .components(new io.swagger.v3.oas.models.Components().addSecuritySchemes("bearer",
                new io.swagger.v3.oas.models.security.SecurityScheme()
                    .type(io.swagger.v3.oas.models.security.SecurityScheme.Type.HTTP)
                    .scheme("bearer").bearerFormat("JWT")))
            .addSecurityItem(new io.swagger.v3.oas.models.security.SecurityRequirement().addList("bearer"))
            .info(new Info()
            .title("OrderFlow Order API")
            .version("0.1.0")
            .description("Create, view and cancel orders (Authorize with a Keycloak access token). "
                + "An order starts as PENDING; PENDING can become CONFIRMED, REJECTED or CANCELLED, "
                + "CONFIRMED, REJECTED and CANCELLED are final; CONFIRMED/REJECTED come from the inventory result. "
                + "Errors are returned as {\"message\": \"...\"} with status 400, 404 or 409."));
    }
}
