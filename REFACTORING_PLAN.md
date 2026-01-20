# Architectural Refactoring Plan - Circuit Breaker & Manual Task Enhancements

## Executive Summary

Based on architectural review, we need to:
1. **Refactor CircuitBreakerTask** → Move circuit breaker to TaskBase as a resilience feature
2. **Enhance ManualTask** → Add notifications/escalation with new `dag.task.manual` package
3. **Clarify SubGraph vs Subprocess** → Document the reusability pattern

---

## Part 1: Circuit Breaker Refactoring

### Current State (INCONSISTENT ❌)

```
dag.task.CircuitBreakerTask.groovy         ← Wrapper task
dag.task.CircuitBreakerState.groovy        ← State enum
dag.task.CircuitBreakerOpenException.groovy ← Exception
```

**Problem:** Circuit breaker is implemented as a **wrapper task**, while all other resilience features (retry, DLQ, rate limit, idempotency) are **TaskBase features**.

### Target State (CONSISTENT ✅)

```
dag.resilience.CircuitBreakerPolicy.groovy      ← Policy (like RetryPolicy, RateLimitPolicy)
dag.resilience.CircuitBreakerDsl.groovy         ← DSL (like RetryDsl, RateLimitDsl)
dag.resilience.CircuitBreakerState.groovy       ← State enum (moved from dag.task)
dag.resilience.CircuitBreakerOpenException.groovy ← Exception (moved from dag.task)
dag.resilience.CircuitBreaker.groovy            ← Circuit breaker implementation
```

**TaskBase Enhancement:**
```groovy
class TaskBase {
    // Add alongside other resilience features
    protected CircuitBreakerPolicy circuitBreakerPolicy = new CircuitBreakerPolicy()
    
    void circuitBreaker(@DelegatesTo(CircuitBreakerDsl) Closure config) {
        def dsl = new CircuitBreakerDsl(this.circuitBreakerPolicy)
        config.delegate = dsl
        config.resolveStrategy = Closure.DELEGATE_FIRST
        config.call()
    }
    
    void circuitBreaker(String preset) {
        def dsl = new CircuitBreakerDsl(this.circuitBreakerPolicy)
        dsl.preset(preset)
    }
}
```

### Usage (New Pattern)

```groovy
// OLD (wrapper task - DELETE)
circuitBreakerTask("protected-call") {
    wrappedTask serviceTask("api") { ... }
    failureThreshold 5
}

// NEW (TaskBase feature - CONSISTENT)
serviceTask("api-call") {
    action { ctx, prev -> callUnreliableApi() }
    
    circuitBreaker {
        failureThreshold 5
        successThreshold 2
        resetTimeout Duration.ofMinutes(1)
        halfOpenRequests 3
        onOpen { ctx -> log.error("Circuit opened for ${ctx.taskId}") }
        onClose { ctx -> log.info("Circuit closed for ${ctx.taskId}") }
    }
    
    retry {
        maxAttempts 3
        backoffMultiplier 2.0
    }
}
```

### Migration Path

1. **Create new resilience classes** (4 files):
   - `CircuitBreakerPolicy.groovy`
   - `CircuitBreakerDsl.groovy`
   - Move `CircuitBreakerState.groovy` from `dag.task` to `dag.resilience`
   - Move `CircuitBreakerOpenException.groovy` from `dag.task` to `dag.resilience`
   - `CircuitBreaker.groovy` (state machine implementation)

2. **Enhance TaskBase**:
   - Add `circuitBreakerPolicy` field
   - Add `circuitBreaker()` DSL methods
   - Integrate with `executeTaskWithRetry()` logic

3. **Deprecate CircuitBreakerTask**:
   - Mark `@Deprecated`
   - Add migration guide
   - Keep for backward compatibility (1-2 releases)

4. **Update tests**:
   - Add circuit breaker tests to TaskBase tests
   - Update CircuitBreakerTask tests to show migration

**Effort:** 6-8 hours

---

## Part 2: Manual Task Enhancement

### Current State

```
dag.task.ManualTask.groovy                 ← Basic manual task
dag.task.ManualTaskContext.groovy          ← Context
```

**Problem:** Too basic - no notifications, escalation, or enterprise features

### Target State (NEW PACKAGE ✅)

```
dag.task.manual/
  ├── ManualTask.groovy                    ← Enhanced manual task
  ├── ManualTaskContext.groovy             ← Enhanced context
  ├── ManualTaskConfig.groovy              ← Configuration (NEW)
  ├── ManualTaskNotification.groovy        ← Notification support (NEW)
  ├── ManualTaskEscalation.groovy          ← Escalation rules (NEW)
  ├── ManualTaskAssignment.groovy          ← Assignment rules (NEW)
  ├── NotificationChannel.groovy           ← Email, Slack, etc (NEW)
  └── EscalationRule.groovy                ← When/who to escalate (NEW)
```

### Enhanced Manual Task Design

```groovy
manualTask("approve-order") {
    // Basic info
    title "Approve High-Value Order"
    instructions "Please review order #${prev.orderId} for approval"
    priority HIGH
    
    // Assignment (NEW)
    assignee "manager@company.com"
    candidateGroups "approvers", "managers"
    
    // Form fields (EXISTING - enhance)
    field "approved", type: BOOLEAN, required: true
    field "comments", type: TEXT, required: false
    
    // Notifications (NEW)
    notify {
        email "manager@company.com"
        slack {
            channel "#approvals"
            message "New approval needed: Order #${prev.orderId}"
        }
        webhook "https://approval-system.com/notify"
    }
    
    // Escalation (NEW)
    escalate {
        after Duration.ofHours(24)
        to "director@company.com"
        reason "Manager has not responded in 24 hours"
        notify {
            email "director@company.com"
            slack "#urgent-approvals"
        }
    }
    
    // Timeout with auto-action (learn from ReceiveTask)
    timeout Duration.ofHours(48)
    autoAction {
        log.warn("Auto-rejecting order due to timeout")
        [approved: false, reason: "timeout", auto: true]
    }
    
    // DLQ for failed manual tasks (inherited from TaskBase)
    deadLetterQueue {
        maxSize 100
        maxAge Duration.ofDays(7)
    }
}
```

### Support Classes

#### 1. **ManualTaskNotification.groovy**
```groovy
class ManualTaskNotification {
    List<NotificationChannel> channels = []
    
    void email(String address) {
        channels << new EmailChannel(address)
    }
    
    void slack(String channel) {
        channels << new SlackChannel(channel)
    }
    
    void slack(@DelegatesTo(SlackNotification) Closure config) {
        def notification = new SlackNotification()
        config.delegate = notification
        config.call()
        channels << new SlackChannel(notification)
    }
    
    void webhook(String url) {
        channels << new WebhookChannel(url)
    }
}
```

#### 2. **ManualTaskEscalation.groovy**
```groovy
class ManualTaskEscalation {
    Duration delay
    String assignee
    String reason
    ManualTaskNotification notification
    
    void after(Duration duration) {
        this.delay = duration
    }
    
    void to(String assignee) {
        this.assignee = assignee
    }
    
    void reason(String text) {
        this.reason = text
    }
    
    void notify(@DelegatesTo(ManualTaskNotification) Closure config) {
        notification = new ManualTaskNotification()
        config.delegate = notification
        config.call()
    }
}
```

#### 3. **NotificationChannel.groovy** (interface)
```groovy
interface NotificationChannel {
    void send(ManualTaskContext ctx, String message)
    String getChannelType()
}

class EmailChannel implements NotificationChannel {
    String address
    
    EmailChannel(String address) {
        this.address = address
    }
    
    @Override
    void send(ManualTaskContext ctx, String message) {
        // Use MailTask or JavaMail
        sendEmail(address, "Manual Task: ${ctx.taskId}", message)
    }
    
    @Override
    String getChannelType() { "email" }
}

class SlackChannel implements NotificationChannel {
    String channel
    String message
    
    @Override
    void send(ManualTaskContext ctx, String defaultMessage) {
        // Use Slack webhook or API
        sendSlackMessage(channel, message ?: defaultMessage)
    }
    
    @Override
    String getChannelType() { "slack" }
}

class WebhookChannel implements NotificationChannel {
    String url
    
    @Override
    void send(ManualTaskContext ctx, String message) {
        // HTTP POST to webhook
        postWebhook(url, [
            taskId: ctx.taskId,
            message: message,
            timestamp: System.currentTimeMillis()
        ])
    }
    
    @Override
    String getChannelType() { "webhook" }
}
```

### Integration with Existing Features

**Reuse from ReceiveTask:**
- ✅ Auto-action on timeout pattern
- ✅ Correlation-based resolution (manual task completion)

**Reuse from TaskBase:**
- ✅ DLQ for failed manual tasks
- ✅ Timeout handling
- ✅ Event dispatching

**New for ManualTask:**
- Assignment logic
- Notification channels
- Escalation rules
- Form validation

### Migration Path

1. **Create new package** `dag.task.manual/`

2. **Move existing files**:
   - Move `ManualTask.groovy` → `dag.task.manual/ManualTask.groovy`
   - Move `ManualTaskContext.groovy` → `dag.task.manual/ManualTaskContext.groovy`

3. **Add new support classes**:
   - `ManualTaskNotification.groovy`
   - `ManualTaskEscalation.groovy`
   - `NotificationChannel.groovy` (and implementations)
   - `ManualTaskAssignment.groovy`

4. **Enhance ManualTask**:
   - Add notification DSL
   - Add escalation DSL
   - Add auto-action (from ReceiveTask pattern)

5. **Update tests**:
   - Test notifications
   - Test escalation
   - Test auto-action
   - Test form validation

**Effort:** 8-10 hours

---

## Part 3: SubGraph vs Subprocess Clarification

### Purpose Distinction (DOCUMENT CLEARLY)

#### **SubGraphTask** - Reusable Graph as Node
**Purpose:** Encapsulate a reusable workflow as a single task node

**Use Case:** You have a common sequence (e.g., "validate-transform-store") that you use in multiple places

```groovy
// Define reusable subgraph
def validateStoreGraph = TaskGraph.build {
    serviceTask("validate") { ... }
    dataTransformTask("transform") { ... }
    fileTask("store") { ... }
    chainVia("validate", "transform", "store")
}

// Use as a single node in larger graph
def mainGraph = TaskGraph.build {
    httpTask("fetch-data") { ... }
    
    // Reusable validation-transform-store logic
    subGraphTask("process-data") {
        graph validateStoreGraph
    }
    
    messagingTask("publish-result") { ... }
    
    chainVia("fetch-data", "process-data", "publish-result")
}
```

**Key Points:**
- Treats an entire graph as a single task
- Input/output pass through
- Reusable across different workflows
- Think: "function call" in programming

#### **SubprocessTask** - Embedded Subprocess (BPMN Style)
**Purpose:** Embed a subprocess within a larger process (BPMN embedded subprocess)

**Use Case:** Workflow has a logical subprocess that's part of this specific flow

```groovy
def orderWorkflow = TaskGraph.build {
    serviceTask("receive-order") { ... }
    
    // Subprocess: specific to this workflow
    subprocessTask("fulfill-order") {
        subprocess {
            serviceTask("check-inventory") { ... }
            serviceTask("allocate-stock") { ... }
            serviceTask("create-shipment") { ... }
            chainVia("check-inventory", "allocate-stock", "create-shipment")
        }
    }
    
    messagingTask("notify-customer") { ... }
    
    chainVia("receive-order", "fulfill-order", "notify-customer")
}
```

**Key Points:**
- Inline subprocess definition
- Scoped to this workflow
- Not intended for reuse
- Think: "code block" in programming

#### **LoopTask** - Repeated Execution
**Purpose:** Execute a graph multiple times (for-each pattern)

**Use Case:** Process multiple items with same logic

```groovy
def batchProcessor = TaskGraph.build {
    serviceTask("fetch-orders") { 
        action { [orders: [order1, order2, order3]] }
    }
    
    loopTask("process-each-order") {
        collection { prev -> prev.orders }
        
        // Execute this graph for each order
        graph {
            serviceTask("validate-order") { ... }
            serviceTask("process-payment") { ... }
            serviceTask("fulfill-order") { ... }
            chainVia("validate-order", "process-payment", "fulfill-order")
        }
    }
    
    chainVia("fetch-orders", "process-each-order")
}
```

**Key Points:**
- Iteration over collection
- Graph executed N times
- Collect results
- Think: "for-each loop" in programming

### Decision Matrix

| Pattern | Reusable? | Inline? | Multiple Executions? | Use When |
|---------|-----------|---------|----------------------|----------|
| **SubGraphTask** | ✅ Yes | ❌ No (ref) | ❌ No | Common workflow pattern used in multiple places |
| **SubprocessTask** | ❌ No | ✅ Yes | ❌ No | Logical subprocess within a specific workflow |
| **LoopTask** | ✅ Yes | ✅ Yes | ✅ Yes (iteration) | Process collection of items |

### Documentation Updates Needed

1. **Create guide**: `SUBGRAPH_PATTERNS.md`
   - When to use SubGraphTask
   - When to use SubprocessTask
   - When to use LoopTask
   - Examples of each
   - Migration between patterns

2. **Update JavaDoc**:
   - Clear purpose statements
   - Cross-references
   - Usage examples

3. **Add to main README**:
   - Section on composition patterns
   - Decision tree diagram

**Effort:** 2-3 hours

---

## Part 4: Implementation Priority

### Phase 1: Circuit Breaker Refactor (HIGH PRIORITY) ⚡
**Time:** 6-8 hours

**Tasks:**
1. Create `dag.resilience.CircuitBreakerPolicy`
2. Create `dag.resilience.CircuitBreakerDsl`
3. Move `CircuitBreakerState` to `dag.resilience`
4. Move `CircuitBreakerOpenException` to `dag.resilience`
5. Create `dag.resilience.CircuitBreaker` (state machine)
6. Enhance `TaskBase` with circuit breaker DSL
7. Integrate with `executeTaskWithRetry()`
8. Deprecate `CircuitBreakerTask`
9. Write tests
10. Update documentation

### Phase 2: Manual Task Enhancement (MEDIUM PRIORITY) 🔔
**Time:** 8-10 hours

**Tasks:**
1. Create `dag.task.manual/` package
2. Move existing files to new package
3. Create `ManualTaskNotification`
4. Create `ManualTaskEscalation`
5. Create `NotificationChannel` and implementations
6. Create `ManualTaskAssignment`
7. Enhance `ManualTask` with new DSL
8. Add auto-action pattern (from ReceiveTask)
9. Write tests
10. Update documentation

### Phase 3: Documentation Clarity (LOW EFFORT) 📝
**Time:** 2-3 hours

**Tasks:**
1. Create `SUBGRAPH_PATTERNS.md`
2. Update JavaDoc for SubGraphTask, SubprocessTask, LoopTask
3. Add composition patterns section to README
4. Create decision tree diagram

---

## Part 5: File Organization Summary

### Before
```
dag.task/
  ├── CircuitBreakerTask.groovy           ← INCONSISTENT
  ├── CircuitBreakerState.groovy          ← WRONG LOCATION
  ├── CircuitBreakerOpenException.groovy  ← WRONG LOCATION
  ├── ManualTask.groovy                   ← TOO BASIC
  ├── ManualTaskContext.groovy
  ├── SubGraphTask.groovy                 ← UNCLEAR
  ├── SubprocessTask.groovy               ← UNCLEAR
  └── ... other tasks

dag.resilience/
  ├── RetryPolicy.groovy
  ├── RateLimitPolicy.groovy
  ├── DeadLetterQueuePolicy.groovy
  ├── IdempotencyPolicy.groovy
  └── ... (no circuit breaker!)
```

### After (CONSISTENT ✅)
```
dag.task/
  ├── @Deprecated CircuitBreakerTask.groovy  ← Deprecated, kept for compatibility
  ├── ManualTask.groovy                      ← Points to dag.task.manual.ManualTask
  ├── SubGraphTask.groovy                    ← DOCUMENTED
  ├── SubprocessTask.groovy                  ← DOCUMENTED
  └── ... other tasks

dag.task.manual/                              ← NEW PACKAGE
  ├── ManualTask.groovy                       ← Enhanced
  ├── ManualTaskContext.groovy
  ├── ManualTaskNotification.groovy           ← NEW
  ├── ManualTaskEscalation.groovy             ← NEW
  ├── ManualTaskAssignment.groovy             ← NEW
  ├── NotificationChannel.groovy              ← NEW
  └── ... support classes

dag.resilience/
  ├── RetryPolicy.groovy
  ├── RateLimitPolicy.groovy
  ├── DeadLetterQueuePolicy.groovy
  ├── IdempotencyPolicy.groovy
  ├── CircuitBreakerPolicy.groovy             ← NEW (consistent!)
  ├── CircuitBreakerDsl.groovy                ← NEW
  ├── CircuitBreakerState.groovy              ← MOVED from dag.task
  ├── CircuitBreakerOpenException.groovy      ← MOVED from dag.task
  └── CircuitBreaker.groovy                   ← NEW (state machine)
```

---

## Part 6: Testing Strategy

### Circuit Breaker Tests
```groovy
class CircuitBreakerIntegrationTest extends Specification {
    def "should open circuit after threshold failures"() { ... }
    def "should attempt reset after timeout"() { ... }
    def "should close circuit after successful resets"() { ... }
    def "should work with retry policy"() { ... }
    def "should invoke callbacks on state change"() { ... }
}
```

### Manual Task Tests
```groovy
class ManualTaskEnhancedTest extends Specification {
    def "should send notifications on task creation"() { ... }
    def "should escalate after timeout"() { ... }
    def "should apply auto-action on timeout"() { ... }
    def "should validate form fields"() { ... }
    def "should support multiple notification channels"() { ... }
}
```

---

## Conclusion

### Summary of Changes

| Change | Type | Effort | Priority |
|--------|------|--------|----------|
| Circuit Breaker → TaskBase | Refactor | 6-8h | HIGH ⚡ |
| Manual Task Enhancement | Feature | 8-10h | MEDIUM 🔔 |
| SubGraph Documentation | Docs | 2-3h | LOW 📝 |
| **Total** | | **16-21h** | |

### Benefits

1. **Consistency** - All resilience features on TaskBase
2. **Enterprise Ready** - ManualTask has full features
3. **Clarity** - SubGraph patterns well-documented
4. **Maintainability** - Better package organization
5. **Competitiveness** - Matches Camunda's user task features

### Next Steps

**Phase 1 (This Session):**
1. Implement Circuit Breaker refactor

**Phase 2 (Next Session):**
2. Implement Manual Task enhancements

**Phase 3 (Documentation):**
3. Create SubGraph pattern guide

**Then: Ship v1.0!** 🚀
