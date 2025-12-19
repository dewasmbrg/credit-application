package com.creditrisk.repository;

import com.creditrisk.model.ProcessedEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Repository for tracking processed events (idempotency).
 *
 * Key method: existsByEventId()
 * - Returns true if we've already processed this event
 * - Returns false if this is a new event
 */
@Repository
public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, Long> {

    /**
     * Check if we've already processed this event.
     * This is used BEFORE processing any event to ensure idempotency.
     */
    boolean existsByEventId(String eventId);
}
