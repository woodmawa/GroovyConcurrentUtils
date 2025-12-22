package org.softwood.dag.task

import groovy.util.logging.Slf4j
import org.softwood.promise.Promise

import java.time.Duration
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeoutException

/**
 * SignalTask - Event Coordination Task
 *
 * Enables event-driven workflows by allowing tasks to wait for and emit signals.
 * Signals can carry data payloads and be used for synchronization between
 * parallel workflow branches or external event integration.
 *
 * <h3>Signal Types:</h3>
 * <ul>
 *   <li><b>WAIT</b> - Wait for a signal to arrive (blocking)</li>
 *   <li><b>SEND</b> - Send a signal with optional payload (non-blocking)</li>
 *   <li><b>WAIT_ALL</b> - Wait for multiple signals (barrier pattern)</li>
 *   <li><b>WAIT_ANY</b> - Wait for any one of multiple signals (race pattern)</li>
 * </ul>
 *
 * <h3>Use Cases:</h3>
 * <ul>
 *   <li>Wait for external events (webhooks, API callbacks)</li>
 *   <li>Synchronize parallel workflow branches</li>
 *   <li>Implement barriers and checkpoints</li>
 *   <li>Event-driven task execution</li>
 *   <li>Pub/sub patterns within workflows</li>
 * </ul>
 *
 * <h3>DSL Example - Basic Wait/Send:</h3>
 * <pre>
 * // Task that waits for approval signal
 * task("wait-approval", TaskType.SIGNAL) {
 *     mode SignalMode.WAIT
 *     signalName "approval-granted"
 *     timeout Duration.ofMinutes(30)
 * }
 *
 * // Task that sends approval signal
 * task("send-approval", TaskType.SIGNAL) {
 *     mode SignalMode.SEND
 *     signalName "approval-granted"
 *     payload { ctx, prev -> [approved: true, approver: "admin"] }
 * }
 * </pre>
 *
 * <h3>DSL Example - Wait for Multiple Signals:</h3>
 * <pre>
 * task("wait-both", TaskType.SIGNAL) {
 *     mode SignalMode.WAIT_ALL
 *     signalNames "payment-received", "shipment-ready"
 *     timeout Duration.ofHours(24)
 * }
 * </pre>
 *
 * <h3>Programmatic Signal Sending:</h3>
 * <pre>
 * // Send signal from external source
 * SignalTask.sendSignal("approval-granted", [approved: true])
 * 
 * // Or get specific task and trigger it
 * def signalTask = graph.tasks["wait-approval"] as SignalTask
 * signalTask.receiveSignal([data: "payload"])
 * </pre>
 */
@Slf4j
class SignalTask extends TaskBase<Map> {

    // =========================================================================
    // Global Signal Registry (Shared Across All Tasks)
    // =========================================================================
    
    /**
     * Global registry of pending signals.
     * Key: signal name, Value: list of signal data payloads
     */
    private static final ConcurrentHashMap<String, List<Map>> SIGNAL_REGISTRY = new ConcurrentHashMap<>()
    
    /**
     * Global registry of waiting tasks.
     * Key: signal name, Value: list of tasks waiting for this signal
     */
    private static final ConcurrentHashMap<String, List<SignalTask>> WAITING_TASKS = new ConcurrentHashMap<>()

    // =========================================================================
    // Task Configuration
    // =========================================================================
    
    /** Signal operation mode */
    SignalMode mode = SignalMode.WAIT
    
    /** Signal name to wait for or send */
    String signalName
    
    /** Multiple signal names (for WAIT_ALL or WAIT_ANY) */
    List<String> signalNames = []
    
    /** Timeout for waiting (null = wait forever) */
    Duration timeout
    
    /** Payload to send with signal (closure that produces the payload) */
    Closure payloadProducer
    
    /** Store received signal data */
    Map receivedSignalData
    
    /** Timestamp when signal was received */
    LocalDateTime receivedAt
    
    // =========================================================================
    // Internal State
    // =========================================================================
    
    /** Promise to resolve when signal is received */
    private Promise<Map> signalPromise
    
    /** Timeout timer */
    private Timer timeoutTimer
    
    /** Flag to track if signal was received */
    private volatile boolean signalReceived = false
    
    /** For WAIT_ALL mode: track which signals have been received */
    private final Set<String> receivedSignals = Collections.synchronizedSet(new HashSet<>())

    SignalTask(String id, String name, TaskContext ctx) {
        super(id, name, ctx)
    }

    // =========================================================================
    // DSL Methods
    // =========================================================================
    
    /**
     * Set signal mode.
     */
    void mode(SignalMode value) {
        this.mode = value
    }
    
    /**
     * Set single signal name.
     */
    void signalName(String value) {
        this.signalName = value
    }
    
    /**
     * Set multiple signal names (for WAIT_ALL or WAIT_ANY).
     */
    void signalNames(String... names) {
        this.signalNames = names as List
    }
    
    /**
     * Set timeout duration.
     */
    void timeout(Duration value) {
        this.timeout = value
    }
    
    /**
     * Set payload producer closure.
     * Closure receives (ctx, prevValue) and returns payload Map.
     */
    void payload(Closure producer) {
        this.payloadProducer = producer
    }

    // =========================================================================
    // Task Execution
    // =========================================================================

    @Override
    protected Promise<Map> runTask(TaskContext ctx, Object prevValue) {
        
        log.debug("SignalTask($id): mode=$mode, signal=${signalName ?: signalNames}")
        
        signalPromise = ctx.promiseFactory.createPromise()
        
        switch (mode) {
            case SignalMode.WAIT:
                handleWaitMode()
                break
                
            case SignalMode.SEND:
                handleSendMode(prevValue)
                break
                
            case SignalMode.WAIT_ALL:
                handleWaitAllMode()
                break
                
            case SignalMode.WAIT_ANY:
                handleWaitAnyMode()
                break
                
            default:
                signalPromise.fail(new IllegalStateException("Unknown signal mode: $mode"))
        }
        
        return signalPromise
    }

    // =========================================================================
    // Mode Handlers
    // =========================================================================
    
    private void handleWaitMode() {
        if (!signalName) {
            signalPromise.fail(new IllegalStateException("SignalTask($id): WAIT mode requires signalName"))
            return
        }
        
        log.info("SignalTask($id): waiting for signal '$signalName'")
        
        // Synchronize to prevent race condition between checking registry and registering as waiter
        synchronized (SIGNAL_REGISTRY) {
            // Check if signal already exists
            def existingSignals = SIGNAL_REGISTRY.get(signalName)
            if (existingSignals && !existingSignals.isEmpty()) {
                // Signal already sent - consume it immediately
                def signalData = existingSignals.remove(0)
                if (existingSignals.isEmpty()) {
                    SIGNAL_REGISTRY.remove(signalName)
                }
                receiveSignalData(signalData)
            } else {
                // Register to wait for signal
                WAITING_TASKS.computeIfAbsent(signalName, { k -> Collections.synchronizedList([]) })
                             .add(this)
                
                // Set up timeout if configured
                if (timeout) {
                    scheduleTimeout()
                }
            }
        }
    }
    
    private void handleSendMode(Object prevValue) {
        if (!signalName) {
            signalPromise.fail(new IllegalStateException("SignalTask($id): SEND mode requires signalName"))
            return
        }
        
        // Produce payload
        def payload = [:]
        if (payloadProducer) {
            try {
                payload = payloadProducer.call(ctx, prevValue) as Map
            } catch (Exception e) {
                log.error("SignalTask($id): error producing payload", e)
                signalPromise.fail(e)
                return
            }
        } else {
            // Default: pass through previous value
            payload = [data: prevValue, sentAt: LocalDateTime.now()]
        }
        
        log.info("SignalTask($id): sending signal '$signalName' with payload: ${payload.keySet()}")
        
        // Send signal
        sendSignalGlobal(signalName, payload)
        
        // Complete immediately with confirmation
        signalPromise.accept([
            signalName: signalName,
            payload: payload,
            sentAt: LocalDateTime.now(),
            mode: 'SEND'
        ])
    }
    
    private void handleWaitAllMode() {
        if (!signalNames || signalNames.isEmpty()) {
            signalPromise.fail(new IllegalStateException("SignalTask($id): WAIT_ALL mode requires signalNames"))
            return
        }
        
        log.info("SignalTask($id): waiting for ALL signals: $signalNames")
        
        // Register for each signal
        signalNames.each { name ->
            // Check if signal already exists
            def existingSignals = SIGNAL_REGISTRY.get(name)
            if (existingSignals && !existingSignals.isEmpty()) {
                receivedSignals.add(name)
            } else {
                WAITING_TASKS.computeIfAbsent(name, { k -> Collections.synchronizedList([]) })
                             .add(this)
            }
        }
        
        // Check if all signals already received
        if (receivedSignals.containsAll(signalNames)) {
            completeWaitAll()
        } else if (timeout) {
            scheduleTimeout()
        }
    }
    
    private void handleWaitAnyMode() {
        if (!signalNames || signalNames.isEmpty()) {
            signalPromise.fail(new IllegalStateException("SignalTask($id): WAIT_ANY mode requires signalNames"))
            return
        }
        
        log.info("SignalTask($id): waiting for ANY signal from: $signalNames")
        
        // Check if any signal already exists
        for (String name : signalNames) {
            def existingSignals = SIGNAL_REGISTRY.get(name)
            if (existingSignals && !existingSignals.isEmpty()) {
                // Found existing signal - consume it
                def signalData = existingSignals.remove(0)
                if (existingSignals.isEmpty()) {
                    SIGNAL_REGISTRY.remove(name)
                }
                receiveSignalData(signalData + [matchedSignal: name])
                return
            }
        }
        
        // No existing signals - register for all
        signalNames.each { name ->
            WAITING_TASKS.computeIfAbsent(name, { k -> Collections.synchronizedList([]) })
                         .add(this)
        }
        
        if (timeout) {
            scheduleTimeout()
        }
    }

    // =========================================================================
    // Signal Reception
    // =========================================================================
    
    /**
     * Receive a signal (called by global signal mechanism).
     */
    void receiveSignal(Map signalData) {
        synchronized (this) {
            if (signalReceived) {
                log.debug("SignalTask($id): signal already received, ignoring")
                return
            }
            
            if (mode == SignalMode.WAIT_ALL) {
                // For WAIT_ALL, track which signal was received
                def sigName = signalData.signalName
                receivedSignals.add(sigName)
                
                if (receivedSignals.containsAll(signalNames)) {
                    completeWaitAll()
                }
            } else if (mode == SignalMode.WAIT_ANY) {
                // For WAIT_ANY, add matchedSignal to indicate which one triggered
                receiveSignalData(signalData + [matchedSignal: signalData.signalName])
            } else {
                receiveSignalData(signalData)
            }
        }
    }
    
    private void receiveSignalData(Map signalData) {
        synchronized (this) {
            if (signalReceived) return
            
            signalReceived = true
            receivedSignalData = signalData
            receivedAt = LocalDateTime.now()
            
            cancelTimeout()
            
            // Unregister from waiting lists
            if (mode == SignalMode.WAIT_ANY) {
                signalNames.each { name ->
                    WAITING_TASKS.get(name)?.remove(this)
                }
            } else if (signalName) {
                WAITING_TASKS.get(signalName)?.remove(this)
            }
            
            log.info("SignalTask($id): signal received")
            
            signalPromise.accept([
                signalData: signalData,
                receivedAt: receivedAt,
                mode: mode.name()
            ])
        }
    }
    
    private void completeWaitAll() {
        synchronized (this) {
            if (signalReceived) return
            
            signalReceived = true
            receivedAt = LocalDateTime.now()
            
            cancelTimeout()
            
            // Collect all signal data
            def allSignalData = [:]
            signalNames.each { name ->
                def signals = SIGNAL_REGISTRY.get(name)
                if (signals && !signals.isEmpty()) {
                    allSignalData[name] = signals.remove(0)
                }
                WAITING_TASKS.get(name)?.remove(this)
            }
            
            log.info("SignalTask($id): all signals received")
            
            signalPromise.accept([
                signals: allSignalData,
                receivedSignals: receivedSignals as List,
                receivedAt: receivedAt,
                mode: 'WAIT_ALL'
            ])
        }
    }

    // =========================================================================
    // Timeout Handling
    // =========================================================================
    
    private void scheduleTimeout() {
        log.debug("SignalTask($id): scheduling timeout for ${timeout.toMillis()}ms")
        
        timeoutTimer = new Timer("SignalTask-${id}-Timeout", true)
        timeoutTimer.schedule(new TimerTask() {
            @Override
            void run() {
                handleTimeout()
            }
        }, timeout.toMillis())
    }
    
    private void cancelTimeout() {
        if (timeoutTimer) {
            log.debug("SignalTask($id): cancelling timeout")
            timeoutTimer.cancel()
            timeoutTimer = null
        }
    }
    
    private void handleTimeout() {
        synchronized (this) {
            if (signalReceived) return
            
            signalReceived = true
            
            // Unregister from waiting lists
            if (mode == SignalMode.WAIT_ANY || mode == SignalMode.WAIT_ALL) {
                signalNames.each { name ->
                    WAITING_TASKS.get(name)?.remove(this)
                }
            } else if (signalName) {
                WAITING_TASKS.get(signalName)?.remove(this)
            }
            
            log.warn("SignalTask($id): timeout waiting for signal")
            
            signalPromise.fail(
                new TimeoutException("SignalTask timeout waiting for signal: ${signalName ?: signalNames}")
            )
        }
    }

    // =========================================================================
    // Global Signal Operations
    // =========================================================================
    
    /**
     * Send a signal globally (static method for external use).
     * 
     * @param signalName name of the signal
     * @param payload signal data payload
     */
    static void sendSignalGlobal(String signalName, Map payload = [:]) {
        log.debug("Global signal sent: $signalName")
        
        // Synchronize to prevent race condition with handleWaitMode()
        synchronized (SIGNAL_REGISTRY) {
            // Check if any tasks are waiting for this signal
            def waiters = WAITING_TASKS.get(signalName)
            if (waiters && !waiters.isEmpty()) {
                // Wake up waiting tasks
                def signalData = payload + [signalName: signalName, sentAt: LocalDateTime.now()]
                
                // Make a copy to avoid concurrent modification
                def waitersCopy = new ArrayList(waiters)
                waitersCopy.each { task ->
                    task.receiveSignal(signalData)
                }
            } else {
                // No one waiting - store for later
                SIGNAL_REGISTRY.computeIfAbsent(signalName, { k -> Collections.synchronizedList([]) })
                              .add(payload + [signalName: signalName, sentAt: LocalDateTime.now()])
                
                log.debug("Signal '$signalName' stored for later consumption")
            }
        }
    }
    
    /**
     * Clear all pending signals (useful for testing).
     */
    static void clearAllSignals() {
        SIGNAL_REGISTRY.clear()
        WAITING_TASKS.clear()
        log.debug("All signals cleared")
    }
    
    /**
     * Get pending signal count for a signal name.
     */
    static int getPendingSignalCount(String signalName) {
        def signals = SIGNAL_REGISTRY.get(signalName)
        return signals ? signals.size() : 0
    }
    
    /**
     * Get waiting task count for a signal name.
     */
    static int getWaitingTaskCount(String signalName) {
        def waiters = WAITING_TASKS.get(signalName)
        return waiters ? waiters.size() : 0
    }
}
