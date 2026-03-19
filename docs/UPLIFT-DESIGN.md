# GroovyConcurrentUtils DAG Uplift Design
## Version Target: 2.1.0

Generated: 2026-03-04  
Scope: Five targeted capability uplifts to take the library from "impressive prototype" to "shippable alpha"

---

## Context: What We Already Have

Before describing what's new, key points about the existing foundation:

- **Idempotency** is fully wired as a cross-cutting concern in `TaskBase.executeTaskWithRetry()`. The `IdempotencyCache` / `IdempotencyPolicy` / `IdempotencyDsl` / `IdempotencyKey` classes exist in `dag/resilience/`. The check+store flow is live. **Gap: scope is per-task-input deduplication only — no graph-level or cross-run memoisation of expensive computations.**
- **JoinStrategy enum** exists (`ALL_COMPLETED`, `FIRST_COMPLETED`) but is a stub — `JoinDsl` doesn't read it and `scheduleIfReady()` only implements AND-join (all predecessors complete).
- **Error routing**: on task failure, `TaskGraph` calls `markDownstreamAsSkipped(t)` — there is no `errorSuccessor` field, no `onError {}` DSL, no error channel concept.
- **DataTransformTask** is a functional pipeline (map/filter/reduce/tap) with no external data fetch step.
- **Memoisation** does not exist at task or graph level.

---

## Uplift 1: Memoisation (Graph-Level Result Cache)

### Problem
Idempotency in `TaskBase` prevents *duplicate execution for the same input in the same run*. It does **not** persist results across graph runs or share results between different task instances that do the same work. Expensive computations (DB lookups, ML inferences, external API calls) are repeated on every graph execution.

### Design

#### New file: `dag/resilience/MemoCache.groovy`
A graph-scoped, optionally persistent result store. Distinct from `IdempotencyCache` in intent: idempotency is about safety/correctness; memo is about performance/cost.

```
MemoCache
  - ConcurrentHashMap<String, MemoCacheEntry> store
  - int maxSize (default 500)
  - Duration defaultTtl (default null = session-lived)
  - boolean persist (default false — plugs into EclipseStore when true)
  - Object get(String key)
  - void put(String key, Object value, Duration ttl)
  - void invalidate(String key)
  - void invalidateAll()
  - Map<String,Object> stats()   // hits, misses, size
```

#### New file: `dag/resilience/MemoCacheEntry.groovy`
```
  - String key
  - Object value
  - Instant createdAt
  - Instant expiresAt   // null = no expiry
  - int hitCount
  - boolean isExpired()
```

#### New file: `dag/resilience/MemoPolicy.groovy`
```
  - boolean enabled = false
  - String cacheKey               // static key; if null, derived from taskId + input hash
  - Closure<String> keyFrom       // dynamic key generator: { ctx, input -> String }
  - Duration ttl                  // null = live for graph lifetime
  - boolean crossRun = false      // if true, uses persistent MemoCache
```

#### New file: `dag/resilience/MemoDsl.groovy`
```groovy
void ttl(Duration d)
void keyFrom(Closure c)           // { ctx, input -> key }
void staticKey(String k)
void crossRun(boolean b)          // persist across graph runs
void enable()
```

#### Changes to `TaskBase`
- Add `protected MemoPolicy memoPolicy = new MemoPolicy()`
- Add `void memo(@DelegatesTo(MemoDsl) Closure c)` DSL method (analogous to `idempotent {}`)
- In `executeTaskWithRetry()`, **before** the idempotency check, insert:
```
if (memoPolicy.enabled) {
    def cached = checkMemoCache(prevValue)
    if (cached != null) return cached
}
```
- After `runTask()` returns result, insert:
```
if (memoPolicy.enabled) {
    storeMemo(prevValue, result)
}
```

#### Changes to `TaskContext`
- Add `MemoCache memoCache` field, lazily initialised on first use
- `MemoCache getMemoCache()` — creates one on demand

#### DSL Usage
```groovy
task("lookup-user") {
    memo {
        keyFrom { ctx, input -> "user:${input.userId}" }
        ttl Duration.ofMinutes(10)
    }
    action { ctx, prev -> userService.findById(prev.userId) }
}

// Cross-run persistence (uses EclipseStore when available)
task("ml-inference") {
    memo {
        keyFrom { ctx, input -> "infer:${input.modelVersion}:${input.inputHash}" }
        crossRun true
    }
    action { ctx, prev -> model.infer(prev) }
}
```

#### Relationship to Idempotency
Both use the same lifecycle hook point in `executeTaskWithRetry`. Order of checks:
1. Memo check (performance — skip the work)
2. Idempotency check (correctness — prevent duplicate side effects)
3. Rate limit check
4. `runTask()`

---

## Uplift 2: ContentEnricher Task

### Problem
`DataTransformTask` can transform data in-flight but cannot fetch external data and merge it into the pipeline. The "Content Enricher" pattern (fetch supplementary data, merge into message) requires hand-rolling a `ServiceTask` today.

### Design

#### New file: `dag/task/ContentEnricherTask.groovy`
Extends `TaskBase`. Has a two-phase execution model:

**Phase 1 — Enrich**: fetch supplementary data from an external source  
**Phase 2 — Merge**: merge the enrichment into the original payload

```groovy
class ContentEnricherTask extends TaskBase<Map> {

    // Phase 1: how to fetch the enrichment
    Closure enrichFrom         // { ctx, input -> enrichmentData }  (can return Promise)

    // Phase 2: how to merge enrichment into input
    Closure mergeWith          // { input, enrichment -> mergedResult }

    // Optional: what key to store enrichment under if mergeWith is not provided
    String enrichmentKey = '__enrichment__'

    // Optional: error handling if enrich fails
    Closure onEnrichError      // { ctx, input, error -> fallback }

    // Optional: condition to skip enrichment
    Closure when               // { ctx, input -> boolean }
}
```

#### New file: `dag/task/ContentEnricherDsl.groovy`
```groovy
void enrichFrom(Closure c)     // { ctx, input -> data }
void mergeWith(Closure c)      // { original, enrichment -> merged }
void enrichmentKey(String k)   // if no mergeWith, store enrichment under this key
void onEnrichError(Closure c)  // { ctx, input, error -> fallback }
void when(Closure c)           // conditional enrichment
```

#### Changes to `TaskFactory` / `TaskType`
- Add `CONTENT_ENRICHER` to `TaskType` enum
- Register in `TaskFactory.createTask()`
- Add `enrich {}` DSL method to `TaskGraphDsl`

#### DSL Usage
```groovy
// Simple key injection
task("enrich-user", TaskType.CONTENT_ENRICHER) {
    enrichFrom { ctx, input -> userRepo.findById(input.userId) }
    mergeWith  { original, user ->
        original + [userName: user.name, userEmail: user.email]
    }
    onEnrichError { ctx, input, err ->
        input + [userName: 'Unknown', userEmail: 'n/a']  // fallback
    }
}

// Conditional enrichment
task("maybe-enrich", TaskType.CONTENT_ENRICHER) {
    when       { ctx, input -> input.needsEnrichment }
    enrichFrom { ctx, input -> externalApi.fetch(input.ref) }
    mergeWith  { original, data -> original + data }
}
```

#### Integration with DataTransformTask
`ContentEnricherTask` can be used as a pipeline step before or after `DataTransformTask`, or composed in a `chainVia`:

```groovy
task("start") { action { ctx, _ -> [userId: 42, amount: 100] } }
task("enrich", TaskType.CONTENT_ENRICHER) {
    enrichFrom { ctx, input -> userRepo.find(input.userId) }
    mergeWith  { orig, user -> orig + [name: user.name, tier: user.tier] }
}
task("transform", TaskType.DATA_TRANSFORM) {
    transform { data -> data + [discount: data.tier == 'GOLD' ? 0.2 : 0.0] }
}

"start" >> "enrich" >> "transform"
```

---

## Uplift 3: JoinStrategy — OR and N-of-M Joins

### Problem
`JoinStrategy` enum exists but is a stub. `JoinDsl` ignores it. `scheduleIfReady()` in `TaskGraph` only fires a task when **all** predecessors are complete (AND-join). No OR-join (first-wins) or N-of-M (partial quorum) exists.

### Design

#### Changes to `JoinStategy.groovy` (fix typo → `JoinStrategy.groovy`)
```groovy
enum JoinStrategy {
    ALL_COMPLETED,      // existing AND-join: wait for ALL predecessors (default)
    FIRST_COMPLETED,    // OR-join: fire when ANY predecessor completes
    N_OF_M              // quorum: fire when N out of M predecessors complete
}
```

#### New file: `dag/task/JoinTask.groovy`
A proper first-class task type (rather than a plain `ServiceTask` with wiring). Extends `TaskBase`:

```groovy
class JoinTask extends TaskBase<Object> {
    JoinStrategy strategy = JoinStrategy.ALL_COMPLETED
    int quorum = 0                  // used for N_OF_M: minimum completions needed
    Closure joinAction              // { ctx, List<completedResults> -> result }
    final AtomicInteger completedCount = new AtomicInteger(0)
    final Set<String> completedPredecessors = ConcurrentHashMap.newKeySet()
    final Map<String, Object> collectedResults = new ConcurrentHashMap<>()

    boolean shouldFire()            // called by TaskGraph instead of scheduleIfReady logic
}
```

#### Changes to `JoinDsl.groovy`
```groovy
class JoinDsl {
    void from(String... ids)
    void strategy(JoinStrategy s)           // strategy ALL_COMPLETED
    void strategy(String s)                 // strategy "FIRST_COMPLETED"
    void quorum(int n)                      // quorum 2  (implies N_OF_M)
    void action(Closure c)                  // { ctx, results -> ... }
    void timeout(Duration d)                // fail the join after this time if not fired
}
```

#### Changes to `TaskGraph.scheduleIfReady()`
Currently:
```groovy
// Only fires if ALL predecessors are done
boolean allDone = t.predecessors.every { predId ->
    ITask pred = tasks[predId]
    pred?.isCompleted() || pred?.isSkipped() || pred?.isFailed()
}
```

New logic for `JoinTask`:
```groovy
if (t instanceof JoinTask) {
    JoinTask join = (JoinTask) t
    switch (join.strategy) {
        case JoinStrategy.FIRST_COMPLETED:
            // fire immediately on first predecessor completion (if not already fired)
            if (!join.isScheduled() && !join.isRunning() && !join.isCompleted()) {
                schedule(join)
            }
            break
        case JoinStrategy.N_OF_M:
            int n = join.completedCount.incrementAndGet()
            if (n >= join.quorum && !join.isScheduled() && !join.isRunning()) {
                schedule(join)
            }
            break
        case JoinStrategy.ALL_COMPLETED:
        default:
            // existing logic — only fire when all predecessors done
            scheduleIfAllReady(join)
    }
} else {
    scheduleIfAllReady(t)   // existing logic for non-joins
}
```

#### OR-join: partial result collection
For `FIRST_COMPLETED` and `N_OF_M`, when `joinAction` fires, `prevValue` will be a `Map<String, Object>` of `{ taskId -> result }` for completed predecessors only. Remaining predecessors are left running but their results are ignored for this join trigger.

#### Changes to `onError` in TaskGraph (failure propagation)
Currently failed predecessors cause the join's downstream to be skipped. With join strategies, the join task itself needs to know that a predecessor failed:
- For `ALL_COMPLETED`: existing skip-downstream logic is correct
- For `FIRST_COMPLETED` / `N_OF_M`: a failed predecessor **decrements the available count** and the join fires with remaining completions (or itself fails if quorum can never be reached)

#### DSL Usage
```groovy
// Classic AND-join (default, unchanged)
join("merge-results") {
    from "task-a", "task-b", "task-c"
    action { ctx, results -> results.values().sum() }
}

// OR-join: first result wins
join("fastest-api") {
    from "api-primary", "api-fallback", "api-cache"
    strategy JoinStrategy.FIRST_COMPLETED
    action { ctx, results -> results.values().first() }
}

// Quorum join: 2 of 3 must succeed
join("quorum-check") {
    from "node-a", "node-b", "node-c"
    strategy JoinStrategy.N_OF_M
    quorum 2
    action { ctx, results -> results.values().toList() }
}
```

---

## Uplift 4: Error Routing

### Problem
When a task fails in `TaskGraph`, `markDownstreamAsSkipped(t)` is called — all downstream tasks are skipped and the graph eventually completes as `FAILED`. There is no way to route to a recovery task, compensation branch, or error handler. This is the most critical gap vs Spring Integration/Camel.

### Design

#### Changes to `ITask.groovy` and `TaskBase.groovy`
Add error routing fields:
```groovy
// In TaskBase
String errorSuccessor = null          // task id to route to on failure
Closure onErrorClosure = null         // inline error handler: { ctx, error -> result }
boolean continueOnError = false       // if true + onErrorClosure present, treat as completed
```

Add DSL methods to `TaskBase`:
```groovy
void onError(String taskId)               // route to named task on failure
void onError(Closure c)                   // inline handler: { ctx, error -> recovered }
void continueOnError(boolean b)           // swallow error after onError handler
```

#### Changes to `TaskGraph` — failure handling block
Current (in the `execPromise.onError {}` lambda in `scheduleTask()`):
```groovy
execPromise.onError { error ->
    t.markFailed(error)
    ...
    markDownstreamAsSkipped(t)
    checkGraphCompletion()
}
```

New:
```groovy
execPromise.onError { error ->
    t.markFailed(error)
    persistTaskStateChange(...)
    publishTaskStateToCluster(...)

    // Error routing: if task has an error successor, schedule it
    if (t.errorSuccessor) {
        ITask errTask = tasks[t.errorSuccessor]
        if (errTask) {
            log.info "Task ${t.id} failed, routing to error handler: ${t.errorSuccessor}"
            // Inject error into the error handler's input
            errTask.setInjectedInput([
                failedTaskId: t.id,
                error: error,
                errorMessage: error.message
            ])
            schedule(errTask)
            // DON'T skip downstream of error handler — it runs normally
            checkGraphCompletion()
            return
        }
    }

    // Inline onError closure: run it, optionally treat task as completed
    if (t.onErrorClosure) {
        try {
            def recovered = t.onErrorClosure.call(ctx, error)
            if (t.continueOnError) {
                // Treat as COMPLETED with the recovered value
                t.markCompleted()
                t.completionPromise?.accept(recovered)
                scheduleNormalSuccessors(t)
                checkGraphCompletion()
                return
            }
        } catch (Exception handlerErr) {
            log.error "Error in onError handler for task ${t.id}: ${handlerErr.message}"
        }
    }

    // Default: skip downstream (existing behaviour)
    markDownstreamAsSkipped(t)
    checkGraphCompletion()
}
```

#### New concept: Graph-level error handler
```groovy
// In TaskGraphDsl
void onGraphError(String taskId)    // a task to run if any unhandled task failure occurs
void onGraphError(Closure c)        // graph-level error closure
```

#### DSL Usage
```groovy
// Route failures to a named compensation task
task("call-payment") {
    onError "compensate-payment"
    action { ctx, prev -> paymentGateway.charge(prev) }
}
task("compensate-payment") {
    action { ctx, prev ->
        log.warn "Payment failed, reversing: ${prev.error}"
        auditLog.record(prev)
    }
}

// Inline recovery with continueOnError
task("fetch-user-prefs") {
    onError { ctx, err -> [:] }    // return empty prefs on failure
    continueOnError true            // treat recovered value as success
    action { ctx, prev -> prefService.fetch(prev.userId) }
}

// Graph-level fallback
graph("order-processing") {
    onGraphError { ctx, err ->
        notificationService.alertOps("Graph failed: ${err.message}")
    }
    // tasks...
}
```

#### Special task type: `ErrorHandlerTask`
For complex recovery scenarios, a dedicated task type makes the intent explicit in graph visualisation:

```groovy
task("handle-payment-error", TaskType.ERROR_HANDLER) {
    handles "call-payment"               // declares this is the error handler for that task
    action { ctx, errorPayload ->
        // errorPayload.failedTaskId, errorPayload.error, errorPayload.errorMessage
        compensate(errorPayload)
    }
}
```

---

## Uplift 5: DSL Improvements

### 5a: Fix `dependsOn` naming confusion
**Current**: `dependsOn("B", "A")` means "B depends on A" — the downstream task ID comes first. This is backwards from Gradle/Make convention.

**Fix options** (implement both, deprecate old):
```groovy
// Option A: operator DSL (recommended)
"processOrder" after "validateCart"        // natural English reading
"ship" after "processOrder"

// Option B: invert argument convention with deprecation warning
dependsOn(predecessor: "A", task: "B")    // named params = unambiguous
```

Implementation: add `after(String taskId)` as instance method on `ITask`, callable via Groovy operator DSL in `TaskGraphDsl`:

```groovy
// In TaskGraphDsl — operator >> already exists
// Add: "taskB" after "taskA"  => same as taskA >> taskB
String.metaClass.after = { String predId ->
    // register deferred wiring: delegate string (this task) depends on predId
}
```

### 5b: Clarify `parallel` vs `fork`
- `parallel {}` → creates a `PARALLEL_GATEWAY` task (fan-out to all children simultaneously)
- `fork {}` → creates a `RouterTask` (content-based routing to a subset)
- These are **not** the same concept; current naming conflates them

**Fix**: add deprecation warning on `parallel` and introduce `fanOut` as the preferred name:
```groovy
// Preferred new name
fanOut("distribute") {
    to "worker-a", "worker-b", "worker-c"
}

// Keep parallel as alias with deprecation log
@Deprecated
void parallel(String id, Closure body) {
    log.warn "parallel() is deprecated — use fanOut() for unconditional fan-out"
    fanOut(id, body)
}
```

### 5c: `>>` chaining sugar improvements
Currently `>>` exists for simple linear chains. Extend to support:
```groovy
// Multi-input "after" syntax
"merge" after ["task-a", "task-b", "task-c"]   // merge depends on all three

// Error routing sugar  
"process" onError "handle-error"

// Conditional routing sugar
"router" when { ctx, input -> input.priority == 'HIGH' } then "fast-path" otherwise "slow-path"
```

### 5d: Document the three entry points
Add a `README-ENTRY-POINTS.md` (or top-level doc block) that clearly maps use cases to entry points:

| Need | Use |
|------|-----|
| Fire-and-forget async tasks, no dependencies | `Tasks` |
| Event-driven reactive task registry, loose coupling | `TasksCollection` |
| Structured workflow with dependencies, routing, resilience | `TaskGraph` + DSL |

---

## Implementation Order & Effort Estimates

| # | Feature | Effort | Risk | Priority |
|---|---------|--------|------|----------|
| 1 | Memoisation (MemoCache + MemoPolicy + TaskBase hooks) | M | Low | HIGH |
| 2 | ContentEnricherTask | M | Low | HIGH |
| 3 | JoinStrategy OR + N-of-M (JoinTask + TaskGraph changes) | L | Medium | HIGH |
| 4 | Error routing (TaskBase fields + TaskGraph onError routing) | L | Medium | CRITICAL |
| 5a | dependsOn fix + `after` operator | S | Low | MEDIUM |
| 5b | parallel/fanOut rename | S | Low | LOW |
| 5c | `>>` chain extensions | S | Low | MEDIUM |

**Effort key**: S = half day, M = 1 day, L = 1.5–2 days

---

## File Change Summary

### New files
```
dag/resilience/MemoCache.groovy
dag/resilience/MemoCacheEntry.groovy
dag/resilience/MemoPolicy.groovy
dag/resilience/MemoDsl.groovy
dag/task/ContentEnricherTask.groovy
dag/task/ContentEnricherDsl.groovy
dag/task/JoinTask.groovy                  (replaces plain ServiceTask in JoinDsl)
dag/task/ErrorHandlerTask.groovy          (optional — for explicit error handler tasks)
```

### Modified files
```
dag/resilience/JoinStategy.groovy         → rename JoinStrategy.groovy + add N_OF_M
dag/JoinDsl.groovy                        → strategy + quorum + timeout support
dag/task/TaskBase.groovy                  → memo{} DSL, onError{} DSL, errorSuccessor field
dag/task/TaskContext.groovy               → memoCache field
dag/task/TaskType.groovy                  → CONTENT_ENRICHER, ERROR_HANDLER
dag/task/TaskFactory.groovy               → register new task types
dag/TaskGraph.groovy                      → error routing in onError{}, join strategy in scheduleIfReady()
dag/TaskGraphDsl.groovy                   → enrich{}, onGraphError{}, fanOut{}, after operator
```

### Test files (new)
```
test/MemoSpec.groovy
test/ContentEnricherTaskSpec.groovy
test/JoinStrategySpec.groovy
test/ErrorRoutingSpec.groovy
```

---

## Build Cycle Plan

Each uplift is a self-contained build-test cycle:

1. **Cycle 1**: MemoCache + MemoPolicy + MemoDsl + TaskBase.memo{} hook + MemoSpec tests → checkpoint
2. **Cycle 2**: ContentEnricherTask + DSL + TaskType entry + ContentEnricherTaskSpec tests → checkpoint
3. **Cycle 3**: JoinTask + JoinStrategy N_OF_M + JoinDsl update + TaskGraph.scheduleIfReady changes + JoinStrategySpec → checkpoint
4. **Cycle 4**: Error routing fields in TaskBase + TaskGraph onError routing + ErrorRoutingSpec → checkpoint
5. **Cycle 5**: DSL improvements (after operator, fanOut, chain extensions) → full test suite run → tag v2.1.0

Checkpoint format after each cycle:
```
git add -A
git commit -m "uplift: [cycle name] - [summary]"
```
