package com.creditrisk.producer;

import com.creditrisk.config.KafkaTopics;
import com.creditrisk.event.CreditApplicationSubmitted;
import com.creditrisk.event.RiskAssessmentCompleted;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;

/**
 * Service for publishing events to Kafka.
 *
 * ASYNC EXPLANATION:
 * ==================
 * Notice the return type: CompletableFuture<SendResult>
 *
 * This means:
 * - The method returns IMMEDIATELY (doesn't wait for Kafka to confirm)
 * - Kafka confirmation happens in the BACKGROUND
 * - We can attach callbacks to handle success/failure ASYNCHRONOUSLY
 *
 * Why async?
 * - If Kafka is slow, we don't block the calling thread
 * - Better throughput (can send many messages quickly)
 * - Better user experience (API responds faster)
 *
 * Common mistake:
 * - Calling .get() on CompletableFuture makes it SYNCHRONOUS (blocks)
 * - Only do this if you MUST wait for confirmation
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EventProducer {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    /**
     * Publish a CreditApplicationSubmitted event.
     *
     * @param event The event to publish
     * @return CompletableFuture that completes when Kafka acknowledges
     */
    public CompletableFuture<SendResult<String, Object>> publishCreditApplication(
            CreditApplicationSubmitted event) {

        log.info("Publishing CreditApplicationSubmitted event: {}", event.applicationId());

        // Send event to Kafka (asynchronous!)
        CompletableFuture<SendResult<String, Object>> future =
                kafkaTemplate.send(KafkaTopics.CREDIT_APPLICATION_SUBMITTED, event.applicationId(), event);

        // Attach callbacks for success/failure (optional but good practice)
        future.whenComplete((result, ex) -> {
            if (ex != null) {
                // Failed to send to Kafka
                log.error("Failed to publish CreditApplicationSubmitted event: {}",
                        event.applicationId(), ex);
            } else {
                // Successfully sent to Kafka
                log.info("Successfully published CreditApplicationSubmitted event: {} to partition {}",
                        event.applicationId(), result.getRecordMetadata().partition());
            }
        });

        return future;
    }

    /**
     * Publish a RiskAssessmentCompleted event.
     */
    public CompletableFuture<SendResult<String, Object>> publishRiskAssessment(
            RiskAssessmentCompleted event) {

        log.info("Publishing RiskAssessmentCompleted event: {}", event.assessmentId());

        CompletableFuture<SendResult<String, Object>> future =
                kafkaTemplate.send(KafkaTopics.RISK_ASSESSMENT_COMPLETED, event.applicationId(), event);

        future.whenComplete((result, ex) -> {
            if (ex != null) {
                log.error("Failed to publish RiskAssessmentCompleted event: {}",
                        event.assessmentId(), ex);
            } else {
                log.info("Successfully published RiskAssessmentCompleted event: {} to partition {}",
                        event.assessmentId(), result.getRecordMetadata().partition());
            }
        });

        return future;
    }
}
