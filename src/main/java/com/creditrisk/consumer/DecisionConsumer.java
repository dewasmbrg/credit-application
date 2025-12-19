package com.creditrisk.consumer;

import com.creditrisk.config.KafkaTopics;
import com.creditrisk.event.RiskAssessmentCompleted;
import com.creditrisk.model.CreditApplication;
import com.creditrisk.model.DecisionStatus;
import com.creditrisk.model.ProcessedEvent;
import com.creditrisk.model.RiskLevel;
import com.creditrisk.repository.CreditApplicationRepository;
import com.creditrisk.repository.ProcessedEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Consumer that makes final credit decisions based on risk assessment.
 *
 * This is the SECOND consumer in our event chain:
 * 1. RiskAssessmentConsumer processes CreditApplicationSubmitted
 * 2. RiskAssessmentConsumer publishes RiskAssessmentCompleted
 * 3. THIS CONSUMER processes RiskAssessmentCompleted
 * 4. Final decision is made
 *
 * EVENT-DRIVEN ARCHITECTURE BENEFIT:
 * ===================================
 * Notice how this consumer is COMPLETELY DECOUPLED from RiskAssessmentConsumer:
 * - They don't know about each other
 * - They communicate only through events
 * - We could deploy them as separate microservices
 * - We could add more consumers without changing existing ones
 * - If DecisionConsumer crashes, RiskAssessmentConsumer keeps working
 *
 * This is the power of event-driven architecture!
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DecisionConsumer {

    private final ProcessedEventRepository processedEventRepository;
    private final CreditApplicationRepository applicationRepository;

    /**
     * Process risk assessment and make final decision.
     *
     * IDEMPOTENCY:
     * ============
     * Notice we use the SAME idempotency pattern as RiskAssessmentConsumer.
     * This is a standard pattern you should use in ALL consumers.
     *
     * FAILURE HANDLING:
     * =================
     * What if this method throws an exception?
     * - Kafka will RETRY the message (configured in KafkaConfig)
     * - After max retries, the message goes to a Dead Letter Queue (if configured)
     * - We can monitor the DLQ and manually process failed messages
     *
     * Common failure scenarios:
     * - Database is down -> Retry until it's back up
     * - Invalid data in event -> Goes to DLQ after max retries
     * - Bug in our code -> Fix bug, then reprocess from DLQ
     */
    @KafkaListener(
            topics = KafkaTopics.RISK_ASSESSMENT_COMPLETED,
            groupId = "decision-group",
            containerFactory = "kafkaListenerContainerFactory"
    )
    @Transactional
    public void makeDecision(RiskAssessmentCompleted event) {
        log.info("Received RiskAssessmentCompleted event for application: {}", event.applicationId());

        // ==================== IDEMPOTENCY CHECK ====================
        // Use assessmentId as the unique event identifier
        if (processedEventRepository.existsByEventId(event.assessmentId())) {
            log.warn("Event already processed, skipping: {}", event.assessmentId());
            return;
        }

        // Record that we're processing this event
        ProcessedEvent processedEvent = new ProcessedEvent();
        processedEvent.setEventId(event.assessmentId());
        processedEvent.setEventType("RiskAssessmentCompleted");
        processedEvent.setConsumerName("DecisionConsumer");
        processedEventRepository.save(processedEvent);

        log.info("Making decision for application: {}", event.applicationId());

        // ==================== MAKE DECISION ====================
        DecisionStatus decision = determineDecision(event.riskLevel());

        // Update application with final decision
        CreditApplication application = applicationRepository.findByApplicationId(event.applicationId())
                .orElseThrow(() -> new IllegalStateException("Application not found: " + event.applicationId()));

        application.setStatus(CreditApplication.ApplicationStatus.DECISION_MADE);
        applicationRepository.save(application);

        log.info("Decision made for application: {} - Decision: {}", event.applicationId(), decision);

        // ==================== NEXT STEPS (Not Implemented) ====================
        // In a real system, you would:
        // 1. Publish a CreditDecisionMade event
        // 2. Send email/SMS notification to customer
        // 3. Update external systems (credit bureau, loan management, etc.)
        // 4. Create loan account if approved
        //
        // We omit this for simplicity, but you can see the pattern.
    }

    /**
     * Determine final decision based on risk level.
     *
     * Decision rules (simplified):
     * - LOW risk -> Auto-approve
     * - MEDIUM risk -> Manual review
     * - HIGH risk -> Auto-reject
     * - CRITICAL risk -> Auto-reject
     */
    private DecisionStatus determineDecision(RiskLevel riskLevel) {
        return switch (riskLevel) {
            case LOW -> DecisionStatus.APPROVED;
            case MEDIUM -> DecisionStatus.MANUAL_REVIEW;
            case HIGH, CRITICAL -> DecisionStatus.REJECTED;
        };
    }
}
