package org.softwood.actor.supervision

import groovy.transform.CompileStatic

/**
 * Defines how to handle actor failures.
 * 
 * <p>When an actor throws an exception while processing a message,
 * the supervisor strategy determines what action to take.</p>
 * 
 * <h2>Available Directives</h2>
 * <ul>
 *   <li><b>RESTART</b> - Stop the actor, create a new instance, resume processing</li>
 *   <li><b>RESUME</b> - Ignore the failure, continue with next message</li>
 *   <li><b>STOP</b> - Permanently stop the actor</li>
 *   <li><b>ESCALATE</b> - Pass the failure up to the parent supervisor</li>
 * </ul>
 * 
 * @since 2.0.0
 */
@CompileStatic
enum SupervisorDirective {
    /**
     * Restart the actor - discard state and create fresh instance.
     */
    RESTART,
    
    /**
     * Resume processing - ignore the exception and continue.
     */
    RESUME,
    
    /**
     * Stop the actor permanently.
     */
    STOP,
    
    /**
     * Escalate to parent supervisor.
     */
    ESCALATE
}
