# Idempotency and Failure Handling - Deep Dive

This document explains two critical concepts in event-driven systems: **Idempotency** and **Failure Handling**.

## Part 1: Understanding Idempotency

### What is Idempotency?

**Definition:** An operation is idempotent if performing it multiple times has the same effect as performing it once.

**Mathematical Example:**
- `SET x = 5` is idempotent (run it 100 times, x is still 5)
- `INCREMENT x` is NOT idempotent (run it 100 times, x is 100 different each time)

### Why Do We Need Idempotency in Event-Driven Systems?

**Kafka's Guarantee:** "At-least-once delivery"
- Every message WILL be delivered at least once ✅
- Some messages MIGHT be delivered multiple times ⚠️

**Common Scenarios Causing Duplicates:**

1. **Network Timeout**
   ```
   Consumer receives event → Processes it → Sends ACK
   Network fails → Kafka doesn't receive ACK
   Kafka resends event → Consumer receives DUPLICATE
   ```

2. **Consumer Crash**
   ```
   Consumer processes event → Crashes before sending ACK
   Consumer restarts → Kafka resends event → DUPLICATE
   ```

3. **Rebalancing**
   ```
   Consumer group rebalances (new consumer joins/leaves)
   Some messages might be delivered to multiple consumers
   ```

### How We Implement Idempotency

#### Step 1: ProcessedEvent Table

```java
@Entity
public class ProcessedEvent {
    @Id
    private Long id;

    @Column(unique = true)  // KEY: This must be unique!
    private String eventId;

    private String eventType;
    private Instant processedAt;
}
```

**Purpose:** Track every event we've processed

#### Step 2: Check Before Processing

```java
@KafkaListener(topics = "credit.application.submitted")
public void processApplication(CreditApplicationSubmitted event) {
    // 1. CHECK if we've seen this event before
    if (processedEventRepository.existsByEventId(event.applicationId())) {
        log.warn("Duplicate event detected: {}", event.applicationId());
        return; // SKIP - already processed!
    }

    // 2. RECORD that we're processing this event
    // (Do this BEFORE actual processing to handle crashes)
    ProcessedEvent processed = new ProcessedEvent();
    processed.setEventId(event.applicationId());
    processed.setEventType("CreditApplicationSubmitted");
    processedEventRepository.save(processed);

    // 3. NOW do the actual work
    performRiskAssessment(event);
}
```

### Idempotency in Action: Example Flow

**Scenario:** Same event delivered twice

```
Time 0: Event arrives (id=APP-123)
Time 1: Check ProcessedEvent table for APP-123 → NOT FOUND
Time 2: Save ProcessedEvent(eventId=APP-123)
Time 3: Process event (save risk assessment, publish result)
Time 4: Send ACK to Kafka

[Network glitch - Kafka doesn't receive ACK]

Time 5: Kafka resends event (id=APP-123)
Time 6: Check ProcessedEvent table for APP-123 → FOUND!
Time 7: SKIP processing
Time 8: Send ACK to Kafka
```

**Result:** Event processed exactly once, even though delivered twice ✅

### Common Idempotency Mistakes

#### Mistake 1: Checking After Processing
❌ **Wrong Order:**
```java
performRiskAssessment(event);  // Do work first
processedEventRepository.save(...);  // Record later
```

**Problem:** If consumer crashes between work and recording, work is done but not recorded. Next delivery will process again!

✅ **Correct Order:**
```java
processedEventRepository.save(...);  // Record FIRST
performRiskAssessment(event);  // Then do work
```

#### Mistake 2: Not Using Transactions
❌ **Without Transaction:**
```java
// These might succeed partially!
processedEventRepository.save(...);
riskAssessmentRepository.save(...);
// Crash here - inconsistent state!
applicationRepository.save(...);
```

✅ **With Transaction:**
```java
@Transactional  // All or nothing!
public void processApplication(CreditApplicationSubmitted event) {
    processedEventRepository.save(...);
    riskAssessmentRepository.save(...);
    applicationRepository.save(...);
    // If crash happens, ALL changes are rolled back
}
```

#### Mistake 3: Wrong Event ID
❌ **Using timestamp:**
```java
processed.setEventId(event.timestamp().toString());
// Different deliveries might have different timestamps!
```

✅ **Using business ID:**
```java
processed.setEventId(event.applicationId());
// Same ID across all deliveries
```

### Testing Idempotency

**Test 1: Manual Replay**
1. Submit application
2. Use Kafka UI to manually publish the same event again
3. Check database - should still have only ONE application
4. Check logs - should see "Duplicate event detected"

**Test 2: Simulated Crash**
1. Add a crash point in your consumer
2. Submit application
3. Consumer crashes after processing but before ACK
4. Restart consumer
5. Check - should not process again

---

## Part 2: Failure Handling

### Types of Failures in Event-Driven Systems

1. **Transient Failures** (temporary)
   - Network timeout
   - Database temporarily unavailable
   - Service temporarily overloaded

2. **Persistent Failures** (permanent)
   - Invalid data in event
   - Bug in consumer code
   - Schema mismatch

### Failure Handling Strategy 1: Retries

**Kafka's Built-In Retry Mechanism:**

When a consumer throws an exception, Kafka automatically retries.

**Configuration:**
```yaml
spring:
  kafka:
    consumer:
      # How many times to retry
      properties:
        max.poll.interval.ms: 300000  # 5 minutes
```

**In Code:**
```java
@KafkaListener(topics = "credit.application.submitted")
public void processApplication(CreditApplicationSubmitted event) {
    try {
        // Process event
        performRiskAssessment(event);
    } catch (TransientException e) {
        log.error("Transient error, will retry: {}", e.getMessage());
        throw e;  // Let Kafka retry
    } catch (PermanentException e) {
        log.error("Permanent error, sending to DLQ: {}", e.getMessage());
        // Don't throw - prevent infinite retries
        sendToDeadLetterQueue(event, e);
    }
}
```

### Failure Handling Strategy 2: Dead Letter Queue (DLQ)

**What is a DLQ?**
A special Kafka topic where failed messages go after max retries.

**Why use DLQ?**
- Prevent consumer from getting stuck on one bad message
- Allow manual inspection and processing
- Keep metrics on failure rates

**Implementation (Simplified):**
```java
public class KafkaErrorHandler implements ConsumerAwareListenerErrorHandler {

    @Override
    public Object handleError(Message<?> message,
                             ListenerExecutionFailedException exception,
                             Consumer<?, ?> consumer) {

        // Log the failure
        log.error("Message processing failed: {}", message, exception);

        // Send to DLQ topic
        kafkaTemplate.send("credit.application.dlq", message.getPayload());

        // Return null to acknowledge the message (remove from original topic)
        return null;
    }
}
```

### Failure Handling Strategy 3: Circuit Breaker

**Problem:** If downstream service is down, don't keep calling it.

**Solution:** After N failures, "open the circuit" and fail fast.

```java
@Service
public class ExternalCreditBureauService {

    private CircuitBreaker circuitBreaker = new CircuitBreaker();

    public CreditReport getReport(String customerId) {
        if (circuitBreaker.isOpen()) {
            throw new ServiceUnavailableException("Credit bureau is down");
        }

        try {
            CreditReport report = externalApi.getReport(customerId);
            circuitBreaker.recordSuccess();
            return report;
        } catch (Exception e) {
            circuitBreaker.recordFailure();
            throw e;
        }
    }
}
```

**States:**
- **CLOSED:** Normal operation, calls go through
- **OPEN:** Too many failures, reject calls immediately
- **HALF-OPEN:** After timeout, try one call to test recovery

### Failure Scenario Examples

#### Scenario 1: Database Temporarily Down

```
Time 0: Event arrives
Time 1: Consumer tries to save to database
Time 2: Database is down → SQLException
Time 3: Consumer throws exception
Time 4: Kafka retries (attempt 1)
Time 5: Database still down → SQLException
Time 6: Kafka retries (attempt 2)
...
Time 10: Database is back up
Time 11: Kafka retries (attempt 5) → SUCCESS ✅
```

**Result:** Eventually succeeds when database recovers.

#### Scenario 2: Invalid Event Data

```
Time 0: Event arrives with null applicationId
Time 1: Consumer tries to process
Time 2: NullPointerException
Time 3: Kafka retries (attempt 1) → Same error
Time 4: Kafka retries (attempt 2) → Same error
Time 5: Kafka retries (attempt 3) → Same error
Time 6: Max retries reached
Time 7: Send to DLQ
Time 8: Acknowledge message (remove from topic)
```

**Result:** Bad message sent to DLQ for manual inspection.

#### Scenario 3: Consumer Crashes

```
Time 0: Event arrives (id=APP-123)
Time 1: Save ProcessedEvent(APP-123)
Time 2: Start risk assessment
Time 3: Consumer crashes (out of memory)
Time 4: Consumer restarts
Time 5: Kafka redelivers event (id=APP-123)
Time 6: Check ProcessedEvent → FOUND (we saved it before crash!)
Time 7: Skip processing (idempotency saves us!) ✅
```

**Result:** No duplicate processing thanks to idempotency.

### Monitoring Failures

**Metrics to Track:**
1. **Retry Count:** How many retries per message?
2. **DLQ Size:** How many messages in DLQ?
3. **Processing Time:** Is it increasing? (might indicate problem)
4. **Error Rate:** Percentage of failed messages

**Example Logging:**
```java
@KafkaListener(topics = "credit.application.submitted")
public void processApplication(CreditApplicationSubmitted event) {
    long startTime = System.currentTimeMillis();

    try {
        performRiskAssessment(event);

        long duration = System.currentTimeMillis() - startTime;
        log.info("Processed event {} in {}ms", event.applicationId(), duration);

    } catch (Exception e) {
        log.error("Failed to process event {}: {}",
                 event.applicationId(), e.getMessage(), e);
        throw e;
    }
}
```

---

## Part 3: Advanced Patterns

### Pattern 1: Outbox Pattern

**Problem:** How to update database and publish event atomically?

**Solution:** Write event to database table, separate process publishes it.

```java
@Transactional
public void submitApplication(...) {
    // 1. Save application
    applicationRepository.save(application);

    // 2. Save event to outbox table (same transaction!)
    OutboxEvent outboxEvent = new OutboxEvent();
    outboxEvent.setEventType("CreditApplicationSubmitted");
    outboxEvent.setPayload(json);
    outboxRepository.save(outboxEvent);

    // Transaction commits - both saved or both rolled back
}

// Separate process
@Scheduled(fixedDelay = 1000)
public void publishPendingEvents() {
    List<OutboxEvent> pending = outboxRepository.findPending();
    for (OutboxEvent event : pending) {
        kafkaTemplate.send(event.getTopic(), event.getPayload());
        outboxRepository.markPublished(event);
    }
}
```

### Pattern 2: Saga Pattern

**Problem:** How to handle multi-step workflows across services?

**Solution:** Chain of events with compensating transactions for rollback.

```
Step 1: Reserve credit
  Success → Step 2
  Failure → End

Step 2: Charge account
  Success → Step 3
  Failure → Compensate: Release credit

Step 3: Create loan
  Success → Done!
  Failure → Compensate: Refund charge + Release credit
```

---

## Summary

### Idempotency Checklist
✅ Use unique business IDs for event tracking
✅ Check ProcessedEvent BEFORE processing
✅ Save ProcessedEvent in SAME transaction as business logic
✅ Use database constraints to prevent duplicates
✅ Test by manually replaying events

### Failure Handling Checklist
✅ Let transient errors retry automatically
✅ Send persistent errors to DLQ
✅ Monitor retry counts and DLQ size
✅ Log failures with full context
✅ Use circuit breakers for external services
✅ Test failure scenarios (kill consumer, take down DB, etc.)

### Common Questions

**Q: What if ProcessedEvent table gets huge?**
A: Archive or delete old entries after N days. Events older than that won't be redelivered.

**Q: What if saving ProcessedEvent fails?**
A: Entire transaction rolls back. Kafka will redeliver and we'll try again.

**Q: Can I use Redis instead of database for ProcessedEvent?**
A: Yes, but be careful - Redis might lose data if it crashes. Database is safer.

**Q: How long does Kafka keep events?**
A: Configurable. Default is 7 days. After that, old events are deleted.

---

This completes the deep dive into idempotency and failure handling!
