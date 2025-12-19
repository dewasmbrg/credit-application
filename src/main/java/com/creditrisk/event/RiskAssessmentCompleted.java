package com.creditrisk.event;

import com.creditrisk.model.RiskLevel;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * Event published when risk assessment is completed.
 *
 * This event is produced by the RiskAssessmentConsumer after it processes
 * the CreditApplicationSubmitted event.
 *
 * Notice: This event references the original applicationId to maintain
 * the correlation between events. This is crucial in event-driven systems
 * to track the flow of a single business transaction across multiple services.
 */
public record RiskAssessmentCompleted(
    String assessmentId,       // Unique ID for this assessment
    String applicationId,      // Links back to the original application (correlation ID)
    RiskLevel riskLevel,       // Calculated risk level
    BigDecimal riskScore,      // Numerical risk score (0-100)
    String assessmentNotes,    // Why we assigned this risk level
    Instant timestamp
) {
    public RiskAssessmentCompleted {
        if (assessmentId == null || assessmentId.isBlank()) {
            throw new IllegalArgumentException("Assessment ID cannot be null or empty");
        }
        if (applicationId == null || applicationId.isBlank()) {
            throw new IllegalArgumentException("Application ID cannot be null or empty");
        }
        if (riskLevel == null) {
            throw new IllegalArgumentException("Risk level cannot be null");
        }
        if (timestamp == null) {
            timestamp = Instant.now();
        }
    }
}
