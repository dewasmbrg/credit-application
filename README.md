# Event-Driven Credit Risk System

A learning project demonstrating event-driven architecture with Kafka, async processing, and idempotency patterns.

## Learning Goals

This project is designed to teach:
- Event-driven architecture fundamentals
- Asynchronous message processing with Kafka
- Idempotency patterns (preventing duplicate processing)
- Producer-consumer patterns
- Failure handling in distributed systems

## Architecture Overview

### System Flow

```
1. User submits credit application
   ↓
2. REST API saves to DB and publishes event
   ↓
3. [KAFKA] CreditApplicationSubmitted event
   ↓
4. RiskAssessmentConsumer processes async
   ↓
5. [KAFKA] RiskAssessmentCompleted event
   ↓
6. DecisionConsumer makes final decision
```

### Key Components

1. **ApplicationController** - REST API for submitting applications
2. **ApplicationService** - Business logic and event publishing
3. **EventProducer** - Publishes events to Kafka
4. **RiskAssessmentConsumer** - Processes applications asynchronously
5. **DecisionConsumer** - Makes final credit decisions

### Tech Stack

- Java 17
- Spring Boot 3.2.0
- Spring Kafka
- H2 Database (file-based persistence)
- Kafka 7.5.0
- **Redis 7.2** (caching, idempotency, distributed locking)
- **Redisson** (distributed lock implementation)

## Quick Start

### Prerequisites

- Java 17 or higher
- Maven
- Docker and Docker Compose

### Step 1: Start Infrastructure (Kafka & Redis)

```bash
docker-compose up -d
```

This starts:
- Zookeeper (port 2181)
- Kafka broker (port 9092)
- Kafka UI (port 8090)
- **Redis** (port 6379) - for caching, idempotency, and distributed locking

Verify all services are running:
```bash
docker-compose ps
```

Test Redis connection:
```bash
docker exec redis redis-cli ping
# Should return: PONG
```

### Step 2: Run the Application

```bash
mvn spring-boot:run
```

The application starts on port 8080.

### Step 3: Submit a Credit Application

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

Response:
```json
{
  "applicationId": "uuid-here",
  "message": "Application submitted successfully. Risk assessment in progress."
}
```

### Step 4: Check Application Status

```bash
curl http://localhost:8080/api/applications/{applicationId}
```

### Step 5: Monitor Kafka (Optional)

Open http://localhost:8090 in your browser to see Kafka topics and messages.

### Step 6: Monitor Redis (Optional)

```bash
# View all Redis keys
docker exec redis redis-cli KEYS "*"

# Monitor Redis commands in real-time
docker exec redis redis-cli MONITOR

# View cache keys
docker exec redis redis-cli KEYS "credit-risk:*"

# View idempotency keys
docker exec redis redis-cli KEYS "idempotency:*"

# Check distributed lock
docker exec redis redis-cli GET "outbox-publisher-lock"

# Get Redis statistics
docker exec redis redis-cli INFO stats
docker exec redis redis-cli INFO memory
```

## Redis Integration Features

This project includes a comprehensive Redis integration demonstrating three key patterns:

### 1. **Caching** (50-100x faster reads)

**What:** Application status and risk assessments are cached in Redis

**Benefits:**
- First GET: Fetches from database (~10-50ms)
- Subsequent GETs: Fetches from Redis (<1ms)
- Reduces database load by >90%

**Configuration:**
- Applications cache: 30-minute TTL (frequently updated)
- Risk assessments cache: 1-hour TTL (immutable once created)

**Files:**
- `ApplicationService.java:164` - `@Cacheable` on getApplication()
- `RiskAssessmentService.java:51` - `@Cacheable` on getByApplicationId()
- Cache eviction after status changes to maintain consistency

### 2. **Redis-Based Idempotency** (50x faster than database)

**What:** Replaced database ProcessedEvent table with Redis

**Performance Comparison:**
- Database: 10-50ms per idempotency check
- Redis: <1ms per idempotency check
- **Speedup: 50-100x faster**

**Key Design:**
- Key pattern: `idempotency:{eventType}:{eventId}`
- Atomic check-and-set using `SET key NX EX`
- Auto-expiration after 7 days (matches Kafka retention)
- Failover: If Redis down, assume NOT processed (fail open)

**Files:**
- `IdempotencyService.java` - Complete implementation
- `RiskAssessmentConsumer.java:106` - Uses `tryAcquire()`
- `DecisionConsumer.java:78` - Uses `tryAcquire()`

### 3. **Distributed Locking** (enables multi-instance deployment)

**What:** Outbox publisher uses Redisson distributed locks

**Problem Without Locking:**
- 3 instances running → all query same events → all publish → 3x duplicates!

**Solution With Locking:**
- Only ONE instance publishes at a time
- Lock auto-releases after 30 seconds (prevents deadlock)
- Redisson watchdog auto-renews lock if thread alive
- Safe horizontal scaling

**Files:**
- `OutboxEventPublisher.java:131` - Lock acquisition logic
- `redisson-config.yml` - Redisson configuration

### Redis Persistence Strategy

**AOF (Append Only File) with `everysec`:**
- Logs every write operation
- Syncs to disk every second
- Balance: Performance vs. durability
- Worst case: Lose 1 second of data on crash

**Data stored in:** `./redis-data` volume (persists across container restarts)

## Key Concepts Explained

### 1. Events Are Immutable

Events represent facts that happened in the past. They never change.

```java
// This is a RECORD (Java 17+) - automatically immutable
public record CreditApplicationSubmitted(
    String applicationId,
    BigDecimal requestedAmount,
    Instant timestamp
) {}
```

**Why immutable?**
- Past facts cannot change
- Prevents bugs from accidental modifications
- Safe to share across threads (async processing)
- Easier to reason about

### 2. Idempotency Pattern (Redis-Based)

**Problem:** Kafka guarantees "at-least-once" delivery. The same event might be delivered multiple times.

**Solution:** Track which events we've already processed using Redis.

```java
@KafkaListener(topics = "credit.application.submitted")
public void processApplication(CreditApplicationSubmitted event) {
    // Redis-based idempotency check (50-100x faster than database!)
    if (!idempotencyService.tryAcquire("CreditApplicationSubmitted",
            event.applicationId(), "RiskAssessmentConsumer")) {
        return; // Skip - already processed!
    }

    // Now do the actual work
    performRiskAssessment(event);
}
```

**How it works:**
- Uses Redis `SET key NX EX` for atomic check-and-set
- Key: `idempotency:CreditApplicationSubmitted:app-123`
- TTL: 7 days (auto-expires, no manual cleanup needed)
- Performance: <1ms vs 10-50ms for database

**Without idempotency:**
- Event delivered twice = processed twice = duplicate data ❌

**With idempotency:**
- Event delivered twice = processed once = correct data ✅

### 3. Asynchronous Processing

**Traditional Synchronous Flow:**
```
User request → Process → Wait → Wait → Wait → Response (5 seconds)
```

**Event-Driven Asynchronous Flow:**
```
User request → Publish event → Response (100ms)
                ↓
    [Background thread processes]
```

**Benefits:**
- Fast response times (better UX)
- System stays responsive even under load
- Can handle more concurrent requests
- Processing failures don't block user requests

### 4. Failure Handling

**Scenario 1: Consumer Crashes Mid-Processing**
- Kafka doesn't receive acknowledgment
- Message is redelivered when consumer restarts
- Idempotency prevents duplicate processing ✅

**Scenario 2: Invalid Event Data**
- Consumer throws exception
- Kafka retries (configured: 3 times)
- After max retries → Dead Letter Queue
- We can manually inspect and fix ✅

**Scenario 3: Database Temporarily Down**
- Consumer throws exception
- Kafka retries automatically
- Eventually succeeds when DB is back up ✅

**Scenario 4: Kafka Broker Down**
- Producer's send() fails
- CompletableFuture captures the error
- We log it and can retry or alert operators ✅

### 5. Event Chain

Events flow through the system in a chain:

```
CreditApplicationSubmitted
         ↓
   [Risk Consumer]
         ↓
 RiskAssessmentCompleted
         ↓
  [Decision Consumer]
         ↓
   CreditDecisionMade
```

Each consumer:
- Listens to one event
- Does its work
- Publishes the next event
- Services are decoupled (don't know about each other)

## Common Mistakes and How We Avoid Them

### Mistake 1: Not Handling Duplicates
❌ **Bad:** Process every event without checking
✅ **Good:** Use ProcessedEvent table for idempotency

### Mistake 2: Making Events Mutable
❌ **Bad:** Using classes with setters
✅ **Good:** Using immutable records

### Mistake 3: Blocking Async Threads
❌ **Bad:**
```java
CompletableFuture<Result> future = kafkaTemplate.send(...);
Result result = future.get(); // BLOCKS! Defeats async purpose
```
✅ **Good:**
```java
CompletableFuture<Result> future = kafkaTemplate.send(...);
future.whenComplete((result, ex) -> {
    // Handle async
});
```

### Mistake 4: No Correlation IDs
❌ **Bad:** Events don't reference related events
✅ **Good:** Every event includes applicationId to track the flow

### Mistake 5: Not Logging Async Events
❌ **Bad:** No visibility into background processing
✅ **Good:** Extensive logging at each step (see our code)

## Testing the System

### Test Scenario 1: Happy Path

1. Submit application with good credit score (750+)
2. Check logs - see event published
3. Wait 2 seconds - see risk assessment complete
4. Check logs - see decision made (APPROVED)
5. Query application status - see DECISION_MADE

### Test Scenario 2: Idempotency

1. Submit application
2. Use Kafka UI to manually replay the event
3. Check logs - see "Event already processed, skipping"
4. Check database - only one application exists ✅

### Test Scenario 3: High Risk

1. Submit application with low credit score (500)
2. Wait for processing
3. Check logs - see CRITICAL risk level
4. Decision should be REJECTED

## Project Structure

```
src/main/java/com/creditrisk/
├── config/
│   ├── KafkaConfig.java          # Kafka producer/consumer setup
│   ├── KafkaTopics.java          # Topic name constants
│   └── RedisConfig.java          # Redis caching & serialization ✨
├── controller/
│   └── ApplicationController.java # REST API endpoints
├── service/
│   ├── ApplicationService.java    # Business logic + caching ✨
│   ├── RiskAssessmentService.java # Risk assessment caching ✨
│   ├── IdempotencyService.java    # Redis idempotency ✨
│   └── OutboxEventPublisher.java  # Outbox + distributed locking ✨
├── producer/
│   └── EventProducer.java         # Kafka event publisher
├── consumer/
│   ├── RiskAssessmentConsumer.java # Processes applications (Redis idempotency) ✨
│   └── DecisionConsumer.java      # Makes decisions (Redis idempotency) ✨
├── event/
│   ├── CreditApplicationSubmitted.java
│   ├── RiskAssessmentCompleted.java
│   └── CreditDecisionMade.java
├── model/
│   ├── CreditApplication.java     # JPA entity
│   ├── RiskAssessment.java        # JPA entity
│   ├── OutboxEvent.java           # Transactional Outbox
│   ├── ProcessedEvent.java        # Legacy (replaced by Redis)
│   ├── RiskLevel.java
│   └── DecisionStatus.java
└── repository/
    ├── CreditApplicationRepository.java
    ├── RiskAssessmentRepository.java
    ├── OutboxEventRepository.java
    └── ProcessedEventRepository.java
```

**✨ = Enhanced with Redis integration**

## Advanced Topics (Not Implemented, but Worth Learning)

### 1. Dead Letter Queue (DLQ)
When a message fails after max retries, send it to a special topic for manual processing.

### 2. Saga Pattern
For complex workflows spanning multiple services, use sagas to handle failures and compensating transactions.

### 3. Outbox Pattern
Instead of publishing events directly, write them to a database table, then have a separate process publish them. This ensures atomicity.

### 4. Event Sourcing
Store all events permanently and reconstruct state by replaying events. Powerful for audit trails.

### 5. CQRS (Command Query Responsibility Segregation)
Separate models for writing (commands) and reading (queries). Often used with event sourcing.

## Resources for Further Learning

- **Kafka Documentation:** https://kafka.apache.org/documentation/
- **Spring Kafka Reference:** https://docs.spring.io/spring-kafka/reference/
- **Martin Fowler on Event-Driven Architecture:** https://martinfowler.com/articles/201701-event-driven.html
- **Designing Data-Intensive Applications** by Martin Kleppmann (book)

## Troubleshooting

### Kafka Connection Refused
- Ensure Docker Compose is running: `docker-compose ps`
- Check Kafka is ready: `docker logs kafka`
- Kafka takes ~30 seconds to start

### Application Won't Start
- Check Java version: `java -version` (must be 17+)
- Check port 8080 is free: `netstat -ano | findstr :8080`

### Events Not Being Consumed
- Check Kafka topics exist: Use Kafka UI at http://localhost:8090
- Check consumer logs for errors
- Verify consumer group ID matches configuration

## License

This is a learning project - feel free to use and modify for educational purposes.

## Contributing

This is a learning project. Suggestions for improvements are welcome!
