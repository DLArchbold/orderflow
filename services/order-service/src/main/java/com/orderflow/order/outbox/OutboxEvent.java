package com.orderflow.order.outbox;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/** Row written in the SAME transaction as the business change (order creation); 
 * published to Kafka by OutboxPublisher to enforce the outbox design pattern */
//Outbox Design Pattern
//In order for the Outbox pattern to work, an order and outbox row must be written 
//within the same transaction just in case the Kafka event does not reach the broker;
//in that case even if Kafka broker is down, OutBoxPublisher will still publish it to 
// the the Kafka broker and the latter will still poll the DB for orders that are still 
// in the PENDING status.
@Entity
@Table(name = "outbox")
public class OutboxEvent {
    @Id
    private UUID id = UUID.randomUUID();
    //Key that will be used by Kafka to group events by Topic/Partition
    @Column(nullable = false) private String aggregateId;
    @Column(nullable = false) private String type;
    @Column(nullable = false, columnDefinition = "text") private String payload;
    @Column(nullable = false) private Instant createdAt = Instant.now();
    private Instant publishedAt;

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
    public Instant getPublishedAt() { return publishedAt; }
    public void markPublished() { this.publishedAt = Instant.now(); }
}
