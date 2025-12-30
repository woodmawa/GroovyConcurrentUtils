package org.softwood.dag.persistence

import groovy.transform.ToString

/**
 * DSL builder for PersistenceConfig.
 * 
 * Usage:
 * <pre>
 * TaskGraph.build {
 *     persistence {
 *         enabled true
 *         baseDir "production-data/snapshots"
 *         snapshotOn "failure"
 *         compression true
 *         retentionPolicy {
 *             maxSnapshots 5
 *             maxRetries 3
 *         }
 *     }
 *     
 *     serviceTask("task1") { ... }
 * }
 * </pre>
 */
@ToString(includeNames = true, includePackage = false)
class PersistenceConfigDsl {
    
    private boolean enabled = false
    private String baseDir = "graphState"
    private String snapshotOn = "failure"
    private boolean compression = false
    private int maxSnapshots = 2
    private int maxRetries = 2
    
    /**
     * Enable or disable persistence
     */
    void enabled(boolean value) {
        this.enabled = value
    }
    
    /**
     * Set base directory for .gsm files
     */
    void baseDir(String value) {
        this.baseDir = value
    }
    
    /**
     * Set snapshot trigger mode: "always", "failure", "never"
     */
    void snapshotOn(String value) {
        this.snapshotOn = value
    }
    
    /**
     * Enable or disable gzip compression
     */
    void compression(boolean value) {
        this.compression = value
    }
    
    /**
     * Configure retention policy using a closure
     */
    void retentionPolicy(@DelegatesTo(RetentionPolicyDsl) Closure config) {
        def dsl = new RetentionPolicyDsl()
        config.delegate = dsl
        config.resolveStrategy = Closure.DELEGATE_FIRST
        config.call()
        
        this.maxSnapshots = dsl.maxSnapshots
        this.maxRetries = dsl.maxRetries
    }
    
    /**
     * Build the final PersistenceConfig
     */
    PersistenceConfig build() {
        return PersistenceConfig.builder()
            .enabled(enabled)
            .baseDir(baseDir)
            .snapshotOn(parseSnapshotMode(snapshotOn))
            .compression(compression)
            .maxSnapshots(maxSnapshots)
            .maxRetries(maxRetries)
            .build()
    }
    
    private static SnapshotMode parseSnapshotMode(String mode) {
        if (!mode) return SnapshotMode.FAILURE
        
        switch (mode.toLowerCase()) {
            case "always":
                return SnapshotMode.ALWAYS
            case "failure":
                return SnapshotMode.FAILURE
            case "never":
                return SnapshotMode.NEVER
            default:
                return SnapshotMode.FAILURE
        }
    }
    
    /**
     * Inner DSL for retention policy configuration
     */
    static class RetentionPolicyDsl {
        int maxSnapshots = 2
        int maxRetries = 2
        
        void maxSnapshots(int value) {
            this.maxSnapshots = value
        }
        
        void maxRetries(int value) {
            this.maxRetries = value
        }
    }
}
