package org.softwood.dag.task

import groovy.util.logging.Slf4j
import org.softwood.promise.Promise

import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ConcurrentHashMap

/**
 * EventGatewayTask - Wait for First Event (Event-Based Gateway)
 *
 * Waits for multiple possible events and proceeds with whichever occurs first.
 * Other events are ignored. Useful for payment timeouts, user approvals, etc.
 *
 * <h3>Key Features:</h3>
 * <ul>
 *   <li>Wait for multiple event types</li>
 *   <li>First event wins, others ignored</li>
 *   <li>Time-based triggers (timeout)</li>
 *   <li>Event-based triggers (signals, webhooks)</li>
 *   <li>Returns event name + data</li>
 * </ul>
 *
 * <h3>DSL Example - Payment Flow:</h3>
 * <pre>
 * eventGatewayTask("await-payment") {
 *     on "payment-received" trigger { webhook ->
 *         log.info("Payment received: ${webhook.amount}")
 *         return [status: "paid", data: webhook]
 *     }
 *     
 *     on "payment-timeout" after 24.hours {
 *         log.warn("Payment timeout")
 *         return [status: "timeout"]
 *     }
 *     
 *     on "payment-cancelled" trigger { notification ->
 *         log.info("Payment cancelled by user")
 *         return [status: "cancelled", reason: notification.reason]
 *     }
 * }
 * 
 * // Route based on result
 * fork("payment-routing") {
 *     from "await-payment"
 *     
 *     when { it.status == "paid" } to "fulfill-order"
 *     when { it.status == "timeout" } to "cancel-order"
 *     when { it.status == "cancelled" } to "notify-customer"
 * }
 * </pre>
 *
 * <h3>DSL Example - Approval Process:</h3>
 * <pre>
 * eventGatewayTask("await-approval") {
 *     on "manager-approved" trigger { approval ->
 *         return [approved: true, by: approval.managerId]
 *     }
 *     
 *     on "manager-rejected" trigger { rejection ->
 *         return [approved: false, reason: rejection.reason]
 *     }
 *     
 *     on "approval-timeout" after 48.hours {
 *         return [approved: false, reason: "timeout"]
 *     }
 * }
 * </pre>
 *
 * <h3>Event Triggering:</h3>
 * <pre>
 * // From external code:
 * EventGatewayTask.triggerEvent("payment-received", paymentData)
 * 
 * // From within task:
 * ctx.globals.eventBus.publish("payment-received", paymentData)
 * </pre>
 */
@Slf4j
class EventGatewayTask extends TaskBase<Map> {

    // =========================================================================
    // Configuration
    // =========================================================================
    
    /** List of event triggers */
    private List<EventTrigger> triggers = []
    
    /**
     * Event trigger definition
     */
    static class EventTrigger {
        String eventName
        Closure handler  // For event-based triggers
        Duration timeout  // For time-based triggers
        
        EventTrigger(String eventName) {
            this.eventName = eventName
        }
    }
    
    // =========================================================================
    // Static Event Bus (Simple Implementation)
    // =========================================================================
    
    /** Global event listeners registry */
    private static final ConcurrentHashMap<String, List<EventListener>> EVENT_LISTENERS = new ConcurrentHashMap<>()
    
    /**
     * Trigger an event from external code.
     * All waiting EventGatewayTasks listening for this event will be notified.
     */
    static void triggerEvent(String eventName, Object eventData = null) {
        log.debug("EventGatewayTask: triggering event '$eventName' with data: ${eventData?.getClass()?.simpleName}")
        
        def listeners = EVENT_LISTENERS.get(eventName)
        if (listeners) {
            // Notify all listeners
            listeners.each { listener ->
                try {
                    listener.onEvent(eventData)
                } catch (Exception e) {
                    log.error("EventGatewayTask: error notifying listener for event '$eventName'", e)
                }
            }
        } else {
            log.debug("EventGatewayTask: no listeners for event '$eventName'")
        }
    }
    
    /**
     * Internal event listener interface
     */
    private static interface EventListener {
        void onEvent(Object eventData)
    }
    
    // =========================================================================
    // Instance State
    // =========================================================================
    
    private CountDownLatch eventLatch
    private volatile String firedEvent
    private volatile Object eventData
    private volatile boolean completed = false
    private List<Thread> timeoutThreads = []

    EventGatewayTask(String id, String name, TaskContext ctx) {
        super(id, name, ctx)
    }

    // =========================================================================
    // DSL Methods
    // =========================================================================
    
    /**
     * Define an event or timeout trigger.
     *
     * Usage:
     *   on "event-name" trigger { data -> ... }
     *   on "timeout-name" after 30.seconds { ... }
     */
    EventTriggerBuilder on(String eventName) {
        return new EventTriggerBuilder(this, eventName)
    }

    /**
     * Legacy method for backward compatibility
     */
    void on(String eventName, Map args) {
        def trigger = new EventTrigger(eventName)

        if (args.trigger) {
            // Event-based trigger
            trigger.handler = args.trigger

        } else if (args.after) {
            // Time-based trigger
            trigger.timeout = args.after
            trigger.handler = args.trigger ?: { [:] }  // Default empty handler

        } else {
            throw new IllegalArgumentException(
                "EventGatewayTask($id): 'on' requires either 'trigger' or 'after'"
            )
        }

        triggers << trigger
    }

    /**
     * Builder for fluent DSL syntax
     */
    static class EventTriggerBuilder {
        private EventGatewayTask task
        private String eventName

        EventTriggerBuilder(EventGatewayTask task, String eventName) {
            this.task = task
            this.eventName = eventName
        }

        /**
         * Event-based trigger: on "event" trigger { data -> ... }
         */
        void trigger(Closure handler) {
            def trigger = new EventTrigger(eventName)
            trigger.handler = handler
            task.triggers << trigger
        }

        /**
         * Time-based trigger: on "event" after Duration.ofSeconds(30) { ... }
         */
        void after(Duration duration, Closure handler = null) {
            def trigger = new EventTrigger(eventName)
            trigger.timeout = duration
            trigger.handler = handler ?: { [:] }  // Default empty handler
            task.triggers << trigger
        }
    }

    // =========================================================================
    // Task Execution
    // =========================================================================

    @Override
    protected Promise<Map> runTask(TaskContext ctx, Object prevValue) {
        
        log.debug("EventGatewayTask($id): waiting for first of ${triggers.size()} events")
        
        // Validate configuration
        validateConfiguration()
        
        // Create latch (count = 1, since first event wins)
        eventLatch = new CountDownLatch(1)
        
        // Register event listeners
        registerEventListeners()
        
        // Start timeout threads
        startTimeoutThreads()
        
        // Wait for first event in async context
        return ctx.promiseFactory.executeAsync {
            try {
                log.debug("EventGatewayTask($id): awaiting first event...")
                eventLatch.await()
                
                // Mark as completed
                completed = true
                
                // Cancel remaining timeouts
                cancelTimeoutThreads()
                
                // Unregister event listeners
                unregisterEventListeners()
                
                log.info("EventGatewayTask($id): event '$firedEvent' occurred first")
                
                // Return result
                return [
                    event: firedEvent,
                    data: eventData
                ]
                
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt()
                throw new RuntimeException("EventGatewayTask($id): interrupted while waiting", e)
            }
        }
    }
    
    private void validateConfiguration() {
        if (triggers.isEmpty()) {
            throw new IllegalStateException("EventGatewayTask($id): requires at least one event trigger")
        }
    }
    
    private void registerEventListeners() {
        triggers.each { trigger ->
            if (trigger.handler && !trigger.timeout) {
                // Event-based trigger - register listener
                log.debug("EventGatewayTask($id): registering listener for event '${trigger.eventName}'")
                
                def listener = new EventListener() {
                    @Override
                    void onEvent(Object data) {
                        handleEvent(trigger.eventName, trigger.handler, data)
                    }
                }
                
                EVENT_LISTENERS.computeIfAbsent(trigger.eventName, { k ->
                    Collections.synchronizedList([])
                }).add(listener)
            }
        }
    }
    
    private void unregisterEventListeners() {
        triggers.each { trigger ->
            if (!trigger.timeout) {
                def listeners = EVENT_LISTENERS.get(trigger.eventName)
                if (listeners) {
                    // Remove all listeners for this task
                    // Note: In production, would need to track specific listener references
                    listeners.clear()
                }
            }
        }
    }
    
    private void startTimeoutThreads() {
        triggers.each { trigger ->
            if (trigger.timeout) {
                // Time-based trigger - start timeout thread
                log.debug("EventGatewayTask($id): starting timeout for '${trigger.eventName}' (${trigger.timeout.toMillis()}ms)")
                
                def timeoutThread = Thread.startVirtualThread {
                    try {
                        Thread.sleep(trigger.timeout.toMillis())
                        handleEvent(trigger.eventName, trigger.handler, null)
                    } catch (InterruptedException e) {
                        // Timeout cancelled
                        log.debug("EventGatewayTask($id): timeout '${trigger.eventName}' cancelled")
                    }
                }
                
                timeoutThreads << timeoutThread
            }
        }
    }
    
    private void cancelTimeoutThreads() {
        timeoutThreads.each { thread ->
            if (thread.isAlive()) {
                thread.interrupt()
            }
        }
        timeoutThreads.clear()
    }
    
    /**
     * Handle an event or timeout occurrence.
     * Only the first event to fire is processed.
     */
    private synchronized void handleEvent(String eventName, Closure handler, Object data) {
        if (completed) {
            log.debug("EventGatewayTask($id): ignoring event '$eventName' (already completed)")
            return
        }
        
        log.info("EventGatewayTask($id): event '$eventName' fired")
        
        try {
            // Execute handler
            def result = handler ? handler.call(data) : data
            
            // Store result
            firedEvent = eventName
            eventData = result
            
            // Count down latch (wakes up waiting thread)
            eventLatch.countDown()
            
        } catch (Exception e) {
            log.error("EventGatewayTask($id): error in event handler for '$eventName'", e)
            
            // Still count down to prevent hanging
            firedEvent = eventName
            eventData = [error: e.message]
            eventLatch.countDown()
        }
    }
}
