package org.softwood.agent

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * AgentPool<T> – manages a pool of agents for load distribution.
 *
 * <p>Provides round-robin dispatch, broadcast, and aggregated metrics
 * across multiple agents managing the same type of state.</p>
 *
 * <h2>Features</h2>
 * <ul>
 *   <li>Round-robin task distribution across agents</li>
 *   <li>Broadcast operations to all agents</li>
 *   <li>Aggregated health and metrics</li>
 *   <li>Coordinated lifecycle management</li>
 * </ul>
 *
 * <h2>Usage Example</h2>
 * <pre>
 * // Create a pool of 4 agents
 * def pool = AgentPoolFactory.create(4) { [count: 0] }
 *
 * // Dispatch tasks (round-robin)
 * pool.send { count++ }
 *
 * // Broadcast to all agents
 * pool.broadcast { count = 0 }
 *
 * // Get aggregated metrics
 * def metrics = pool.metrics()
 * </pre>
 */
@Slf4j
@CompileStatic
class AgentPool<T> {

    /** Pool of agents */
    private final List<Agent<T>> agents

    /** Round-robin index */
    private final AtomicInteger roundRobinIndex = new AtomicInteger(0)

    /** Pool creation timestamp */
    private final long createdAt = System.currentTimeMillis()

    /** Total dispatches through pool */
    private final AtomicLong totalDispatches = new AtomicLong(0)

    /** Total broadcasts */
    private final AtomicLong totalBroadcasts = new AtomicLong(0)

    /**
     * Creates an agent pool with the given agents.
     *
     * @param agents list of agents to pool
     */
    AgentPool(List<Agent<T>> agents) {
        if (agents == null || agents.isEmpty()) {
            throw new IllegalArgumentException("Agent pool must have at least one agent")
        }
        this.agents = Collections.unmodifiableList(new ArrayList<>(agents))
    }

    // ----------------------------------------------------------------------
    // Dispatch Operations
    // ----------------------------------------------------------------------

    /**
     * Dispatches a task to the next agent in round-robin fashion.
     *
     * @param action closure to execute
     * @return this pool for chaining
     */
    AgentPool<T> send(Closure action) {
        Agent<T> agent = nextAgent()
        agent.send(action)
        totalDispatches.incrementAndGet()
        return this
    }

    /**
     * Dispatches a task synchronously to the next agent.
     *
     * @param action closure returning a result
     * @param timeoutSeconds optional timeout
     * @return result from the closure
     */
    def <R> R sendAndGet(Closure<R> action, long timeoutSeconds = 0L) {
        Agent<T> agent = nextAgent()
        totalDispatches.incrementAndGet()
        return agent.sendAndGet(action, timeoutSeconds)
    }

    /**
     * Broadcasts a task to all agents in the pool.
     *
     * @param action closure to execute on all agents
     * @return this pool for chaining
     */
    AgentPool<T> broadcast(Closure action) {
        agents.each { agent ->
            agent.send(action)
        }
        totalBroadcasts.incrementAndGet()
        return this
    }

    /**
     * Broadcasts a task to all agents and waits for all to complete.
     *
     * @param action closure to execute on all agents
     * @param timeoutSeconds timeout per agent
     * @return list of results from each agent
     */
    List<Object> broadcastAndGet(Closure action, long timeoutSeconds = 0L) {
        def results = agents.collect { agent ->
            agent.sendAndGet(action, timeoutSeconds)
        }
        totalBroadcasts.incrementAndGet()
        return results
    }

    /**
     * Alias for {@link #send}.
     */
    AgentPool<T> async(Closure c) { send(c) }

    /**
     * Alias for {@link #sendAndGet}.
     */
    def <R> R sync(Closure<R> c, long t = 0) { sendAndGet(c, t) }

    /**
     * Operator: pool >> closure
     */
    def rightShift(Closure c) { async(c) }

    /**
     * Operator: pool << closure
     */
    def <R> R leftShift(Closure<R> c) { sync(c) }

    // ----------------------------------------------------------------------
    // State Access
    // ----------------------------------------------------------------------

    /**
     * Returns snapshots from all agents in the pool.
     *
     * @return list of state snapshots
     */
    List<T> getAllValues() {
        return agents.collect { it.getValue() }
    }

    /**
     * Returns snapshot from a specific agent by index.
     *
     * @param index agent index (0-based)
     * @return state snapshot
     */
    T getValue(int index) {
        if (index < 0 || index >= agents.size()) {
            throw new IndexOutOfBoundsException("Agent index $index out of bounds (size=${agents.size()})")
        }
        return agents[index].getValue()
    }

    // ----------------------------------------------------------------------
    // Pool Management
    // ----------------------------------------------------------------------

    /**
     * Returns the number of agents in the pool.
     */
    int size() {
        return agents.size()
    }

    /**
     * Returns a specific agent by index.
     */
    Agent<T> getAgent(int index) {
        if (index < 0 || index >= agents.size()) {
            throw new IndexOutOfBoundsException("Agent index $index out of bounds (size=${agents.size()})")
        }
        return agents[index]
    }

    /**
     * Returns all agents in the pool (read-only).
     */
    List<Agent<T>> getAgents() {
        return agents
    }

    /**
     * Selects the next agent using round-robin.
     */
    private Agent<T> nextAgent() {
        int index = Math.abs(roundRobinIndex.getAndIncrement() % agents.size())
        return agents[index]
    }

    // ----------------------------------------------------------------------
    // Error Management
    // ----------------------------------------------------------------------

    /**
     * Sets an error handler on all agents in the pool.
     *
     * @param errorHandler closure to handle errors
     * @return this pool for chaining
     */
    AgentPool<T> onError(Closure<Void> errorHandler) {
        agents.each { it.onError(errorHandler) }
        return this
    }

    /**
     * Returns all errors from all agents in the pool.
     *
     * @param maxPerAgent maximum errors per agent
     * @return map of agent index to error list
     */
    Map<Integer, List<Map<String, Object>>> getAllErrors(int maxPerAgent = Integer.MAX_VALUE) {
        Map<Integer, List<Map<String, Object>>> errors = new LinkedHashMap<>()
        agents.eachWithIndex { agent, index ->
            def agentErrors = agent.getErrors(maxPerAgent)
            if (!agentErrors.isEmpty()) {
                errors[index] = agentErrors
            }
        }
        return errors
    }

    /**
     * Clears errors on all agents.
     */
    void clearAllErrors() {
        agents.each { it.clearErrors() }
    }

    // ----------------------------------------------------------------------
    // Configuration
    // ----------------------------------------------------------------------

    /**
     * Sets max queue size on all agents.
     */
    void setMaxQueueSize(int max) {
        agents.each { it.setMaxQueueSize(max) }
    }

    // ----------------------------------------------------------------------
    // Lifecycle
    // ----------------------------------------------------------------------

    /**
     * Shuts down all agents gracefully.
     */
    void shutdown() {
        agents.each { it.shutdown() }
    }

    /**
     * Shuts down all agents with timeout.
     *
     * @return true if all agents shut down within timeout
     */
    boolean shutdown(long timeout, TimeUnit unit) {
        long deadline = System.currentTimeMillis() + unit.toMillis(timeout)
        
        for (Agent<T> agent : agents) {
            long remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) {
                return false
            }
            
            if (!agent.shutdown(remaining, TimeUnit.MILLISECONDS)) {
                return false
            }
        }
        
        return true
    }

    /**
     * Forcefully shuts down all agents.
     */
    void shutdownNow() {
        agents.each { it.shutdownNow() }
    }

    /**
     * Returns true if all agents have initiated shutdown.
     */
    boolean isShutdown() {
        return agents.every { it.isShutdown() }
    }

    /**
     * Returns true if all agents have terminated.
     */
    boolean isTerminated() {
        return agents.every { it.isTerminated() }
    }

    // ----------------------------------------------------------------------
    // Health & Metrics
    // ----------------------------------------------------------------------

    /**
     * Returns aggregated health status for the pool.
     */
    Map<String, Object> health() {
        def agentHealths = agents.collect { it.health() }
        
        // Determine overall status
        def statuses = agentHealths*.status as List<String>
        String overallStatus = "HEALTHY"
        if (statuses.contains("SHUTTING_DOWN")) {
            overallStatus = "SHUTTING_DOWN"
        } else if (statuses.contains("DEGRADED")) {
            overallStatus = "DEGRADED"
        }
        
        // Aggregate metrics
        int totalQueueSize = agentHealths.sum { it.queueSize as Integer } as Integer
        int agentsProcessing = agentHealths.count { it.processing } as Integer
        int agentsShuttingDown = agentHealths.count { it.shuttingDown } as Integer
        int totalErrors = agentHealths.sum { it.recentErrorCount as Integer } as Integer
        
        return [
                status: overallStatus,
                poolSize: agents.size(),
                agentsShuttingDown: agentsShuttingDown,
                agentsProcessing: agentsProcessing,
                totalQueueSize: totalQueueSize,
                totalRecentErrors: totalErrors,
                agentHealths: agentHealths,
                timestamp: System.currentTimeMillis()
        ]
    }

    /**
     * Returns aggregated metrics for the pool.
     */
    Map<String, Object> metrics() {
        def agentMetrics = agents.collect { it.metrics() }
        
        // Aggregate counters
        long totalSubmitted = agentMetrics.sum { it.tasksSubmitted as Long } as Long
        long totalCompleted = agentMetrics.sum { it.tasksCompleted as Long } as Long
        long totalErrored = agentMetrics.sum { it.tasksErrored as Long } as Long
        long totalRejections = agentMetrics.sum { it.queueRejections as Long } as Long
        int totalQueueDepth = agentMetrics.sum { it.queueDepth as Integer } as Integer
        
        long now = System.currentTimeMillis()
        long uptime = now - createdAt
        
        // Calculate pool-level metrics
        double poolThroughput = uptime > 0 ? (totalCompleted * 1000.0 / uptime) : 0.0
        double avgErrorRate = totalCompleted > 0 ? (totalErrored * 100.0 / totalCompleted) : 0.0
        
        return [
                // Pool-level
                poolSize: agents.size(),
                totalDispatches: totalDispatches.get(),
                totalBroadcasts: totalBroadcasts.get(),
                poolUptimeMs: uptime,
                poolThroughputPerSec: poolThroughput,
                
                // Aggregated agent metrics
                totalTasksSubmitted: totalSubmitted,
                totalTasksCompleted: totalCompleted,
                totalTasksErrored: totalErrored,
                totalQueueRejections: totalRejections,
                totalQueueDepth: totalQueueDepth,
                avgErrorRatePercent: avgErrorRate,
                
                // Per-agent breakdown
                agentMetrics: agentMetrics,
                
                timestamp: now
        ]
    }
}
