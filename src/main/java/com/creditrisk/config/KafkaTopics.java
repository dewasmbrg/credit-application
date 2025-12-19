package com.creditrisk.config;

/**
 * Centralized Kafka topic names.
 *
 * Why centralize?
 * - Avoids typos (misspelling topic names is a common bug)
 * - Makes refactoring easier
 * - Documents all topics in one place
 */
public class KafkaTopics {

    // Topic for new credit applications
    public static final String CREDIT_APPLICATION_SUBMITTED = "credit.application.submitted";

    // Topic for completed risk assessments
    public static final String RISK_ASSESSMENT_COMPLETED = "risk.assessment.completed";

    // Topic for final credit decisions
    public static final String CREDIT_DECISION_MADE = "credit.decision.made";

    // Private constructor to prevent instantiation
    private KafkaTopics() {
    }
}
