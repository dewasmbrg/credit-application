package com.creditrisk.service;

import com.creditrisk.config.KafkaTopics;
import com.creditrisk.event.CreditApplicationSubmitted;
import com.creditrisk.model.CreditApplication;
import com.creditrisk.model.OutboxEvent;
import com.creditrisk.repository.CreditApplicationRepository;
import com.creditrisk.repository.OutboxEventRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Service for handling credit application submissions.
 *
 * TRANSACTIONAL OUTBOX PATTERN IMPLEMENTATION:
 * ============================================
 * This service now uses the Transactional Outbox pattern instead of direct Kafka publishing.
 *
 * OLD APPROACH (Dual-Write Problem):
 * ----------------------------------
 * 1. Save to database
 * 2. Publish to Kafka directly
 * Problem: If Kafka fails, we have data in DB but no event!
 *
 * NEW APPROACH (Outbox Pattern):
 * ------------------------------
 * 1. Save business data (CreditApplication) to database
 * 2. Save event to OUTBOX table in the SAME transaction
 * 3. Both commit atomically
 * 4. Background publisher reads outbox and publishes to Kafka
 *
 * BENEFITS:
 * ---------
 * - Atomic: Both saves happen in one transaction
 * - Reliable: Events guaranteed to be published eventually
 * - Kafka-independent: Application works even if Kafka is down
 * - No data loss: Events are persisted before publishing
 *
 * TRADE-OFFS:
 * -----------
 * - Slight delay: Events published asynchronously (eventual consistency)
 * - Extra table: Need to manage outbox events
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ApplicationService {

    private final CreditApplicationRepository applicationRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    /**
     * Submit a new credit application.
     *
     * Flow (Transactional Outbox Pattern):
     * 1. Create and save application entity to database
     * 2. Create immutable event
     * 3. Save event to OUTBOX table (same transaction as #1)
     * 4. Return application ID to caller
     * 5. Background publisher reads outbox and publishes to Kafka
     *
     * Note: This method returns QUICKLY. Event publishing happens asynchronously.
     */
    @Transactional
    public String submitApplication(String customerId,
                                    BigDecimal requestedAmount,
                                    Integer creditScore,
                                    BigDecimal annualIncome) {

        // Generate unique ID for this application
        String applicationId = UUID.randomUUID().toString();

        log.info("Submitting credit application: {} for customer: {}", applicationId, customerId);

        // 1. Save business data to database
        CreditApplication application = new CreditApplication();
        application.setApplicationId(applicationId);
        application.setCustomerId(customerId);
        application.setRequestedAmount(requestedAmount);
        application.setCreditScore(creditScore);
        application.setAnnualIncome(annualIncome);
        application.setStatus(CreditApplication.ApplicationStatus.SUBMITTED);
        application.setSubmittedAt(Instant.now());

        applicationRepository.save(application);

        log.info("Saved application to database: {}", applicationId);

        // 2. Create immutable event
        CreditApplicationSubmitted event = new CreditApplicationSubmitted(
                applicationId,
                customerId,
                requestedAmount,
                creditScore,
                annualIncome,
                Instant.now()
        );

        // 3. Save event to OUTBOX table (SAME transaction as application save!)
        try {
            saveToOutbox(event, applicationId, "CreditApplicationSubmitted", KafkaTopics.CREDIT_APPLICATION_SUBMITTED);
            log.info("Saved CreditApplicationSubmitted event to outbox: {}", applicationId);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize event for outbox: {}", applicationId, e);
            throw new RuntimeException("Failed to save event to outbox", e);
        }

        return applicationId;
    }

    /**
     * Save event to outbox table.
     * This is a helper method that serializes the event and saves it to the outbox.
     */
    private void saveToOutbox(Object event, String eventId, String eventType, String topic)
            throws JsonProcessingException {

        String payload = objectMapper.writeValueAsString(event);

        OutboxEvent outboxEvent = new OutboxEvent();
        outboxEvent.setEventId(eventId);
        outboxEvent.setEventType(eventType);
        outboxEvent.setPayload(payload);
        outboxEvent.setTopic(topic);

        outboxEventRepository.save(outboxEvent);
    }

    /**
     * Get application by ID.
     *
     * CACHING STRATEGY:
     * =================
     * @Cacheable: Spring checks cache first
     * - Cache HIT: Returns cached value (no DB query)
     * - Cache MISS: Calls method, stores result in cache
     *
     * Key: applicationId (simple, unique identifier)
     * TTL: 30 minutes (configured in RedisConfig)
     *
     * CACHE EVICTION:
     * ===============
     * Cache is evicted when:
     * 1. Application status changes (see evictApplicationCache method)
     * 2. TTL expires (30 minutes)
     *
     * PERFORMANCE IMPACT:
     * ===================
     * Before: Every GET hits database
     * After: First GET hits database, subsequent GETs hit Redis (sub-millisecond)
     */
    @Cacheable(value = "applications", key = "#applicationId")
    public CreditApplication getApplication(String applicationId) {
        log.debug("Cache miss - fetching application from database: {}", applicationId);
        return applicationRepository.findByApplicationId(applicationId)
                .orElseThrow(() -> new IllegalArgumentException("Application not found: " + applicationId));
    }

    /**
     * Evict application from cache.
     * Call this whenever application status changes.
     *
     * This ensures cache consistency - when application data changes in the database,
     * we remove the cached copy so the next GET will fetch fresh data.
     */
    @CacheEvict(value = "applications", key = "#applicationId")
    public void evictApplicationCache(String applicationId) {
        log.debug("Evicted application from cache: {}", applicationId);
    }
}
