package com.orderflow.inventory.admin;

import com.orderflow.inventory.config.KafkaConfig;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * PLAN step 16c: re-sends the records of orders.events.DLT to orders.events with their original key, value and
 * headers (the kafka_dlt-* headers added by the recoverer are dropped). The consumer group's committed offset is
 * what marks a dead letter as already replayed, since Kafka cannot delete a record.
 * Enabled only with orderflow.admin.enabled=true: the endpoint has no login (inventory-service is internal).
 */
@Component
@ConditionalOnProperty(name = "orderflow.admin.enabled", havingValue = "true")
public class DeadLetterReplayer {
    private static final Logger log = LoggerFactory.getLogger(DeadLetterReplayer.class);
    static final String GROUP = "dlt-replayer";

    private final KafkaProperties kafkaProperties;
    private final KafkaTemplate<String, String> template;

    public DeadLetterReplayer(KafkaProperties kafkaProperties, KafkaTemplate<String, String> template) {
        this.kafkaProperties = kafkaProperties;
        this.template = template;
    }

    /** Returns the number of records replayed. */
    public synchronized int replay() throws Exception {
        Map<String, Object> config = kafkaProperties.buildConsumerProperties(null);
        Properties props = new Properties();
        props.putAll(config);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, GROUP);
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());

        int replayed = 0;
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of(KafkaConfig.ORDERS_EVENTS_DLT));
            // the first poll also joins the group, so it gets a longer timeout than the following ones
            Duration timeout = Duration.ofSeconds(5);
            while (true) {
                ConsumerRecords<String, String> records = consumer.poll(timeout);
                if (records.isEmpty()) {
                    break;
                }
                timeout = Duration.ofSeconds(1);
                for (ConsumerRecord<String, String> record : records) {
                    ProducerRecord<String, String> out = new ProducerRecord<>(
                            KafkaConfig.ORDERS_EVENTS_TOPIC, record.key(), record.value());
                    for (Header h : record.headers()) {
                        if (!h.key().startsWith("kafka_dlt-")) {
                            out.headers().add(h);
                        }
                    }
                    template.send(out).get(5, TimeUnit.SECONDS);   // wait for the ack before committing
                    replayed++;
                }
                consumer.commitSync();
            }
        }
        log.info("Replayed {} dead letters from {}", replayed, KafkaConfig.ORDERS_EVENTS_DLT);
        return replayed;
    }
}
