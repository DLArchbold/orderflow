package com.orderflow.order.outbox;

import com.orderflow.order.config.KafkaConfig;
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
 * PLAN step 5: poll unpublished rows, send to Kafka (key = aggregateId), wait for the ack,
 * then mark published. Delivery is at-least-once, so consumers must be idempotent.
 */
@Component
// On by default; tests without a Kafka container set orderflow.outbox.enabled=false so no bean is created
//Ground truth of orderflow.outbox.* is in OutboxPublisher; See notes\outbox\what_is_orderflow_outbox_property_and_its_uses
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
            // continue the trace of the request that wrote the row, so the Kafka send is a child of its span
            Span span = trace.startChild(event.getTraceContext(), "outbox publish");
            if (event.getCorrelationId() != null) {
                MDC.put("correlationId", event.getCorrelationId());
            }
            try (Tracer.SpanInScope ignored = trace.inScope(span)) {
                // message-id = outbox row id: stable across re-sends, so consumers can dedupe on it
                ProducerRecord<String, String> record = new ProducerRecord<>(
                        KafkaConfig.ORDERS_EVENTS_TOPIC, event.getAggregateId(), event.getPayload());
                record.headers().add("message-id", event.getId().toString().getBytes(StandardCharsets.UTF_8));
                if (event.getCorrelationId() != null) {
                    record.headers().add("correlation-id", event.getCorrelationId().getBytes(StandardCharsets.UTF_8));
                }
                kafka.send(record).get(5, TimeUnit.SECONDS);
                // Only after the broker acked; saved on commit through dirty checking.
                event.markPublished();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                // Do not rethrow: that would roll back markPublished() of events already sent in this batch.
                // Stop at the first failure so later events do not overtake the failed one.
                log.warn("Failed to publish outbox event {}; will retry on next poll", event.getId(), e);
                break;
            } finally {
                span.end();
                MDC.remove("correlationId");
            }
        }
    }
}
