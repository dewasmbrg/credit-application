package com.creditrisk.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Redis-based Idempotency Tracking Service
 *
 * MIGRATION FROM DATABASE TO REDIS:
 * =================================
 *
 * OLD (Database):
 * - ProcessedEvent table with unique constraint on eventId
 * - Each check = SELECT query
 * - Each record = INSERT + potential duplicate key exception
 * - Records stored forever (or manual cleanup required)
 * - Performance: ~10-50ms per check
 *
 * NEW (Redis):
 * - SET key with NX (not exists) flag
 * - Each check = Redis GET command
 * - Each record = Redis SET with automatic expiration
 * - Records auto-expire after 7 days
 * - Performance: <1ms per check
 *
 * PERFORMANCE COMPARISON:
 * =======================
 * Operation          | Database | Redis
 * -------------------|----------|-------
 * Check existence    | 10-50ms  | 0.5ms
 * Record processing  | 20-100ms | 1ms
 * Storage overhead   | Permanent| Auto-expires
 *
 * For 1000 events/sec:
 * - Database: 10-50 seconds of DB query time
 * - Redis: 0.5 seconds of Redis time
 * - Speedup: 50-100x faster
 *
 * KEY NAMING CONVENTION:
 * ======================
 * Pattern: idempotency:{eventType}:{eventId}
 * Examples:
 * - idempotency:CreditApplicationSubmitted:app-123
 * - idempotency:RiskAssessmentCompleted:assess-456
 *
 * WHY INCLUDE EVENT TYPE?
 * - Prevents collisions (same ID used in different event types)
 * - Easier debugging with redis-cli
 * - Better monitoring (can count by event type)
 *
 * TTL STRATEGY:
 * =============
 * 7 days (168 hours):
 * - Long enough to catch legitimate duplicate deliveries
 * - Short enough to not waste memory
 * - Kafka typically retains messages for 7 days by default
 *
 * FAILOVER STRATEGY:
 * ==================
 * If Redis is down:
 * - Assume NOT processed (fail open)
 * - Allow event processing (potential duplicate)
 * - Better to process twice than not at all
 * - Kafka will retry, and hopefully Redis is back up by then
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class IdempotencyService {

    private final RedisTemplate<String, Object> redisTemplate;

    // TTL for idempotency records (7 days)
    private static final Duration IDEMPOTENCY_TTL = Duration.ofDays(7);

    // Key prefix for idempotency tracking
    private static final String IDEMPOTENCY_PREFIX = "idempotency:";

    /**
     * Check if event has already been processed.
     *
     * This is a NON-BLOCKING check. Returns true/false immediately.
     *
     * @param eventType Type of event (e.g., "CreditApplicationSubmitted")
     * @param eventId Unique identifier for the event
     * @return true if already processed, false if new
     */
    public boolean isProcessed(String eventType, String eventId) {
        String key = buildKey(eventType, eventId);

        try {
            Boolean exists = redisTemplate.hasKey(key);
            boolean processed = Boolean.TRUE.equals(exists);

            if (processed) {
                log.debug("Event already processed (idempotency check): {}:{}", eventType, eventId);
            }

            return processed;

        } catch (Exception e) {
            log.error("Redis error checking idempotency, treating as NOT processed: {}:{}",
                     eventType, eventId, e);
            // FAILOVER: On Redis error, assume NOT processed
            // Kafka will retry, and hopefully Redis is back up by then
            // Better to process duplicate than miss an event
            return false;
        }
    }

    /**
     * Mark event as processed (atomically).
     *
     * Uses SET with NX (Not eXists) flag for atomicity:
     * - If key doesn't exist: Sets it and returns true
     * - If key exists: Does nothing and returns false
     *
     * This is ATOMIC - no race condition even with multiple consumers.
     *
     * @param eventType Type of event
     * @param eventId Unique identifier for the event
     * @param consumerName Name of the consumer processing this event
     * @return true if successfully marked (first processor), false if already marked
     */
    public boolean markAsProcessed(String eventType, String eventId, String consumerName) {
        String key = buildKey(eventType, eventId);

        try {
            // Value stored: consumerName + timestamp (for debugging)
            String value = String.format("%s:%d", consumerName, System.currentTimeMillis());

            // SET key value NX EX ttl
            // - NX: Only set if key doesn't exist (atomic check-and-set)
            // - EX: Set expiration in seconds
            Boolean success = redisTemplate.opsForValue()
                .setIfAbsent(key, value, IDEMPOTENCY_TTL);

            if (Boolean.TRUE.equals(success)) {
                log.debug("Marked event as processed: {}:{} by {}", eventType, eventId, consumerName);
                return true;
            } else {
                log.warn("Event already processed by another consumer: {}:{}", eventType, eventId);
                return false;
            }

        } catch (Exception e) {
            log.error("Redis error marking event as processed: {}:{}", eventType, eventId, e);
            // FAILOVER: On Redis error, assume NOT marked
            // Consumer will process the event (potential duplicate)
            // Better to process twice than not at all
            return true;
        }
    }

    /**
     * Combined check-and-set operation for idempotency.
     *
     * This is the RECOMMENDED method to use in consumers.
     *
     * Returns true if this is the FIRST time seeing this event.
     * Returns false if this event was already processed.
     *
     * USAGE IN CONSUMERS:
     * ===================
     * if (!idempotencyService.tryAcquire("EventType", eventId, "ConsumerName")) {
     *     log.warn("Event already processed, skipping");
     *     return;
     * }
     * // Process event...
     *
     * @param eventType Type of event
     * @param eventId Unique identifier
     * @param consumerName Name of consumer
     * @return true if new event (process it), false if duplicate (skip it)
     */
    public boolean tryAcquire(String eventType, String eventId, String consumerName) {
        // First check if already processed (fast path - single Redis call)
        if (isProcessed(eventType, eventId)) {
            return false;
        }

        // Try to mark as processed (atomic check-and-set)
        return markAsProcessed(eventType, eventId, consumerName);
    }

    /**
     * Build Redis key from event type and ID.
     *
     * Format: idempotency:{eventType}:{eventId}
     */
    private String buildKey(String eventType, String eventId) {
        return IDEMPOTENCY_PREFIX + eventType + ":" + eventId;
    }

    /**
     * Get processing info for debugging.
     * Returns consumer name and timestamp of when event was processed.
     *
     * Example output: "RiskAssessmentConsumer:1638360000000"
     *
     * @param eventType Type of event
     * @param eventId Unique identifier
     * @return Processing info string, or null if not found
     */
    public String getProcessingInfo(String eventType, String eventId) {
        String key = buildKey(eventType, eventId);
        try {
            Object value = redisTemplate.opsForValue().get(key);
            return value != null ? value.toString() : null;
        } catch (Exception e) {
            log.error("Redis error getting processing info: {}:{}", eventType, eventId, e);
            return null;
        }
    }
}
