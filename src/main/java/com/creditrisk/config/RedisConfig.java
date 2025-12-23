package com.creditrisk.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Redis Configuration for Caching, Idempotency, and Distributed Locking
 *
 * DESIGN DECISIONS:
 * =================
 * 1. JSON Serialization: More readable, debuggable, and language-agnostic
 * 2. Multiple Cache Regions: Different TTLs for different data types
 * 3. RedisTemplate: For manual cache operations (idempotency)
 * 4. Spring Cache Abstraction: For declarative caching (@Cacheable)
 *
 * CACHE REGIONS:
 * ==============
 * - applications: Cache CreditApplication entities (TTL: 30 min)
 * - assessments: Cache RiskAssessment entities (TTL: 1 hour - rarely changes)
 *
 * TTL RATIONALE:
 * ==============
 * - Applications: 30 min (frequently updated during workflow)
 * - Assessments: 1 hour (immutable once created)
 * - Idempotency: 7 days (handled separately in IdempotencyService)
 */
@Configuration
@EnableCaching
@Slf4j
public class RedisConfig {

    /**
     * ObjectMapper for JSON serialization/deserialization.
     * Configured to handle Java 8 time types (Instant, LocalDateTime, etc.)
     */
    @Bean
    public ObjectMapper redisObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        // Enable Java 8 date/time support
        mapper.registerModule(new JavaTimeModule());
        // Write dates as ISO-8601 strings (not timestamps)
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        log.info("Configured ObjectMapper for Redis with JavaTimeModule");
        return mapper;
    }

    /**
     * RedisTemplate for manual Redis operations.
     * Used for idempotency tracking (SET with expiration).
     *
     * Generic <String, Object> allows storing any type of data.
     */
    @Bean
    public RedisTemplate<String, Object> redisTemplate(
            RedisConnectionFactory connectionFactory,
            ObjectMapper redisObjectMapper) {

        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);

        // Use String serializer for keys (human-readable)
        StringRedisSerializer stringSerializer = new StringRedisSerializer();
        template.setKeySerializer(stringSerializer);
        template.setHashKeySerializer(stringSerializer);

        // Use JSON serializer for values (flexible, debuggable)
        GenericJackson2JsonRedisSerializer jsonSerializer =
            new GenericJackson2JsonRedisSerializer(redisObjectMapper);
        template.setValueSerializer(jsonSerializer);
        template.setHashValueSerializer(jsonSerializer);

        template.afterPropertiesSet();

        log.info("Configured RedisTemplate with JSON serialization");
        return template;
    }

    /**
     * CacheManager for Spring Cache abstraction (@Cacheable, @CacheEvict).
     *
     * Different cache regions have different TTLs based on data characteristics:
     * - Mutable data: Shorter TTL
     * - Immutable data: Longer TTL
     * - Frequently accessed: Cache it
     * - Rarely accessed: Don't cache it
     */
    @Bean
    public CacheManager cacheManager(
            RedisConnectionFactory connectionFactory,
            ObjectMapper redisObjectMapper) {

        // Default cache configuration
        RedisCacheConfiguration defaultConfig = RedisCacheConfiguration.defaultCacheConfig()
            .entryTtl(Duration.ofMinutes(30))  // Default TTL: 30 minutes
            .disableCachingNullValues()         // Don't cache null values
            .serializeKeysWith(
                RedisSerializationContext.SerializationPair.fromSerializer(
                    new StringRedisSerializer()))
            .serializeValuesWith(
                RedisSerializationContext.SerializationPair.fromSerializer(
                    new GenericJackson2JsonRedisSerializer(redisObjectMapper)));

        // Custom configurations for specific cache regions
        Map<String, RedisCacheConfiguration> cacheConfigurations = new HashMap<>();

        // Cache for CreditApplication entities
        // TTL: 30 minutes (applications are updated frequently during workflow)
        cacheConfigurations.put("applications", defaultConfig
            .entryTtl(Duration.ofMinutes(30))
            .prefixCacheNameWith("app:"));

        // Cache for RiskAssessment entities
        // TTL: 1 hour (assessments are immutable once created)
        cacheConfigurations.put("assessments", defaultConfig
            .entryTtl(Duration.ofHours(1))
            .prefixCacheNameWith("assess:"));

        // Build cache manager
        RedisCacheManager cacheManager = RedisCacheManager.builder(connectionFactory)
            .cacheDefaults(defaultConfig)
            .withInitialCacheConfigurations(cacheConfigurations)
            .transactionAware()  // Respect @Transactional boundaries
            .build();

        log.info("Configured RedisCacheManager with regions: applications (30m), assessments (1h)");
        return cacheManager;
    }
}
