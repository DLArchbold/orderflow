package com.orderflow.order.stock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** PLAN step 17a: the client reads inventory-service's JSON into a StockInfo. */
class StockClientTest {

    @Test
    void readsStockFromInventoryService() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://inventory.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://inventory.test/stock/SKU-1"))
                .andRespond(withSuccess("{\"sku\":\"SKU-1\",\"available\":98,\"reserved\":2}", MediaType.APPLICATION_JSON));

        StockInfo info = new StockClient(builder.build()).get("SKU-1");

        assertThat(info).isEqualTo(new StockInfo("SKU-1", 98, 2));
        server.verify();
    }
}
