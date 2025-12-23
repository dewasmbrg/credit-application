package com.creditrisk.consumer;

import com.creditrisk.config.KafkaTopics;
import com.creditrisk.event.CreditApplicationSubmitted;
import com.creditrisk.event.RiskAssessmentCompleted;
import com.creditrisk.model.*;
import com.creditrisk.repository.CreditApplicationRepository;
import com.creditrisk.repository.OutboxEventRepository;
import com.creditrisk.repository.RiskAssessmentRepository;
import com.creditrisk.service.ApplicationService;
import com.creditrisk.service.IdempotencyService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Consumer that processes credit applications and performs risk assessment.
 *
 * ASYNC PROCESSING EXPLAINED:
 * ============================
 * This class runs on SEPARATE THREADS from the REST API.
 *
 * Flow (with Transactional Outbox):
 * 1. User calls POST /api/applications (REST thread)
 * 2. ApplicationService saves to database + outbox (same transaction)
 * 3. REST API returns immediately (user gets response)
 * 4. Background publisher reads outbox and publishes to Kafka
 * 5. THIS CONSUMER picks up the event (different thread!)
 * 6. Risk assessment happens in background
 * 7. Result is saved to database + outbox (atomic!)
 * 8. Background publisher publishes result event
 *
 * User experience:
 * - Submits application -> Gets confirmation in <100ms
 * - Risk assessment happens in background
 * - User can poll or wait for notification
 *
 * IDEMPOTENCY PATTERN:
 * ====================
 * The processApplication() method demonstrates a critical pattern:
 *
 * 1. Check if we've processed this event before
 * 2. If yes, SKIP processing (return early)
 * 3. If no, record that we're processing it
 * 4. Do the actual work
 *
 * This ensures that even if Kafka delivers the same event twice,
 * we only process it once.
 *
 * TRANSACTIONAL OUTBOX PATTERN:
 * ==============================
 * Instead of publishing directly to Kafka, this consumer:
 * 1. Saves assessment data to database
 * 2. Saves event to OUTBOX table (same transaction!)
 * 3. Background publisher handles Kafka publishing
 *
 * This guarantees that if data is saved, the event WILL be published.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RiskAssessmentConsumer {

    private final IdempotencyService idempotencyService;
    private final CreditApplicationRepository applicationRepository;
    private final RiskAssessmentRepository riskAssessmentRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final ApplicationService applicationService;
    private final ObjectMapper objectMapper;

    /**
     * Kafka listener method.
     *
     * @KafkaListener annotation makes this method a consumer.
     *
     * How it works:
     * - Spring automatically calls this method when a new message arrives
     * - The method runs on a background thread (NOT the REST API thread)
     * - Multiple instances can run in parallel (see concurrency in KafkaConfig)
     *
     * Parameters:
     * - topics: Which topic to listen to
     * - groupId: Consumer group (multiple consumers with same group share workload)
     * - containerFactory: Configuration for how to consume messages
     */
    @KafkaListener(
            topics = KafkaTopics.CREDIT_APPLICATION_SUBMITTED,
            groupId = "risk-assessment-group",
            containerFactory = "kafkaListenerContainerFactory"
    )
    @Transactional
    public void processApplication(CreditApplicationSubmitted event) {
        log.info("Received CreditApplicationSubmitted event: {}", event.applicationId());

        // ==================== REDIS-BASED IDEMPOTENCY CHECK ====================
        // CRITICAL: Check if we've already processed this event
        // Using Redis for 50-100x faster idempotency checks compared to database
        if (!idempotencyService.tryAcquire("CreditApplicationSubmitted",
                event.applicationId(), "RiskAssessmentConsumer")) {
            log.warn("Event already processed, skipping: {}", event.applicationId());
            return; // Exit early - don't process again!
        }

        log.info("Starting risk assessment for application: {}", event.applicationId());

        // ==================== SIMULATE ASYNC WORK ====================
        // In real systems, this might:
        // - Call external credit bureau APIs
        // - Run ML models
        // - Query fraud detection services
        // - Take several seconds
        try {
            Thread.sleep(2000); // Simulate 2 seconds of processing
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // ==================== PERFORM RISK ASSESSMENT ====================
        RiskLevel riskLevel = calculateRiskLevel(event.creditScore(), event.annualIncome(), event.requestedAmount());
        BigDecimal riskScore = calculateRiskScore(event.creditScore(), event.annualIncome(), event.requestedAmount());
        String notes = generateAssessmentNotes(riskLevel, event);

        // Save assessment to database
        String assessmentId = UUID.randomUUID().toString();
        RiskAssessment assessment = new RiskAssessment();
        assessment.setAssessmentId(assessmentId);
        assessment.setApplicationId(event.applicationId());
        assessment.setRiskLevel(riskLevel);
        assessment.setRiskScore(riskScore);
        assessment.setAssessmentNotes(notes);
        riskAssessmentRepository.save(assessment);

        // Update application status
        CreditApplication application = applicationRepository.findByApplicationId(event.applicationId())
                .orElseThrow(() -> new IllegalStateException("Application not found: " + event.applicationId()));
        application.setStatus(CreditApplication.ApplicationStatus.RISK_ASSESSED);
        applicationRepository.save(application);

        // Evict cache - application status changed!
        applicationService.evictApplicationCache(event.applicationId());
        log.debug("Evicted cache for application after status update: {}", event.applicationId());

        log.info("Risk assessment completed for application: {} with risk level: {}",
                event.applicationId(), riskLevel);

        // ==================== SAVE RESULT EVENT TO OUTBOX ====================
        // Create event and save to outbox (instead of publishing directly to Kafka)
        RiskAssessmentCompleted resultEvent = new RiskAssessmentCompleted(
                assessmentId,
                event.applicationId(),
                riskLevel,
                riskScore,
                notes,
                Instant.now()
        );

        // Save to outbox table (SAME transaction as risk assessment save!)
        try {
            saveToOutbox(resultEvent, assessmentId, "RiskAssessmentCompleted", KafkaTopics.RISK_ASSESSMENT_COMPLETED);
            log.info("Saved RiskAssessmentCompleted event to outbox for application: {}", event.applicationId());
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize event for outbox: {}", assessmentId, e);
            throw new RuntimeException("Failed to save event to outbox", e);
        }
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
     * Simple risk level calculation based on credit score and debt-to-income ratio.
     */
    private RiskLevel calculateRiskLevel(Integer creditScore, BigDecimal annualIncome, BigDecimal requestedAmount) {
        // Simplified risk calculation (real systems are much more complex)

        if (creditScore == null || creditScore < 550) {
            return RiskLevel.CRITICAL;
        }

        // Calculate debt-to-income ratio
        BigDecimal debtToIncomeRatio = requestedAmount.divide(annualIncome, 2, BigDecimal.ROUND_HALF_UP);

        if (creditScore >= 750 && debtToIncomeRatio.compareTo(BigDecimal.valueOf(0.3)) < 0) {
            return RiskLevel.LOW;
        } else if (creditScore >= 650 && debtToIncomeRatio.compareTo(BigDecimal.valueOf(0.5)) < 0) {
            return RiskLevel.MEDIUM;
        } else {
            return RiskLevel.HIGH;
        }
    }

    /**
     * Calculate numerical risk score (0-100).
     */
    private BigDecimal calculateRiskScore(Integer creditScore, BigDecimal annualIncome, BigDecimal requestedAmount) {
        // Simplified: convert credit score to 0-100 scale
        if (creditScore == null) {
            return BigDecimal.valueOf(80); // High risk if no credit score
        }

        // Higher credit score = lower risk score
        BigDecimal score = BigDecimal.valueOf(850 - creditScore)
                .divide(BigDecimal.valueOf(850), 2, BigDecimal.ROUND_HALF_UP)
                .multiply(BigDecimal.valueOf(100));

        return score;
    }

    /**
     * Generate human-readable assessment notes.
     */
    private String generateAssessmentNotes(RiskLevel riskLevel, CreditApplicationSubmitted event) {
        return String.format("Risk Level: %s. Credit Score: %d, Annual Income: %s, Requested: %s",
                riskLevel,
                event.creditScore(),
                event.annualIncome(),
                event.requestedAmount());
    }
}
