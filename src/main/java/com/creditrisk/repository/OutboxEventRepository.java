package com.creditrisk.repository;

import com.creditrisk.model.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

/**
 * Repository for managing outbox events.
 *
 * Key queries:
 * - Find unpublished events (for the publisher to process)
 * - Find old unpublished events (for alerting on stuck events)
 */
@Repository
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    /**
     * Find all unpublished events, ordered by creation time (FIFO).
     * This ensures events are published in the order they were created.
     *
     * @return List of unpublished events
     */
    @Query("SELECT e FROM OutboxEvent e WHERE e.published = false ORDER BY e.createdAt ASC")
    List<OutboxEvent> findUnpublishedEvents();

    /**
     * Find unpublished events with a limit (for batch processing).
     * Prevents loading too many events at once.
     *
     * @param limit Maximum number of events to fetch
     * @return List of unpublished events
     */
    @Query(value = "SELECT * FROM outbox_events WHERE published = false ORDER BY created_at ASC LIMIT ?1",
           nativeQuery = true)
    List<OutboxEvent> findUnpublishedEventsWithLimit(int limit);

    /**
     * Find unpublished events older than a certain time.
     * Useful for monitoring and alerting on stuck events.
     *
     * @param before Events created before this time
     * @return List of old unpublished events
     */
    List<OutboxEvent> findByPublishedFalseAndCreatedAtBefore(Instant before);

    /**
     * Count unpublished events.
     * Useful for monitoring outbox queue size.
     *
     * @return Number of unpublished events
     */
    long countByPublishedFalse();
}
