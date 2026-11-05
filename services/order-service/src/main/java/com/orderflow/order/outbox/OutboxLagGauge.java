package com.orderflow.order.outbox;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * PLAN step 21a: orderflow.outbox.lag.seconds = now - min(created_at) over unpublished rows, 0 when there are none.
 * It is the health signal of the outbox pattern: when the publisher stops, orders are still accepted and only this
 * number grows. The partial index idx_outbox_unpublished (V3) makes the query cheap.
 */
@Component
public class OutboxLagGauge {
    private final JdbcClient jdbc;

    public OutboxLagGauge(JdbcClient jdbc, MeterRegistry registry) {
        this.jdbc = jdbc;
        Gauge.builder("orderflow.outbox.lag.seconds", this, OutboxLagGauge::lagSeconds)
                .description("Age of the oldest unpublished outbox row")
                .baseUnit("seconds")
                .register(registry);
    }

    double lagSeconds() {
        try {
            Double lag = jdbc.sql("SELECT EXTRACT(EPOCH FROM (now() - min(created_at))) FROM outbox WHERE published_at IS NULL")
                    .query(Double.class).optional().orElse(null);
            return lag == null ? 0.0 : Math.max(0.0, lag);
        } catch (RuntimeException e) {
            return Double.NaN;   // the database is unreachable: report "no value" rather than a misleading 0
        }
    }
}
