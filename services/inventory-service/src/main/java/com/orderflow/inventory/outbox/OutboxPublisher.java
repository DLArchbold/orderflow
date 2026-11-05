package com.orderflow.inventory.outbox;

import com.orderflow.inventory.config.KafkaConfig;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Copy of order-service's OutboxPublisher (ADR 0002): poll unpublished rows, send to inventory.results
 * (key = aggregateId, header message-id = row id), wait for the ack, then mark published.
 * Delivery is at-least-once, so consumers must be idempotent.
 */
@Component
@ConditionalOnProperty(name = "orderflow.outbox.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxPublisher {
    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxRepository repo;
    private final KafkaTemplate<String, String> kafka;

    public OutboxPublisher(OutboxRepository repo, KafkaTemplate<String, String> kafka) {
        this.repo = repo;
        this.kafka = kafka;
    }

    @Scheduled(fixedDelayString = "${orderflow.outbox.poll-ms:500}")
    @Transactional
    public void publishPending() {
        for (OutboxEvent event : repo.findTop100ByPublishedAtIsNullOrderByCreatedAt()) {
            try {
                ProducerRecord<String, String> record = new ProducerRecord<>(
                        KafkaConfig.INVENTORY_RESULTS_TOPIC, event.getAggregateId(), event.getPayload());
                record.headers().add("message-id", event.getId().toString().getBytes(StandardCharsets.UTF_8));
                kafka.send(record).get(5, TimeUnit.SECONDS);
                event.markPublished();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                // Do not rethrow: that would roll back markPublished() of events already sent in this batch.
                log.warn("Failed to publish outbox event {}; will retry on next poll", event.getId(), e);
                break;
            }
        }
    }
}
