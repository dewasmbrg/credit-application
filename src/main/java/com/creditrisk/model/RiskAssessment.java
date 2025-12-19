package com.creditrisk.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Entity storing risk assessment results.
 *
 * Why store this in DB?
 * - Audit trail (who got what risk score and when)
 * - Historical analysis
 * - Regulatory compliance (must keep records)
 */
@Entity
@Table(name = "risk_assessments")
@Data
@NoArgsConstructor
public class RiskAssessment {

    @Id
    private String assessmentId;

    @Column(nullable = false)
    private String applicationId;  // Foreign key relationship (simplified)

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RiskLevel riskLevel;

    private BigDecimal riskScore;

    @Column(length = 1000)
    private String assessmentNotes;

    @Column(nullable = false)
    private Instant assessedAt;

    @PrePersist
    protected void onCreate() {
        if (assessedAt == null) {
            assessedAt = Instant.now();
        }
    }
}
