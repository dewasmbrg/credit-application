# Transactional Outbox Pattern Implementation

## Overview

This document describes the Transactional Outbox pattern implementation that has been added to the Credit Risk Event-Driven System.

## What Problem Does It Solve?

### The Dual-Write Problem (BEFORE)

**Old Flow:**
```
1. Save to Database ✅
2. Publish to Kafka ❌ (fails)
Result: Data in DB, but no event → Lost event!
```

OR

```
1. Save to Database ✅
2. Publish to Kafka ✅
3. Transaction rolls back ❌
Result: Event published, but no data → Orphaned event!
```

### The Solution (AFTER - Transactional Outbox)

**New Flow:**
```
1. Save business data to Database ✅
2. Save event to OUTBOX table ✅ (same transaction!)
3. Both commit atomically
4. Background publisher reads outbox
5. Publishes to Kafka
6. Marks as published
```

**Benefits:**
- ✅ **Atomic**: Database and outbox saved together
- ✅ **Reliable**: Events guaranteed to be published eventually
- ✅ **Kafka-independent**: Works even if Kafka is down
- ✅ **No data loss**: Events persisted before publishing
- ✅ **Production-ready**: Safe for high-traffic scenarios

## Files Created

### 1. OutboxEvent.java
**Path:** `src/main/java/com/creditrisk/model/OutboxEvent.java`

Entity that stores events waiting to be published to Kafka.

**Key fields:**
- `eventId`: Business event identifier (applicationId, assessmentId)
- `eventType`: Type of event (CreditApplicationSubmitted, etc.)
- `payload`: JSON serialized event data
- `topic`: Target Kafka topic
- `published`: Whether event has been published
- `retryCount`: Number of publish attempts
- `lastError`: Last error message (for debugging)

### 2. OutboxEventRepository.java
**Path:** `src/main/java/com/creditrisk/repository/OutboxEventRepository.java`

Repository for managing outbox events.

**Key queries:**
- `findUnpublishedEvents()`: Get all unpublished events (FIFO)
- `findUnpublishedEventsWithLimit(int)`: Batch processing
- `countByPublishedFalse()`: Monitor queue size

### 3. OutboxEventPublisher.java
**Path:** `src/main/java/com/creditrisk/service/OutboxEventPublisher.java`

Background service that publishes events from outbox to Kafka.

**How it works:**
- Runs every **100ms** (configurable)
- Fetches unpublished events (max 100 per batch)
- Deserializes and publishes to Kafka
- Marks as published on success
- Retries on failure automatically

**Monitoring:**
- Warns after 10 failed attempts
- Alerts on stuck events (older than 5 minutes)
- Logs queue size every minute

## Files Modified

### 1. ApplicationService.java
**Path:** `src/main/java/com/creditrisk/service/ApplicationService.java`

**Before:**
```java
applicationRepository.save(application);
eventProducer.publishCreditApplication(event); // Direct publish ❌
```

**After:**
```java
applicationRepository.save(application);
saveToOutbox(event, ...); // Save to outbox ✅
// Background publisher handles Kafka!
```

### 2. RiskAssessmentConsumer.java
**Path:** `src/main/java/com/creditrisk/consumer/RiskAssessmentConsumer.java`

**Before:**
```java
riskAssessmentRepository.save(assessment);
eventProducer.publishRiskAssessment(resultEvent); // Direct publish ❌
```

**After:**
```java
riskAssessmentRepository.save(assessment);
saveToOutbox(resultEvent, ...); // Save to outbox ✅
// Background publisher handles Kafka!
```

### 3. CreditRiskApplication.java
**Path:** `src/main/java/com/creditrisk/CreditRiskApplication.java`

Added `@EnableScheduling` to enable the OutboxEventPublisher scheduled tasks.

## Database Changes

### New Table: `outbox_events`

```sql
CREATE TABLE outbox_events (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    event_id VARCHAR(255) NOT NULL,
    event_type VARCHAR(255) NOT NULL,
    payload TEXT NOT NULL,
    topic VARCHAR(255) NOT NULL,
    published BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL,
    published_at TIMESTAMP,
    retry_count INT NOT NULL DEFAULT 0,
    last_error TEXT,
    INDEX idx_published (published),
    INDEX idx_created_at (created_at)
);
```

This table will be automatically created when you run the application (thanks to `ddl-auto: update`).

## How to Test

### 1. Start the Application

```bash
# Make sure Kafka is running (via docker-compose)
docker-compose up -d

# Start the Spring Boot application
mvn spring-boot:run
```

### 2. Submit a Credit Application

```bash
curl -X POST http://localhost:8080/api/applications \
  -H "Content-Type: application/json" \
  -d '{
    "customerId": "CUST-001",
    "requestedAmount": 50000,
    "creditScore": 720,
    "annualIncome": 80000
  }'
```

### 3. Verify Outbox Pattern

**Check the database (H2 Console at http://localhost:8080/h2-console):**

```sql
-- See unpublished events
SELECT * FROM outbox_events WHERE published = false;

-- See all events
SELECT * FROM outbox_events ORDER BY created_at DESC;

-- Monitor queue size
SELECT COUNT(*) FROM outbox_events WHERE published = false;
```

**Expected behavior:**
1. Event is saved to `outbox_events` with `published = false`
2. Within 100ms, OutboxEventPublisher picks it up
3. Event is published to Kafka
4. `published` changes to `true`, `published_at` is set

### 4. Check Logs

You should see logs like:

```
INFO  - Saved CreditApplicationSubmitted event to outbox: abc-123
DEBUG - Publishing 1 outbox events
INFO  - Successfully published event: abc-123 (type: CreditApplicationSubmitted)
```

### 5. Test Failure Scenarios

**Scenario 1: Kafka is down**
```bash
# Stop Kafka
docker-compose stop kafka

# Submit application - should still work!
curl -X POST http://localhost:8080/api/applications ...

# Event saved to outbox, but not published yet
# Start Kafka again
docker-compose start kafka

# Events will be automatically published
```

**Scenario 2: High traffic**
```bash
# Submit many requests simultaneously
for i in {1..100}; do
  curl -X POST http://localhost:8080/api/applications ... &
done

# All events saved atomically to outbox
# Published in order without data loss
```

## Configuration

### Adjust Publishing Frequency

In `OutboxEventPublisher.java`:

```java
@Scheduled(fixedDelay = 100) // Change this value (in milliseconds)
public void publishEvents() {
    // ...
}
```

- **Lower value** (e.g., 50ms) = Lower latency, more DB load
- **Higher value** (e.g., 500ms) = Higher latency, less DB load

### Adjust Batch Size

In `OutboxEventPublisher.java`:

```java
private static final int BATCH_SIZE = 100; // Change this value
```

### Adjust Max Retries

In `OutboxEventPublisher.java`:

```java
private static final int MAX_RETRY_COUNT = 10; // Change this value
```

## Production Considerations

### 1. Distributed Locking (Multiple Instances)

If you run multiple instances, add **ShedLock** to prevent duplicate publishing:

```xml
<dependency>
    <groupId>net.javacrumbs.shedlock</groupId>
    <artifactId>shedlock-spring</artifactId>
    <version>5.9.0</version>
</dependency>
```

### 2. Dead Letter Queue

Add logic to move events with too many failures to a DLQ:

```java
if (event.getRetryCount() >= MAX_RETRY_COUNT) {
    moveToDeadLetterQueue(event);
}
```

### 3. Metrics and Monitoring

Add Prometheus/Grafana metrics:
- Outbox queue size
- Publishing rate
- Failed events count
- Stuck events alert

### 4. Cleanup Old Published Events

Add a scheduled task to delete old published events:

```java
@Scheduled(cron = "0 0 2 * * *") // Every day at 2 AM
public void cleanupOldEvents() {
    Instant threshold = Instant.now().minusDays(7);
    outboxEventRepository.deleteByPublishedTrueAndPublishedAtBefore(threshold);
}
```

## Alternative: Debezium CDC

For enterprise production systems, consider using **Debezium Change Data Capture**:
- Captures database changes automatically
- No polling needed
- Ultra-reliable
- More complex setup

## Summary

You now have a **production-ready** event-driven system with:
- ✅ Transactional Outbox pattern
- ✅ Guaranteed event delivery
- ✅ No dual-write problem
- ✅ Safe for high traffic
- ✅ Kafka-independent operation
- ✅ Automatic retries
- ✅ Monitoring and alerting

The system is now **safe and reliable** even under high load!
