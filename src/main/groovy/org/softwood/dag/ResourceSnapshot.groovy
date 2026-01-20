package org.softwood.dag

import groovy.transform.Canonical

/**
 * Snapshot of resource usage during graph execution.
 *
 * Captures current resource consumption for monitoring.
 * Zero dependencies - pure data structure.
 */
@Canonical
class ResourceSnapshot {

    /** Number of currently running tasks */
    int runningTasks

    /** Number of queued tasks waiting for slots */
    int queuedTasks

    /** Maximum concurrent tasks allowed (0 = unlimited) */
    int maxConcurrentTasks

    /** Current memory usage in MB */
    long usedMemoryMB

    /** Maximum memory allowed in MB (0 = unlimited) */
    long maxMemoryMB

    /** Total memory available to JVM in MB */
    long totalMemoryMB

    /** Free memory available in MB */
    long freeMemoryMB

    /**
     * Check if resource limits are being approached.
     *
     * @param threshold percentage threshold (e.g., 0.8 for 80%)
     * @return true if any resource is above threshold
     */
    boolean isNearLimit(double threshold = 0.8) {
        if (maxConcurrentTasks > 0) {
            double concurrencyRatio = runningTasks / (double) maxConcurrentTasks
            if (concurrencyRatio >= threshold) return true
        }

        if (maxMemoryMB > 0) {
            double memoryRatio = usedMemoryMB / (double) maxMemoryMB
            if (memoryRatio >= threshold) return true
        }

        return false
    }

    /**
     * Get concurrency utilization percentage (0-100).
     */
    double getConcurrencyUtilization() {
        if (maxConcurrentTasks == 0) return 0.0
        return (runningTasks / (double) maxConcurrentTasks) * 100.0
    }

    /**
     * Get memory utilization percentage (0-100).
     */
    double getMemoryUtilization() {
        if (maxMemoryMB == 0) return 0.0
        return (usedMemoryMB / (double) maxMemoryMB) * 100.0
    }

    /**
     * Get available task slots.
     */
    int getAvailableSlots() {
        if (maxConcurrentTasks == 0) return Integer.MAX_VALUE
        return Math.max(0, maxConcurrentTasks - runningTasks)
    }

    /**
     * Convert to map for JSON serialization.
     */
    Map<String, Object> toMap() {
        return [
            runningTasks: runningTasks,
            queuedTasks: queuedTasks,
            maxConcurrentTasks: maxConcurrentTasks,
            availableSlots: getAvailableSlots(),
            concurrencyUtilization: getConcurrencyUtilization(),
            usedMemoryMB: usedMemoryMB,
            maxMemoryMB: maxMemoryMB,
            totalMemoryMB: totalMemoryMB,
            freeMemoryMB: freeMemoryMB,
            memoryUtilization: getMemoryUtilization(),
            nearLimit: isNearLimit()
        ]
    }

    @Override
    String toString() {
        return "ResourceSnapshot[tasks=$runningTasks/$maxConcurrentTasks, " +
               "memory=${usedMemoryMB}MB/${maxMemoryMB}MB]"
    }
}
