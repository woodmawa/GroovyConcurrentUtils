# Observability & Monitoring Enhancement Plan

## Current State Analysis

### ✅ What Already Exists:

1. **Basic Event System:**
   - `TaskEvent` - captures task state changes (SCHEDULED, RUNNING, COMPLETED, FAILED)
   - `TaskListener` interface - for listening to events
   - `TaskEventDispatch` interface - for emitting events
   - `DefaultTaskEventDispatcher` - routes events to TaskGraph
   - `ClusterTaskEvent` - serializable events for distributed monitoring

2. **ResourceMonitor:**
   - Tracks concurrent tasks, queued tasks, memory usage
   - Atomic counters for metrics
   - Callback support via `notifyLimitExceeded()`

3. **ExecutionSummary:**
   - File processing metrics (for FileTask)
   - Custom metrics map
   - Duration tracking

### ❌ Gaps for Production Monitoring:

1. **No listener registration system** - `TaskGraph.notifyEvent()` just logs, doesn't broadcast
2. **No timing/metrics on events** - Missing execution time, wait time, retry count
3. **No graph-level metrics** - Total execution time, completion rate, bottlenecks
4. **No OpenTelemetry/Prometheus integration** - No standard metrics export
5. **No health check API** - Can't query graph health status
6. **No listener lifecycle management** - Can't add/remove listeners dynamically

---

## Recommended Approach: Lightweight Listener Architecture

**Philosophy:** Keep library lean, provide rich hooks for external monitoring apps.

### Core Enhancements Needed:

## 1. Enhanced TaskEvent with Metrics

```groovy
class TaskEvent {
    final String taskId
    final String taskName
    final TaskState taskState
    final Throwable error

    // NEW: Timing and metrics
    final Instant timestamp
    final Long executionTimeMs      // Time spent executing
    final Long waitTimeMs           // Time spent waiting for predecessors
    final Integer attemptNumber     // Retry attempt (1 = first attempt)
    final Map<String, Object> metadata  // Extensible metadata

    // NEW: Context
    final String graphId
    final String runId              // Unique per execution
}
```

## 2. Graph-Level Event Types

```groovy
enum GraphEventType {
    GRAPH_STARTED,
    GRAPH_COMPLETED,
    GRAPH_FAILED,
    GRAPH_CANCELLED,
    TASK_STARTED,
    TASK_COMPLETED,
    TASK_FAILED,
    TASK_RETRYING,
    TASK_SKIPPED,
    RESOURCE_WARNING,   // Memory/concurrency warnings
    CIRCUIT_OPENED,     // Circuit breaker triggered
    RATE_LIMITED,       // Rate limit hit
    DLQ_ENTRY_ADDED     // Task captured to DLQ
}

class GraphEvent {
    final GraphEventType type
    final String graphId
    final String runId
    final Instant timestamp
    final TaskEvent taskEvent       // Optional - for task-level events
    final Map<String, Object> metrics
}
```

## 3. Listener Registration System

```groovy
// In TaskGraph
class TaskGraph {

    private final List<GraphEventListener> listeners = []
    private final CopyOnWriteArrayList<GraphEventListener> concurrentListeners = []

    // Register listener
    void addListener(GraphEventListener listener) {
        concurrentListeners.add(listener)
    }

    void removeListener(GraphEventListener listener) {
        concurrentListeners.remove(listener)
    }

    // Emit events to all listeners
    private void notifyListeners(GraphEvent event) {
        concurrentListeners.each { listener ->
            try {
                listener.onEvent(event)
            } catch (Exception e) {
                log.error("Listener failed: ${listener.class.simpleName}", e)
            }
        }
    }

    // DSL for registering listeners
    void onEvent(@DelegatesTo(ListenerBuilder) Closure config) {
        def builder = new ListenerBuilder()
        config.delegate = builder
        config.call()
        addListener(builder.build())
    }
}

// Listener interface
interface GraphEventListener {
    void onEvent(GraphEvent event)

    // Optional: filter events
    default boolean accepts(GraphEvent event) {
        return true
    }
}
```

## 4. Built-in Listener Implementations

### MetricsCollector (for Prometheus/Micrometer)
```groovy
class MetricsCollector implements GraphEventListener {
    private final MeterRegistry registry

    // Counters
    private Counter tasksStarted
    private Counter tasksCompleted
    private Counter tasksFailed

    // Timers
    private Timer taskExecutionTime
    private Timer graphExecutionTime

    // Gauges
    private AtomicInteger runningTasks

    @Override
    void onEvent(GraphEvent event) {
        switch(event.type) {
            case TASK_STARTED:
                tasksStarted.increment()
                runningTasks.incrementAndGet()
                break
            case TASK_COMPLETED:
                tasksCompleted.increment()
                runningTasks.decrementAndGet()
                taskExecutionTime.record(event.metrics.executionTimeMs, TimeUnit.MILLISECONDS)
                break
            // ... etc
        }
    }
}
```

### LoggingListener (structured logging)
```groovy
class LoggingListener implements GraphEventListener {
    @Override
    void onEvent(GraphEvent event) {
        log.info("graph_event", [
            type: event.type,
            graph: event.graphId,
            run: event.runId,
            task: event.taskEvent?.taskId,
            metrics: event.metrics
        ])
    }
}
```

### OpenTelemetryListener (distributed tracing)
```groovy
class OpenTelemetryListener implements GraphEventListener {
    private final Tracer tracer
    private final Map<String, Span> activeSpans = [:]

    @Override
    void onEvent(GraphEvent event) {
        switch(event.type) {
            case GRAPH_STARTED:
                def span = tracer.spanBuilder("graph.execute")
                    .setAttribute("graph.id", event.graphId)
                    .startSpan()
                activeSpans[event.runId] = span
                break

            case TASK_STARTED:
                def parent = activeSpans[event.runId]
                def taskSpan = tracer.spanBuilder("task.execute")
                    .setParent(Context.current().with(parent))
                    .setAttribute("task.id", event.taskEvent.taskId)
                    .startSpan()
                activeSpans["${event.runId}:${event.taskEvent.taskId}"] = taskSpan
                break

            case TASK_COMPLETED:
                def taskSpan = activeSpans.remove("${event.runId}:${event.taskEvent.taskId}")
                taskSpan?.end()
                break

            case GRAPH_COMPLETED:
                def span = activeSpans.remove(event.runId)
                span?.end()
                break
        }
    }
}
```

## 5. Health Check API

```groovy
// In TaskGraph
class TaskGraph {

    HealthStatus getHealth() {
        return new HealthStatus(
            graphId: this.id,
            state: this.state,
            totalTasks: tasks.size(),
            completedTasks: tasks.values().count { it.isCompleted() },
            failedTasks: tasks.values().count { it.isFailed() },
            runningTasks: tasks.values().count { it.state == TaskState.RUNNING },
            resourceUsage: ctx.resourceMonitor?.getSnapshot(),
            startTime: this.startTime,
            duration: System.currentTimeMillis() - (startTime?.toEpochMilli() ?: 0)
        )
    }

    // Snapshot of current execution
    ExecutionSnapshot getSnapshot() {
        return new ExecutionSnapshot(
            health: getHealth(),
            taskStates: tasks.collectEntries { id, task ->
                [id, new TaskSnapshot(
                    id: task.id,
                    name: task.name,
                    state: task.state,
                    error: task.error?.message,
                    predecessors: task.predecessors,
                    successors: task.successors
                )]
            },
            metrics: collectMetrics()
        )
    }
}

@Canonical
class HealthStatus {
    String graphId
    GraphState state
    int totalTasks
    int completedTasks
    int failedTasks
    int runningTasks
    ResourceSnapshot resourceUsage
    Instant startTime
    long duration

    boolean isHealthy() {
        return failedTasks == 0 &&
               (state == GraphState.COMPLETED || state == GraphState.RUNNING)
    }
}
```

## 6. DSL Integration

```groovy
// User-facing DSL
def workflow = TaskGraph.build {

    // Register listeners during graph construction
    onEvent {
        task { event ->
            if (event.type == TASK_FAILED) {
                sendAlert(event)
            }
        }

        graph { event ->
            if (event.type == GRAPH_COMPLETED) {
                recordMetrics(event)
            }
        }
    }

    // Or register pre-built listeners
    addListener(new MetricsCollector(registry))
    addListener(new OpenTelemetryListener(tracer))
    addListener(new LoggingListener())

    // Tasks...
    serviceTask("api-call") {
        action { ctx, prev -> callApi() }
    }
}

// External monitoring can also register
workflow.addListener(myCustomMonitor)
```

---

## External Monitoring App Integration

### Example: Standalone TaskGraph Monitor

```groovy
class TaskGraphMonitor {

    private final Map<String, GraphExecution> activeGraphs = [:]
    private final MeterRegistry metrics

    void attach(TaskGraph graph) {
        // Register comprehensive listener
        graph.addListener(new MonitoringListener(graph.id, this))

        // Track this graph
        activeGraphs[graph.id] = new GraphExecution(
            graph: graph,
            startTime: Instant.now()
        )
    }

    // REST API endpoints
    @Get("/health")
    HealthStatus getOverallHealth() {
        return new HealthStatus(
            totalGraphs: activeGraphs.size(),
            runningGraphs: activeGraphs.count { it.value.isRunning() },
            metrics: collectAggregateMetrics()
        )
    }

    @Get("/graphs/{id}/snapshot")
    ExecutionSnapshot getGraphSnapshot(String id) {
        return activeGraphs[id]?.graph?.getSnapshot()
    }

    @Get("/graphs/{id}/tasks/{taskId}")
    TaskSnapshot getTaskDetails(String id, String taskId) {
        return activeGraphs[id]?.graph?.tasks[taskId]?.getSnapshot()
    }
}
```

### Grafana/Prometheus Integration

```groovy
// Expose metrics endpoint
class PrometheusExporter {
    private final PrometheusMeterRegistry registry

    PrometheusExporter() {
        registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
    }

    String scrape() {
        return registry.scrape()
    }
}

// HTTP endpoint for Prometheus to scrape
@Get("/metrics")
String getMetrics() {
    return prometheusExporter.scrape()
}
```

---

## Implementation Priority

### Phase 1: Core Infrastructure (Critical)
1. ✅ Enhanced TaskEvent with timing/metadata
2. ✅ GraphEvent and GraphEventType
3. ✅ Listener registration in TaskGraph
4. ✅ GraphEventListener interface

### Phase 2: Built-in Listeners (High Value)
1. ✅ MetricsCollector (Prometheus/Micrometer)
2. ✅ LoggingListener (structured logging)
3. ✅ OpenTelemetryListener (tracing)

### Phase 3: Health & Observability API (Production)
1. ✅ HealthStatus and getHealth()
2. ✅ ExecutionSnapshot and getSnapshot()
3. ✅ ResourceSnapshot from ResourceMonitor

### Phase 4: DSL & Examples (Documentation)
1. ✅ DSL for listener registration
2. ✅ Example monitoring app
3. ✅ Grafana dashboard examples

---

## Benefits of This Approach

### Library Stays Lean:
- Core library just provides hooks and basic listeners
- No heavyweight monitoring dependencies
- Users opt-in to what they need

### Flexible Integration:
- Works with Prometheus, Grafana, OpenTelemetry, custom dashboards
- Listeners can be added/removed dynamically
- Filter events based on needs

### Production-Ready:
- Structured events with full context
- Thread-safe listener registration
- Error isolation (listener failures don't break graph)

### Developer-Friendly:
- Simple DSL for common cases
- Pre-built listeners for standard tools
- Rich snapshot API for debugging

---

## Example: Complete Monitoring Setup

```groovy
// Production workflow with full observability
def workflow = TaskGraph.build {

    // Metrics for Prometheus
    addListener(new MetricsCollector(meterRegistry))

    // Distributed tracing
    addListener(new OpenTelemetryListener(tracer))

    // Custom alerting
    onEvent {
        task { event ->
            if (event.type == TASK_FAILED && event.taskEvent.attemptNumber >= 3) {
                pagerDuty.alert("Task ${event.taskEvent.taskId} failed after 3 retries")
            }
        }

        resource { event ->
            if (event.type == RESOURCE_WARNING) {
                slack.notify("#ops", "Memory warning: ${event.metrics.usedMemoryMB}MB")
            }
        }
    }

    // Tasks...
    serviceTask("critical-api") {
        retry { maxAttempts 3 }
        timeout Duration.ofSeconds(30)
        action { ctx, prev -> callCriticalApi() }
    }
}

// Execute and monitor
def future = workflow.executeAsync(input)

// Health check endpoint
app.get("/health") {
    workflow.getHealth().toJson()
}

// Detailed snapshot for debugging
app.get("/snapshot") {
    workflow.getSnapshot().toJson()
}
```

This approach gives you **enterprise-grade observability** while keeping the library **focused and lean**. External monitoring apps can consume events and build rich dashboards without bloating the core library.
