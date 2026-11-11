package com.orderflow.inventory.outbox;

import com.orderflow.inventory.config.KafkaConfig;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Copy of order-service's OutboxPublisher (ADR 0002): poll unpublished rows, send to inventory.results
 * (key = aggregateId, headers message-id = row id and correlation-id), wait for the ack, then mark published.
 * Delivery is at-least-once, so consumers must be idempotent.
 */
@Component
@ConditionalOnProperty(name = "orderflow.outbox.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxPublisher {
    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxRepository repo;
    private final KafkaTemplate<String, String> kafka;
    private final OutboxTrace trace;

    public OutboxPublisher(OutboxRepository repo, KafkaTemplate<String, String> kafka, OutboxTrace trace) {
        this.repo = repo;
        this.kafka = kafka;
        this.trace = trace;
    }

    @Scheduled(fixedDelayString = "${orderflow.outbox.poll-ms:500}")
    @Transactional
    public void publishPending() {
        for (OutboxEvent event : repo.findTop100ByPublishedAtIsNullOrderByCreatedAt()) {
            Span span = trace.startChild(event.getTraceContext(), "outbox publish");
            if (event.getCorrelationId() != null) {
                MDC.put("correlationId", event.getCorrelationId());
            }
            try (Tracer.SpanInScope ignored = trace.inScope(span)) {
                ProducerRecord<String, String> record = new ProducerRecord<>(
                        KafkaConfig.INVENTORY_RESULTS_TOPIC, event.getAggregateId(), event.getPayload());
                record.headers().add("message-id", event.getId().toString().getBytes(StandardCharsets.UTF_8));
                if (event.getCorrelationId() != null) {
                    record.headers().add("correlation-id", event.getCorrelationId().getBytes(StandardCharsets.UTF_8));
                }
                kafka.send(record).get(5, TimeUnit.SECONDS);
                event.markPublished();
                log.info("Published outbox event {} ({}) for aggregate {} to {}",
                        event.getId(), event.getType(), event.getAggregateId(), KafkaConfig.INVENTORY_RESULTS_TOPIC);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                // Do not rethrow: that would roll back markPublished() of events already sent in this batch.
                log.warn("Failed to publish outbox event {}; will retry on next poll", event.getId(), e);
                break;
            } finally {
                span.end();
                MDC.remove("correlationId");
            }
        }
    }
}
