package com.creditrisk.controller;

import com.creditrisk.model.CreditApplication;
import com.creditrisk.service.ApplicationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;

/**
 * REST Controller for credit applications.
 *
 * This is the entry point for submitting credit applications.
 */
@RestController
@RequestMapping("/api/applications")
@RequiredArgsConstructor
@Slf4j
public class ApplicationController {

    private final ApplicationService applicationService;

    /**
     * Submit a new credit application.
     *
     * POST /api/applications
     *
     * Example request:
     * {
     *   "customerId": "CUST-123",
     *   "requestedAmount": 50000,
     *   "creditScore": 720,
     *   "annualIncome": 80000
     * }
     *
     * Response:
     * {
     *   "applicationId": "uuid-here",
     *   "message": "Application submitted successfully"
     * }
     *
     * NOTE: This endpoint returns IMMEDIATELY. The risk assessment
     * happens asynchronously in the background.
     */
    @PostMapping
    public ResponseEntity<SubmissionResponse> submitApplication(
            @Valid @RequestBody ApplicationRequest request) {

        log.info("Received application request for customer: {}", request.getCustomerId());

        // Submit application (async processing starts here)
        String applicationId = applicationService.submitApplication(
                request.getCustomerId(),
                request.getRequestedAmount(),
                request.getCreditScore(),
                request.getAnnualIncome()
        );

        // Return immediately - user doesn't wait for risk assessment
        SubmissionResponse response = new SubmissionResponse();
        response.setApplicationId(applicationId);
        response.setMessage("Application submitted successfully. Risk assessment in progress.");

        return ResponseEntity.ok(response);
    }

    /**
     * Get application status.
     *
     * GET /api/applications/{applicationId}
     */
    @GetMapping("/{applicationId}")
    public ResponseEntity<CreditApplication> getApplication(@PathVariable String applicationId) {
        CreditApplication application = applicationService.getApplication(applicationId);
        return ResponseEntity.ok(application);
    }

    // ==================== DTOs ====================

    @Data
    public static class ApplicationRequest {
        @NotNull(message = "Customer ID is required")
        private String customerId;

        @NotNull(message = "Requested amount is required")
        @Positive(message = "Requested amount must be positive")
        private BigDecimal requestedAmount;

        private Integer creditScore;

        private BigDecimal annualIncome;
    }

    @Data
    public static class SubmissionResponse {
        private String applicationId;
        private String message;
    }
}
