package com.caseware.pendingupdates.fanout;

import java.time.Duration;

/**
 * Settings for the worker.
 *
 * @param maxConcurrentCalls the most calls we make to the other team at the same time. This is the
 *                           number we agree with them.
 * @param queueCapacity      how many jobs we keep in memory. Anything beyond this waits in the table.
 * @param sweepPageSize      how many rows we read from the table in one go.
 * @param sweepInterval      how often we look in the table for unchecked engagements, as a safety net.
 * @param lease              how long a reservation lasts. It must be longer than the time allowed for
 *                           one call (for example: calls take 1 minute, give up after 2, lease is 3).
 * @param maxAttempts        how many times we try one engagement before marking it FAILED.
 * @param retryBaseDelay     the first wait before a retry. It doubles on each attempt.
 * @param retryMaxDelay      the longest we ever wait before a retry.
 * @param overloadPause      how long everyone stops when the other team says they are too busy.
 */
public record FanOutConfig(
        String workerId,
        int maxConcurrentCalls,
        int queueCapacity,
        int sweepPageSize,
        Duration sweepInterval,
        Duration lease,
        int maxAttempts,
        Duration retryBaseDelay,
        Duration retryMaxDelay,
        Duration overloadPause) {

    public FanOutConfig {
        if (workerId == null || workerId.isBlank()) throw new IllegalArgumentException("workerId required");
        if (maxConcurrentCalls < 1) throw new IllegalArgumentException("maxConcurrentCalls must be >= 1");
        if (queueCapacity < 1) throw new IllegalArgumentException("queueCapacity must be >= 1");
        if (sweepPageSize < 1) throw new IllegalArgumentException("sweepPageSize must be >= 1");
        if (maxAttempts < 1) throw new IllegalArgumentException("maxAttempts must be >= 1");
    }

    /** Sensible starting values for one region. The numbers are a starting point, not measured. */
    public static FanOutConfig defaults(String workerId) {
        return new FanOutConfig(workerId, 20, 20_000, 1_000,
                Duration.ofMinutes(5), Duration.ofMinutes(3), 5,
                Duration.ofSeconds(30), Duration.ofMinutes(15), Duration.ofMinutes(2));
    }
}
