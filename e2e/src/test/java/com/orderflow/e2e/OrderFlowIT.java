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

    final HttpClient http = HttpClient.newHttpClient();
    final ObjectMapper json = new ObjectMapper();

    JsonNode send(HttpRequest request) throws Exception {
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
