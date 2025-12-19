package com.creditrisk.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Entity representing a credit application in our database.
 *
 * Note the difference between Events and Entities:
 * - EVENTS (CreditApplicationSubmitted): Immutable messages passed between services
 * - ENTITIES (CreditApplication): Mutable database records for persistence
 *
 * We use entities to:
 * 1. Store the current state
 * 2. Query historical data
 * 3. Implement idempotency (prevent duplicate processing)
 */
@Entity
@Table(name = "credit_applications")
@Data
@NoArgsConstructor
public class CreditApplication {

    @Id
    private String applicationId;  // Same as event's applicationId

    @Column(nullable = false)
    private String customerId;

    @Column(nullable = false)
    private BigDecimal requestedAmount;

    private Integer creditScore;

    private BigDecimal annualIncome;

    @Enumerated(EnumType.STRING)
    private ApplicationStatus status;

    @Column(nullable = false)
    private Instant submittedAt;

    private Instant lastUpdated;

    public enum ApplicationStatus {
        SUBMITTED,          // Initial state
        RISK_ASSESSED,      // Risk assessment completed
        DECISION_MADE       // Final decision made
    }

    @PrePersist
    @PreUpdate
    protected void onUpdate() {
        lastUpdated = Instant.now();
    }
}
