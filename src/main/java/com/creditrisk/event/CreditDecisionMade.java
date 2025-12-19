package com.creditrisk.event;

import com.creditrisk.model.DecisionStatus;
import java.time.Instant;

/**
 * Event published when a final credit decision is made.
 *
 * This is the final event in our flow:
 * CreditApplicationSubmitted -> RiskAssessmentCompleted -> CreditDecisionMade
 *
 * In a real system, this event might trigger:
 * - Email notification to customer
 * - Update to external credit bureau
 * - Activation of loan account (if approved)
 */
public record CreditDecisionMade(
    String decisionId,         // Unique ID for this decision
    String applicationId,      // Correlation ID
    String assessmentId,       // Links to the risk assessment
    DecisionStatus decision,   // Final decision
    String reason,             // Why we made this decision
    Instant timestamp
) {
    public CreditDecisionMade {
        if (decisionId == null || decisionId.isBlank()) {
            throw new IllegalArgumentException("Decision ID cannot be null or empty");
        }
        if (applicationId == null || applicationId.isBlank()) {
            throw new IllegalArgumentException("Application ID cannot be null or empty");
        }
        if (decision == null) {
            throw new IllegalArgumentException("Decision cannot be null");
        }
        if (timestamp == null) {
            timestamp = Instant.now();
        }
    }
}
