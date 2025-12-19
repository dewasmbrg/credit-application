package com.creditrisk.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * CRITICAL for IDEMPOTENCY!
 *
 * This entity tracks which events we've already processed.
 *
 * Why do we need this?
 * ==================
 * Kafka guarantees "at-least-once" delivery. This means:
 * - An event WILL be delivered at least once
 * - An event MIGHT be delivered multiple times (due to retries, crashes, etc.)
 *
 * Without idempotency tracking:
 * 1. Consumer receives CreditApplicationSubmitted (id=123)
 * 2. Consumer processes it, saves to DB
 * 3. Kafka broker crashes before receiving acknowledgment
 * 4. Consumer receives the SAME event again (id=123)
 * 5. Consumer processes it AGAIN -> DUPLICATE DATA!
 *
 * With idempotency tracking:
 * 1. Consumer receives event (id=123)
 * 2. Consumer checks: "Have I processed id=123 before?" -> No
 * 3. Consumer saves ProcessedEvent(eventId=123)
 * 4. Consumer processes the event
 * 5. Consumer receives SAME event again (id=123)
 * 6. Consumer checks: "Have I processed id=123 before?" -> YES
 * 7. Consumer SKIPS processing -> No duplicate!
 *
 * This is a common pattern in event-driven systems.
 */
@Entity
@Table(name = "processed_events",
       indexes = @Index(name = "idx_event_id", columnList = "eventId"))
@Data
@NoArgsConstructor
public class ProcessedEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * The unique identifier of the event we processed.
     * For our events, this would be applicationId, assessmentId, etc.
     */
    @Column(nullable = false, unique = true)
    private String eventId;

    /**
     * What type of event was this?
     * e.g., "CreditApplicationSubmitted", "RiskAssessmentCompleted"
     */
    @Column(nullable = false)
    private String eventType;

    /**
     * Which consumer processed this?
     * Useful for debugging in systems with multiple consumers.
     */
    private String consumerName;

    /**
     * When did we process this event?
     */
    @Column(nullable = false)
    private Instant processedAt;

    @PrePersist
    protected void onCreate() {
        if (processedAt == null) {
            processedAt = Instant.now();
        }
    }
}
