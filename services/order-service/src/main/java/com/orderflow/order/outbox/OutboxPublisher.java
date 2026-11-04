package com.orderflow.order.outbox;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * PLAN step 5: poll unpublished rows, send to Kafka (key = aggregateId), wait for the ack,
 * then mark published. Delivery is at-least-once, so consumers must be idempotent.
 */
@Component
// On by default; tests without a Kafka container set orderflow.outbox.enabled=false so no bean is created
//Ground truth of orderflow.outbox.* is in OutboxPublisher; See notes\outbox\what_is_orderflow_outbox_property_and_its_uses
@ConditionalOnProperty(name = "orderflow.outbox.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxPublisher {
    private final OutboxRepository repo;
    private final KafkaTemplate<String, String> kafka;

    public OutboxPublisher(OutboxRepository repo, KafkaTemplate<String, String> kafka) {
        this.repo = repo;
        this.kafka = kafka;
    }

    @Scheduled(fixedDelayString = "${orderflow.outbox.poll-ms:500}")
    @Transactional
    public void publishPending() {
        // TODO: for each event -> kafka.send("orders.events", aggregateId, payload).get(timeout)
        //       then event.markPublished(). Add a test that kills Kafka mid-publish.
    }
}
