package com.creditrisk.service;

import com.creditrisk.event.CreditApplicationSubmitted;
import com.creditrisk.model.CreditApplication;
import com.creditrisk.producer.EventProducer;
import com.creditrisk.repository.CreditApplicationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Service for handling credit application submissions.
 *
 * TRANSACTION MANAGEMENT:
 * =======================
 * Notice @Transactional on submitApplication():
 *
 * This ensures that:
 * 1. Database save and event publishing happen in a single transaction
 * 2. If event publishing fails, database save is rolled back
 * 3. Data consistency is maintained
 *
 * However, there's a TRADE-OFF:
 * - If Kafka is down, the entire transaction fails
 * - In production, you might want to:
 *   a) Save to DB first, then publish (risk: event might fail to send)
 *   b) Use outbox pattern (advanced, not shown here)
 *
 * For learning purposes, we keep it simple.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ApplicationService {

    private final CreditApplicationRepository applicationRepository;
    private final EventProducer eventProducer;

    /**
     * Submit a new credit application.
     *
     * Flow:
     * 1. Create and save application entity to database
     * 2. Create immutable event
     * 3. Publish event to Kafka (asynchronously)
     * 4. Return application ID to caller
     *
     * Note: This method returns QUICKLY because Kafka publishing is async.
     */
    @Transactional
    public String submitApplication(String customerId,
                                    BigDecimal requestedAmount,
                                    Integer creditScore,
                                    BigDecimal annualIncome) {

        // Generate unique ID for this application
        String applicationId = UUID.randomUUID().toString();

        log.info("Submitting credit application: {} for customer: {}", applicationId, customerId);

        // 1. Save to database
        CreditApplication application = new CreditApplication();
        application.setApplicationId(applicationId);
        application.setCustomerId(customerId);
        application.setRequestedAmount(requestedAmount);
        application.setCreditScore(creditScore);
        application.setAnnualIncome(annualIncome);
        application.setStatus(CreditApplication.ApplicationStatus.SUBMITTED);
        application.setSubmittedAt(Instant.now());

        applicationRepository.save(application);

        log.info("Saved application to database: {}", applicationId);

        // 2. Create immutable event
        CreditApplicationSubmitted event = new CreditApplicationSubmitted(
                applicationId,
                customerId,
                requestedAmount,
                creditScore,
                annualIncome,
                Instant.now()
        );

        // 3. Publish event to Kafka (async - doesn't block)
        eventProducer.publishCreditApplication(event);

        log.info("Published CreditApplicationSubmitted event: {}", applicationId);

        return applicationId;
    }

    /**
     * Get application by ID.
     */
    public CreditApplication getApplication(String applicationId) {
        return applicationRepository.findByApplicationId(applicationId)
                .orElseThrow(() -> new IllegalArgumentException("Application not found: " + applicationId));
    }
}
