package org.softwood.dag.task

import groovy.transform.ToString

/**
 * Context provided to ManualTask completion handlers.
 * Contains all information about how the task was completed.
 */
@ToString(includeNames = true, excludes = ['attachments'])
class ManualTaskContext {
    
    /** Form data submitted with the completion */
    Map<String, Object> formData = [:]
    
    /** Attachments uploaded with the completion */
    List<Attachment> attachments = []
    
    /** How the task was completed */
    CompletionOutcome outcome
    
    /** Who completed the task */
    String completedBy
    
    /** When the task was completed */
    java.time.LocalDateTime completedAt
    
    /** Optional reason for skip (if outcome is SKIP) */
    String skipReason
    
    /** Optional comments */
    String comments
}
