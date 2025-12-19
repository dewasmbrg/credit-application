package com.creditrisk.event;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Event published when a new credit application is submitted.
 *
 * IMPORTANT: This is a RECORD (Java 17+), which makes it IMMUTABLE.
 * - All fields are final by default
 * - No setters are generated
 * - Once created, the event cannot be modified
 *
 * Why immutable?
 * - Events represent facts that happened in the past
 * - Past facts cannot change
 * - Makes the system more predictable and easier to reason about
 * - Prevents accidental modifications during async processing
 */
public record CreditApplicationSubmitted(
    String applicationId,      // Unique identifier for this application
    String customerId,         // Who is applying
    BigDecimal requestedAmount,// How much money they want
    Integer creditScore,       // Their credit score (simplified)
    BigDecimal annualIncome,   // Their yearly income
    Instant timestamp          // When this event was created
) {
    /**
     * Compact constructor for validation.
     * This runs before the record is created.
     */
    public CreditApplicationSubmitted {
        if (applicationId == null || applicationId.isBlank()) {
            throw new IllegalArgumentException("Application ID cannot be null or empty");
        }
        if (requestedAmount == null || requestedAmount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Requested amount must be positive");
        }
        if (timestamp == null) {
            timestamp = Instant.now();
        }
    }
}
