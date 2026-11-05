package com.orderflow.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * PLAN step 15d: the only test that runs both services with real Kafka and PostgreSQL together. It drives the
 * running stack over HTTP and never runs during a normal mvn test.
 */
class OrderFlowIT {
    static final String ORDER_URL = System.getenv().getOrDefault("ORDER_URL", "http://localhost:8080");
    static final String INVENTORY_URL = System.getenv().getOrDefault("INVENTORY_URL", "http://localhost:8081");
    static final String KEYCLOAK_URL = System.getenv().getOrDefault("KEYCLOAK_URL", "http://localhost:8180");

    final HttpClient http = HttpClient.newHttpClient();
    final ObjectMapper json = new ObjectMapper();

    /** Access token of a dev user from the realm import (orderflow-cli client, password grant: dev only). */
    String token(String user) throws Exception {
        String form = "client_id=orderflow-cli&grant_type=password&username=" + user + "&password=" + user + "-dev-pw";
        HttpResponse<String> res = http.send(HttpRequest.newBuilder(
                        URI.create(KEYCLOAK_URL + "/realms/orderflow/protocol/openid-connect/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form)).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(res.statusCode()).as("token for " + user + ": " + res.body()).isEqualTo(200);
        return json.readTree(res.body()).get("access_token").asText();
    }

    String bearer = null;

    JsonNode send(HttpRequest request) throws Exception {
        if (bearer == null) {
            bearer = "Bearer " + token("alice");
        }
        request = HttpRequest.newBuilder(request.uri()).method(request.method(),
                        request.bodyPublisher().orElse(HttpRequest.BodyPublishers.noBody()))
                .header("Authorization", bearer)
                .headers(request.headers().firstValue("Content-Type").map(v -> new String[] {"Content-Type", v})
                        .orElse(new String[] {"Accept", "application/json"}))
                .build();
        HttpResponse<String> res = http.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(res.statusCode()).as(request.uri() + " -> " + res.body()).isBetween(200, 299);
        return json.readTree(res.body());
    }

    String createOrder(String sku, long quantity) throws Exception {
        String body = "{\"customerId\":\"e2e\",\"items\":[{\"sku\":\"" + sku + "\",\"quantity\":" + quantity + "}]}";
        JsonNode created = send(HttpRequest.newBuilder(URI.create(ORDER_URL + "/api/orders"))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build());
        assertThat(created.get("status").asText()).isEqualTo("PENDING");
        return created.get("id").asText();
    }

    String statusOf(String id) {
        try {
            return send(HttpRequest.newBuilder(URI.create(ORDER_URL + "/api/orders/" + id)).GET().build())
                    .get("status").asText();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void anOrderThatFitsInStockBecomesConfirmed() throws Exception {
        String id = createOrder("SKU-1", 1);
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> assertThat(statusOf(id)).isEqualTo("CONFIRMED"));
    }

    @Test
    void anOrderLargerThanTheStockBecomesRejected() throws Exception {
        String id = createOrder("SKU-1", 2_000_000);   // more than the seeded 1,000,000
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> assertThat(statusOf(id)).isEqualTo("REJECTED"));
    }
}
