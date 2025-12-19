package com.creditrisk.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * TRANSACTIONAL OUTBOX PATTERN
 * =============================
 *
 * This entity solves the "dual-write" problem in event-driven systems.
 *
 * THE PROBLEM:
 * ------------
 * When you save to database AND publish to Kafka in the same operation:
 * - Database transaction can commit, but Kafka publish fails -> Lost event
 * - Kafka publish succeeds, but database transaction rolls back -> Orphaned event
 * - These are TWO separate systems with NO atomic guarantee!
 *
 * THE SOLUTION (Outbox Pattern):
 * -------------------------------
 * 1. Save business data (e.g., RiskAssessment) to database
 * 2. Save event to OUTBOX TABLE in the SAME transaction
 * 3. Both commit atomically - if either fails, both rollback
 * 4. Separate background process reads outbox and publishes to Kafka
 * 5. Mark event as published after successful Kafka delivery
 *
 * BENEFITS:
 * ---------
 * - Guarantees: If data is saved, event WILL eventually be published
 * - Atomic: Database and outbox are in same transaction
 * - Reliable: Events can't be lost (they're in database)
 * - Retryable: Failed publishes can be retried indefinitely
 * - Auditable: You can see all events that were/will be published
 *
 * TRADE-OFFS:
 * -----------
 * - Slight delay: Events aren't published immediately (eventual consistency)
 * - Extra table: Need to store and manage outbox events
 * - Background job: Need scheduler/poller to publish events
 *
 * IMPLEMENTATION:
 * ---------------
 * This is used with OutboxEventPublisher service that:
 * - Polls this table every N milliseconds
 * - Publishes unpublished events to Kafka
 * - Marks them as published on success
 * - Retries failures automatically
 */
@Entity
@Table(name = "outbox_events",
       indexes = {
           @Index(name = "idx_published", columnList = "published"),
           @Index(name = "idx_created_at", columnList = "createdAt")
       })
@Data
@NoArgsConstructor
public class OutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Unique identifier for the business event (applicationId, assessmentId, etc.)
     * Used for deduplication and tracking.
     */
    @Column(nullable = false)
    private String eventId;

    /**
     * Type of event (e.g., "CreditApplicationSubmitted", "RiskAssessmentCompleted")
     * Determines which Kafka topic to publish to.
     */
    @Column(nullable = false)
    private String eventType;

    /**
     * Event payload serialized as JSON
     * Will be deserialized and sent to Kafka.
     */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String payload;

    /**
     * Target Kafka topic for this event
     */
    @Column(nullable = false)
    private String topic;

    /**
     * Whether this event has been published to Kafka
     * False = waiting to be published
     * True = already published
     */
    @Column(nullable = false)
    private Boolean published = false;

    /**
     * When this event was created (saved to outbox)
     */
    @Column(nullable = false)
    private Instant createdAt;

    /**
     * When this event was published to Kafka (null if not yet published)
     */
    private Instant publishedAt;

    /**
     * Number of times we tried to publish this event
     * Used for monitoring and alerting on stuck events
     */
    @Column(nullable = false)
    private Integer retryCount = 0;

    /**
     * Last error message if publishing failed
     * Useful for debugging
     */
    @Column(columnDefinition = "TEXT")
    private String lastError;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        if (published == null) {
            published = false;
        }
        if (retryCount == null) {
            retryCount = 0;
        }
    }
}
