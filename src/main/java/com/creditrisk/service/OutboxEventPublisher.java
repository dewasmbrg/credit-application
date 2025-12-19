package com.creditrisk.service;

import com.creditrisk.event.CreditApplicationSubmitted;
import com.creditrisk.event.RiskAssessmentCompleted;
import com.creditrisk.model.OutboxEvent;
import com.creditrisk.producer.EventProducer;
import com.creditrisk.repository.OutboxEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * OUTBOX EVENT PUBLISHER
 * ======================
 *
 * This service is the "heart" of the Transactional Outbox pattern.
 *
 * HOW IT WORKS:
 * -------------
 * 1. Runs every 100ms (configurable via @Scheduled)
 * 2. Queries database for unpublished events
 * 3. For each event:
 *    a) Deserialize JSON payload
 *    b) Publish to Kafka using EventProducer
 *    c) Mark as published in database
 * 4. If publishing fails, increment retry count and continue
 *
 * RELIABILITY:
 * ------------
 * - Events are NEVER lost (they're in database)
 * - Failed events are automatically retried on next poll
 * - Kafka being down doesn't affect business operations
 * - Events are published in order (FIFO)
 *
 * PERFORMANCE:
 * ------------
 * - Batch size limit prevents overwhelming Kafka
 * - Separate thread from business operations
 * - Can scale horizontally with distributed locking (not implemented)
 *
 * MONITORING:
 * -----------
 * - Log warnings for events with high retry counts
 * - Log errors for stuck events (old unpublished events)
 * - Track outbox queue size
 *
 * PRODUCTION CONSIDERATIONS:
 * --------------------------
 * 1. Add distributed locking if running multiple instances
 *    (use ShedLock or Redis locks to prevent duplicate publishing)
 * 2. Add dead letter queue for events that fail too many times
 * 3. Add metrics/monitoring (Prometheus, Grafana)
 * 4. Consider using CDC (Change Data Capture) like Debezium instead
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OutboxEventPublisher {

    private final OutboxEventRepository outboxEventRepository;
    private final EventProducer eventProducer;
    private final ObjectMapper objectMapper;

    private static final int BATCH_SIZE = 100; // Process max 100 events per run
    private static final int MAX_RETRY_COUNT = 10; // Warn after 10 failed attempts

    /**
     * Scheduled task that publishes outbox events to Kafka.
     *
     * Runs every 100ms (10 times per second).
     * Adjust based on your throughput requirements:
     * - Higher frequency = lower latency, more database load
     * - Lower frequency = higher latency, less database load
     *
     * For production:
     * - Use fixedDelay for consistent intervals regardless of execution time
     * - Consider using ShedLock to prevent concurrent execution in multi-instance setups
     */
    @Scheduled(fixedDelay = 100) // Run every 100ms
    @Transactional
    public void publishEvents() {
        try {
            // Fetch unpublished events (limited to prevent overwhelming Kafka)
            List<OutboxEvent> events = outboxEventRepository.findUnpublishedEventsWithLimit(BATCH_SIZE);

            if (events.isEmpty()) {
                return; // No events to publish
            }

            log.debug("Publishing {} outbox events", events.size());

            for (OutboxEvent event : events) {
                try {
                    publishEvent(event);
                } catch (Exception e) {
                    handlePublishError(event, e);
                }
            }

            log.debug("Finished publishing batch of {} events", events.size());

        } catch (Exception e) {
            log.error("Error in outbox event publisher", e);
        }
    }

    /**
     * Publish a single event to Kafka.
     */
    private void publishEvent(OutboxEvent outboxEvent) throws Exception {
        log.debug("Publishing event: {} (type: {})", outboxEvent.getEventId(), outboxEvent.getEventType());

        // Deserialize payload based on event type
        Object event = deserializeEvent(outboxEvent);

        // Publish to Kafka
        switch (outboxEvent.getEventType()) {
            case "CreditApplicationSubmitted" ->
                eventProducer.publishCreditApplication((CreditApplicationSubmitted) event);
            case "RiskAssessmentCompleted" ->
                eventProducer.publishRiskAssessment((RiskAssessmentCompleted) event);
            default ->
                throw new IllegalArgumentException("Unknown event type: " + outboxEvent.getEventType());
        }

        // Mark as published
        outboxEvent.setPublished(true);
        outboxEvent.setPublishedAt(Instant.now());
        outboxEventRepository.save(outboxEvent);

        log.info("Successfully published event: {} (type: {})",
                 outboxEvent.getEventId(), outboxEvent.getEventType());
    }

    /**
     * Deserialize JSON payload to event object.
     */
    private Object deserializeEvent(OutboxEvent outboxEvent) throws Exception {
        return switch (outboxEvent.getEventType()) {
            case "CreditApplicationSubmitted" ->
                objectMapper.readValue(outboxEvent.getPayload(), CreditApplicationSubmitted.class);
            case "RiskAssessmentCompleted" ->
                objectMapper.readValue(outboxEvent.getPayload(), RiskAssessmentCompleted.class);
            default ->
                throw new IllegalArgumentException("Unknown event type: " + outboxEvent.getEventType());
        };
    }

    /**
     * Handle publishing errors.
     */
    private void handlePublishError(OutboxEvent event, Exception e) {
        event.setRetryCount(event.getRetryCount() + 1);
        event.setLastError(e.getMessage());
        outboxEventRepository.save(event);

        if (event.getRetryCount() >= MAX_RETRY_COUNT) {
            log.error("Event {} has failed {} times. Manual intervention may be required. Error: {}",
                      event.getEventId(), event.getRetryCount(), e.getMessage());
        } else {
            log.warn("Failed to publish event {} (attempt {}): {}",
                     event.getEventId(), event.getRetryCount(), e.getMessage());
        }
    }

    /**
     * Monitor for stuck events (events that haven't been published for too long).
     * Run this less frequently (e.g., every minute).
     */
    @Scheduled(fixedDelay = 60000) // Every minute
    public void monitorStuckEvents() {
        try {
            Instant threshold = Instant.now().minusSeconds(300); // 5 minutes ago
            List<OutboxEvent> stuckEvents = outboxEventRepository
                    .findByPublishedFalseAndCreatedAtBefore(threshold);

            if (!stuckEvents.isEmpty()) {
                log.error("Found {} stuck events older than 5 minutes. Manual intervention may be required.",
                          stuckEvents.size());
                stuckEvents.forEach(event ->
                    log.error("Stuck event: id={}, eventId={}, eventType={}, createdAt={}, retryCount={}, lastError={}",
                              event.getId(), event.getEventId(), event.getEventType(),
                              event.getCreatedAt(), event.getRetryCount(), event.getLastError())
                );
            }

            // Log queue size for monitoring
            long queueSize = outboxEventRepository.countByPublishedFalse();
            if (queueSize > 1000) {
                log.warn("Outbox queue size is {}, which is high. Consider scaling up.", queueSize);
            } else {
                log.debug("Outbox queue size: {}", queueSize);
            }

        } catch (Exception e) {
            log.error("Error monitoring stuck events", e);
        }
    }
}
