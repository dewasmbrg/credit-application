package com.creditrisk.model;

/**
 * Risk levels for credit assessment.
 * In real systems, this would be much more granular.
 */
public enum RiskLevel {
    LOW,      // Good credit score, stable income
    MEDIUM,   // Average credit, some risk factors
    HIGH,     // Poor credit history or unstable finances
    CRITICAL  // Major red flags
}
