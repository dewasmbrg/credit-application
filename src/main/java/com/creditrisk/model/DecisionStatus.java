package com.creditrisk.model;

/**
 * Final decision on a credit application.
 */
public enum DecisionStatus {
    APPROVED,        // Credit approved
    REJECTED,        // Credit denied
    MANUAL_REVIEW    // Needs human review
}
