package com.creditrisk.config;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.*;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.support.serializer.JsonSerializer;

import java.util.HashMap;
import java.util.Map;

/**
 * Kafka configuration for producers and consumers.
 *
 * Key concepts explained:
 * ======================
 *
 * SERIALIZATION:
 * - When sending events to Kafka, we convert Java objects to bytes (serialize)
 * - When reading events from Kafka, we convert bytes back to Java objects (deserialize)
 * - We use JSON for human-readable messages (easier debugging)
 *
 * PRODUCER CONFIG:
 * - Key: String (topic name)
 * - Value: Our event objects (serialized as JSON)
 *
 * CONSUMER CONFIG:
 * - Group ID: Multiple consumers with same group ID share the workload
 * - Auto-offset-reset: What to do if there's no previous offset (earliest = from beginning)
 * - Enable auto-commit: Automatically mark messages as processed
 */
@Configuration
@EnableKafka
public class KafkaConfig {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    // ==================== PRODUCER CONFIGURATION ====================

    /**
     * Producer configuration for sending events to Kafka.
     */
    @Bean
    public ProducerFactory<String, Object> producerFactory() {
        Map<String, Object> config = new HashMap<>();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);

        // Add type information to JSON (needed for deserialization)
        config.put(JsonSerializer.ADD_TYPE_INFO_HEADERS, true);

        return new DefaultKafkaProducerFactory<>(config);
    }

    /**
     * KafkaTemplate is the main class for sending messages.
     * Think of it as a "sender" that you can inject anywhere.
     */
    @Bean
    public KafkaTemplate<String, Object> kafkaTemplate() {
        return new KafkaTemplate<>(producerFactory());
    }

    // ==================== CONSUMER CONFIGURATION ====================

    /**
     * Consumer configuration for reading events from Kafka.
     */
    @Bean
    public ConsumerFactory<String, Object> consumerFactory() {
        Map<String, Object> config = new HashMap<>();
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        config.put(ConsumerConfig.GROUP_ID_CONFIG, "credit-risk-group");
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class);

        // Trust all packages for deserialization (in production, specify exact packages)
        config.put(JsonDeserializer.TRUSTED_PACKAGES, "*");

        // If consumer starts and has no previous offset, start from the beginning
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

        return new DefaultKafkaConsumerFactory<>(config);
    }

    /**
     * Container factory manages the consumer threads.
     *
     * CONCURRENCY:
     * - setConcurrency(3) means 3 threads will process messages in parallel
     * - More threads = faster processing BUT more CPU usage
     * - In production, tune this based on your workload
     */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, Object> kafkaListenerContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, Object> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory());

        // Process messages in 3 parallel threads
        factory.setConcurrency(3);

        return factory;
    }
}
