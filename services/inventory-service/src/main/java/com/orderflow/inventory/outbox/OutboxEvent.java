package com.orderflow.inventory.outbox;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/** Row written in the SAME transaction as the stock change; published to Kafka by OutboxPublisher. */
@Entity
@Table(name = "outbox")
public class OutboxEvent {
    @Id
    private UUID id = UUID.randomUUID();
    @Column(nullable = false) private String aggregateId;
    @Column(nullable = false) private String type;
    @Column(nullable = false, columnDefinition = "text") private String payload;
    @Column(nullable = false) private Instant createdAt = Instant.now();
    private Instant publishedAt;
    private String traceContext;     // W3C traceparent of the consumed record's span
    private String correlationId;

    protected OutboxEvent() {}

    public OutboxEvent(String aggregateId, String type, String payload) {
        this.aggregateId = aggregateId;
        this.type = type;
        this.payload = payload;
    }

    public UUID getId() { return id; }
    public String getAggregateId() { return aggregateId; }
    public String getType() { return type; }
    public String getPayload() { return payload; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getPublishedAt() { return publishedAt; }
    public String getTraceContext() { return traceContext; }
    public String getCorrelationId() { return correlationId; }
    public void attachContext(String traceContext, String correlationId) {
        this.traceContext = traceContext;
        this.correlationId = correlationId;
    }
    public void markPublished() { this.publishedAt = Instant.now(); }
}
