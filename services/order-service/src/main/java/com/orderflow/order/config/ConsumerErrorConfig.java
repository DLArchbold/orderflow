package com.orderflow.order.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;

/**
 * PLAN step 16b: blocking retry with exponential backoff, then the record goes to <topic>.DLT (same partition,
 * original key, value and headers plus the exception) so the partition is not blocked (ADR 0004).
 * Spring Boot registers a CommonErrorHandler bean on the default listener container factory.
 */
@Configuration
public class ConsumerErrorConfig {
    private static final Logger log = LoggerFactory.getLogger(ConsumerErrorConfig.class);

    @Bean
    DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> template, MeterRegistry meters,
            @Value("${orderflow.consumer.backoff-initial-ms:1000}") long initialMs,
            @Value("${orderflow.consumer.backoff-max-ms:10000}") long maxMs,
            @Value("${orderflow.consumer.max-attempts:3}") int maxAttempts) {
        DeadLetterPublishingRecoverer dlt = new DeadLetterPublishingRecoverer(template);
        Counter dltMessages = Counter.builder("orderflow.dlt.messages")
                .description("Records sent to a dead-letter topic").register(meters);
        ConsumerRecordRecoverer counting = (record, ex) -> {
            log.error("Dead-lettering record {}-{}@{} (key {}): {}", record.topic(), record.partition(),
                    record.offset(), record.key(), ex.toString());
            dlt.accept(record, ex);
            dltMessages.increment();
        };

        // maxAttempts counts deliveries, so there are maxAttempts - 1 retries
        DefaultErrorHandler handler = new DefaultErrorHandler(counting, backOffWithMaxRetries(initialMs, maxMs, maxAttempts - 1));
        // unreadable or incomplete payloads never succeed on retry (a missing message-id header is already
        // not retryable by default)
        handler.addNotRetryableExceptions(com.fasterxml.jackson.core.JsonProcessingException.class,
                IllegalArgumentException.class);
        return handler;
    }

    private static org.springframework.kafka.support.ExponentialBackOffWithMaxRetries backOffWithMaxRetries(
            long initialMs, long maxMs, int retries) {
        var backOff = new org.springframework.kafka.support.ExponentialBackOffWithMaxRetries(retries);
        backOff.setInitialInterval(initialMs);
        backOff.setMultiplier(2.0);
        backOff.setMaxInterval(maxMs);
        return backOff;
    }
}
