package org.softwood.dag.task

import java.time.Duration
import java.time.LocalDateTime

class RetryPolicy {

    int maxAttempts = 0
    Duration initialDelay = Duration.ofMillis(0)
    double backoffMultiplier = 1.0
    Duration circuitOpenDuration = Duration.ofSeconds(30)

    // internal state
    int attemptCount = 0
    LocalDateTime circuitOpenUntil

    boolean isCircuitOpen() {
        circuitOpenUntil != null && LocalDateTime.now().isBefore(circuitOpenUntil)
    }

    void recordFailure() {
        attemptCount++
        if (attemptCount > maxAttempts && maxAttempts > 0) {
            circuitOpenUntil = LocalDateTime.now().plus(circuitOpenDuration)
        }
    }

    void recordSuccess() {
        attemptCount = 0
        circuitOpenUntil = null
    }

    Duration nextDelay() {
        if (attemptCount <= 1) {
            return initialDelay
        }
        long base = initialDelay.toMillis()
        return Duration.ofMillis((long)(base * Math.pow(backoffMultiplier, attemptCount - 1)))
    }
}