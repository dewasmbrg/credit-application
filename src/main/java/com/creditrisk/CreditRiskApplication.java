package com.creditrisk;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Main Spring Boot application for Event-Driven Credit Risk System.
 *
 * This is a learning project demonstrating:
 * - Event-driven architecture with Kafka
 * - Asynchronous message processing
 * - Idempotency patterns
 * - Producer-consumer patterns
 *
 * To run this application:
 * 1. Start Kafka (see docker-compose.yml or run locally)
 * 2. Run this main class
 * 3. Submit a credit application via POST /api/applications
 * 4. Watch the logs to see async processing in action
 *
 * Architecture flow:
 * User -> REST API -> Kafka -> Risk Consumer -> Kafka -> Decision Consumer
 *
 * Key learning points:
 * - REST API returns immediately (async)
 * - Processing happens in background threads
 * - Events are immutable
 * - Idempotency prevents duplicate processing
 * - Services are decoupled via events
 */
@SpringBootApplication
public class CreditRiskApplication {

    public static void main(String[] args) {
        SpringApplication.run(CreditRiskApplication.class, args);
    }
}
