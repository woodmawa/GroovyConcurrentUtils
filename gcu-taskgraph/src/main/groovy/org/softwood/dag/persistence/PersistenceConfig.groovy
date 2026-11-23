package org.softwood.dag.persistence

import groovy.transform.ToString
import groovy.transform.builder.Builder

/**
 * Configuration for TaskGraph persistence feature.
 * Controls when and how graph execution state is persisted.
 */
@ToString(includeNames = true, includePackage = false)
@Builder
class PersistenceConfig implements Serializable {
    private static final long serialVersionUID = 1L
    
    // Feature toggle
    boolean enabled = false
    
    // Base directory for .gsm files
    String baseDir = "graphState"
    
    // Snapshot trigger strategy
    SnapshotMode snapshotOn = SnapshotMode.FAILURE
    
    // Compression settings
    boolean compression = false
    
    // Retention policy
    int maxSnapshots = 2
    int maxRetries = 2
    
    /**
     * Check if snapshots should be taken for this execution
     */
    boolean shouldSnapshot(boolean graphFailed) {
        if (!enabled) return false
        
        switch (snapshotOn) {
            case SnapshotMode.ALWAYS:
                return true
            case SnapshotMode.FAILURE:
                return graphFailed
            case SnapshotMode.NEVER:
                return false
            default:
                return false
        }
    }
    
    /**
     * Load config from application config map
     */
    static PersistenceConfig fromConfig(Map config) {
        def persistConfig = config?.taskgraph?.persistence
        
        if (!persistConfig) {
            // Return disabled config if not found
            return PersistenceConfig.builder().enabled(false).build()
        }
        
        return PersistenceConfig.builder()
            .enabled(persistConfig.enabled ?: false)
            .baseDir(persistConfig.baseDir ?: "graphState")
            .snapshotOn(parseSnapshotMode(persistConfig.snapshotOn))
            .compression(persistConfig.compression ?: false)
            .maxSnapshots(persistConfig.retentionPolicy?.maxSnapshots ?: 2)
            .maxRetries(persistConfig.retentionPolicy?.maxRetries ?: 2)
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
}

/**
 * Defines when snapshots should be taken
 */
enum SnapshotMode {
    ALWAYS,     // Snapshot every run
    FAILURE,    // Snapshot only on task/graph failure
    NEVER       // Disable snapshots
}
