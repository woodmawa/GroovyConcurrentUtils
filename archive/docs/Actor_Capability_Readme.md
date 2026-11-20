# GroovyActor - Complete Architecture & Capabilities Guide

## Table of Contents

1. [Overview & Architecture](#overview--architecture)
2. [Core Actor Contract](#core-actor-contract)
3. [ActorSystem - The Foundation](#actorsystem---the-foundation)
4. [Actor Hierarchies & Supervision](#actor-hierarchies--supervision)
5. [Lifecycle Management](#lifecycle-management)
6. [Remote Actors & Transport](#remote-actors--transport)
7. [Clustering with Hazelcast](#clustering-with-hazelcast)
8. [Serialization Options](#serialization-options)
9. [Configuration & Defaults](#configuration--defaults)
10. [Best Practices & Patterns](#best-practices--patterns)
11. [@SafeActor - Compile-Time Safety](#safeactor---compile-time-safety)

---

## Overview & Architecture

### Design Philosophy

GroovyActor is a production-ready actor framework built for Groovy 5, emphasizing:

- **Simplicity**: Clean DSL for actor creation and messaging
- **Safety**: Comprehensive error handling and supervision
- **Flexibility**: Pluggable transports, serializers, and registries
- **Performance**: Virtual threads, efficient message passing
- **Production-Ready**: Configuration, monitoring, distributed support

### Architecture Layers

```
┌─────────────────────────────────────────────────────────┐
│                   Application Layer                      │
│              (Your Actors & Business Logic)              │
├─────────────────────────────────────────────────────────┤
│                    Actor DSL Layer                       │
│        (ActorSystem, ActorBuilder, ActorFactory)         │
├─────────────────────────────────────────────────────────┤
│                   Core Actor Layer                       │
│    (GroovyActor, ActorContext, Message Processing)      │
├─────────────────────────────────────────────────────────┤
│              Advanced Features Layer                     │
│  (Supervision, Hierarchy, DeathWatch, Scheduling)        │
├─────────────────────────────────────────────────────────┤
│                  Distribution Layer                      │
│   (RemoteActorRef, Transports, Serialization)           │
├─────────────────────────────────────────────────────────┤
│                Infrastructure Layer                      │
│  (Executor Pools, Virtual Threads, Configuration)       │
└─────────────────────────────────────────────────────────┘
```

### Key Components

| Component | Purpose | Location |
|-----------|---------|----------|
| **ActorSystem** | Primary entry point, lifecycle coordinator | `ActorSystem.groovy` |
| **GroovyActor** | Core actor implementation | `GroovyActor.groovy` |
| **ActorContext** | Message handler context with helper methods | `ActorContext.groovy` |
| **ActorRegistry** | Actor lookup (local or distributed) | `ActorRegistry.groovy` |
| **ActorScheduler** | Delayed and periodic messages | `scheduling/ActorScheduler.groovy` |
| **DeathWatchRegistry** | Lifecycle monitoring | `lifecycle/DeathWatchRegistry.groovy` |
| **ActorHierarchyRegistry** | Parent-child relationships | `hierarchy/ActorHierarchyRegistry.groovy` |
| **RemotingTransport** | Distributed actor communication | `remote/RemotingTransport.groovy` |

---

## Core Actor Contract

### The Actor Interface

All actors implement the `Actor` interface, which defines the core contract:

```groovy
interface Actor {
    // Messaging
    void tell(Object msg)                                    // Fire-and-forget
    void tell(Object msg, Actor sender)                      // With sender tracking
    Object ask(Object msg, Duration timeout)                 // Request-response (async)
    Object askSync(Object msg, Duration timeout)            // Request-response (blocking)
    
    // Lifecycle
    void stop()                                              // Graceful shutdown
    boolean stop(Duration timeout)                           // Graceful with timeout
    void stopNow()                                           // Immediate shutdown
    boolean isStopped()
    boolean isTerminated()
    
    // State & Identity
    String getName()
    Map getState()
    
    // Error Handling
    Actor onError(Closure handler)
    List getErrors(int maxCount)
    void clearErrors()
    
    // Mailbox Management
    void setMaxMailboxSize(int max)
    int getMaxMailboxSize()
    
    // Monitoring
    Map health()
    Map metrics()
}
```

### GroovyActor Implementation

`GroovyActor` is the primary implementation:

**Key Features:**
- Message queue with backpressure (configurable max size)
- Virtual thread execution
- Automatic error capture and retry
- State management (thread-safe)
- Lifecycle hooks
- Metrics and health reporting

**Message Processing Flow:**
```
1. Message arrives → tell(msg)
2. Added to mailbox (ConcurrentLinkedQueue)
3. Processing task submitted to executor pool
4. Message handler invoked with ActorContext
5. Handler returns result (for ask) or null (for tell)
6. Auto-reply or explicit reply via ctx.reply()
7. Next message processed
```

**State Management:**
```groovy
def actor = system.actor {
    name 'Counter'
    initialState count: 0  // Thread-safe via ConcurrentHashMap
    
    onMessage { msg, ctx ->
        ctx.state.count++  // Safe concurrent access
        ctx.reply(ctx.state.count)
    }
}
```

### Message Handler Context (ActorContext)

Every message handler receives an `ActorContext` with rich capabilities:

```groovy
onMessage { msg, ctx ->
    // Message & State
    ctx.message        // Current message
    ctx.state          // Actor state (mutable)
    ctx.actorName      // This actor's name
    
    // Messaging
    ctx.reply(result)  // Reply to sender
    ctx.forward("OtherActor", msg)  // Forward to another actor
    ctx.broadcast(["A1", "A2"], msg)  // Send to multiple actors
    
    // Actor Management
    ctx.spawn('child') { childMsg, childCtx -> ... }  // Create child
    ctx.spawnForEach(items, 'worker') { ... }  // Bulk create
    ctx.watch(otherActor)  // Monitor lifecycle
    ctx.unwatch(otherActor)
    
    // Scheduling
    ctx.scheduleOnce(Duration.ofSeconds(5), 'timeout')
    ctx.scheduleAtFixedRate(delay, period, 'heartbeat')
    
    // System Access
    ctx.system         // ActorSystem reference
    ctx.actorRef("OtherActor")  // Lookup actors
    
    // Async Handling
    ctx.deferReply()   // Prevent auto-reply for async work
    
    // Sender Tracking
    ctx.sender         // Original sender (if tracked)
    ctx.replyToSender(msg)
    
    // Security
    ctx.isAuthenticated()
    ctx.hasRole("admin")
    ctx.requireRole("admin")
}
```

---

## ActorSystem - The Foundation

### Primary Entry Point

`ActorSystem` is the **recommended way** to create actors in production:

```groovy
// 1. Create the system
def system = new ActorSystem("my-app")

// 2. Create actors through the system
def greeter = system.actor {
    name 'Greeter'
    onMessage { msg, ctx ->
        println "Hello, ${msg}!"
        ctx.reply("Greeted: ${msg}")
    }
}

// 3. Send messages
greeter.tell("World")
def response = greeter.askSync("Alice", Duration.ofSeconds(2))

// 4. Coordinated shutdown
system.shutdown()
```

### Why Use ActorSystem?

Actors created via `ActorSystem` get:
- ✅ Automatic registration in the registry
- ✅ System reference injection (enables ctx.spawn, ctx.watch, etc.)
- ✅ Access to scheduler, deathwatch, hierarchy
- ✅ Coordinated shutdown
- ✅ Full feature support

Actors created via `ActorFactory.create()`:
- ❌ Standalone (not registered)
- ❌ No system reference
- ❌ Cannot use ctx.spawn(), ctx.watch(), ctx.scheduleOnce()
- ⚠️ Use only for testing or advanced cases

### ActorSystem Components

```groovy
class ActorSystem {
    final String name                          // System identifier
    final ActorRegistry registry               // Actor lookup
    final ActorScheduler scheduler             // Message scheduling
    final DeathWatchRegistry deathWatch        // Lifecycle monitoring
    final ActorHierarchyRegistry hierarchy     // Parent-child tracking
    
    // Actor creation
    Actor actor(Closure spec)                  // DSL creation
    Actor actor(String name, Closure handler)  // Simple creation
    Actor createActor(String name, ...)        // Explicit creation
    
    // Actor management
    Actor getActor(String name)
    Set<String> getActorNames()
    boolean hasActor(String name)
    void removeActor(String name)
    
    // Lifecycle
    void shutdown()
    void close()
    
    // Remoting (optional)
    void enableRemoting(List<RemotingTransport> transports)
    Object remote(String actorUri)
}
```

### Distributed Registry (Optional)

By default, `ActorRegistry` is local (in-memory). For distributed systems, use `HazelcastActorRegistry`:

```groovy
// Local registry (default)
def system = new ActorSystem("app")

// Distributed registry (Hazelcast)
def hazelcastInstance = Hazelcast.newHazelcastInstance()
def registry = new HazelcastActorRegistry(hazelcastInstance)
def system = new ActorSystem("app", registry)

// Now actors are visible across all cluster nodes
```

---

## Actor Hierarchies & Supervision

### Parent-Child Relationships

Actors can spawn children, creating supervision hierarchies:

```groovy
def supervisor = system.actor {
    name 'Supervisor'
    
    onMessage { msg, ctx ->
        if (msg == 'create-workers') {
            // Spawn child actors
            def worker1 = ctx.spawn('worker-1') { childMsg, childCtx ->
                println "Worker 1 processing: ${childMsg}"
            }
            
            def worker2 = ctx.spawn('worker-2') { childMsg, childCtx ->
                println "Worker 2 processing: ${childMsg}"
            }
            
            // Parent automatically supervises children
            ctx.watch(worker1)  // Get notified if child terminates
            ctx.watch(worker2)
        }
        
        if (msg instanceof Terminated) {
            println "Child ${msg.actor.name} terminated!"
            // Restart, escalate, or handle failure
        }
    }
}
```

### Bulk Child Creation (Safe)

Use `ctx.spawnForEach()` to avoid Groovy closure scoping issues:

```groovy
ctx.spawnForEach(directories, 'dir-scanner') { dir, index, msg, ctx ->
    if (msg.type == 'scan') {
        // Process directory safely
        dir.listFiles().each { file ->
            // Process file
        }
    }
}
```

### Supervision Strategies

Supervisors can define error handling strategies:

```groovy
def supervisor = system.actor {
    name 'Supervisor'
    supervisionStrategy {
        // Restart on specific errors
        onError(IllegalArgumentException) { error, child ->
            restart(child)
        }
        
        // Stop on fatal errors
        onError(FatalException) { error, child ->
            stop(child)
        }
        
        // Escalate unknown errors
        onError(Exception) { error, child ->
            escalate(error)
        }
    }
    
    onMessage { msg, ctx ->
        // Supervisor logic
    }
}
```

**Built-in Strategies:**
- `RestartStrategy` - Restart failed actor (default)
- `StopStrategy` - Stop failed actor permanently
- `EscalateStrategy` - Pass error to parent supervisor
- `ResumeStrategy` - Ignore error, continue processing

### Hierarchy Query

```groovy
// Get all children of an actor
def children = ctx.children  // Set<String> of child names

// Get parent of an actor
def parent = ctx.parent  // String parent name or null

// Navigate hierarchy
system.hierarchy.getChildren(actor)
system.hierarchy.getParent(actor)
```

---

## Lifecycle Management

### Actor Lifecycle States

```
Created → Started → Running → Stopping → Stopped → Terminated
                      ↓
                   Failed (with supervision)
```

### Lifecycle Hooks

```groovy
def actor = system.actor {
    name 'Lifecycle'
    
    preStart {
        println "Actor starting - initialize resources"
    }
    
    postStop {
        println "Actor stopped - cleanup resources"
    }
    
    preRestart { reason ->
        println "Actor restarting due to: ${reason}"
        // Save state, close connections
    }
    
    postRestart { reason ->
        println "Actor restarted - reinitialize"
        // Restore state, reopen connections
    }
    
    onMessage { msg, ctx ->
        // Handle messages
    }
}
```

### Death Watch (Lifecycle Monitoring)

Actors can watch other actors and receive `Terminated` messages:

```groovy
def watcher = system.actor {
    name 'Watcher'
    
    onMessage { msg, ctx ->
        if (msg == 'watch-worker') {
            def worker = ctx.actorRef("Worker")
            ctx.watch(worker)  // Start watching
            println "Now watching Worker"
        }
        
        if (msg instanceof Terminated) {
            println "Actor ${msg.actor.name} has terminated!"
            println "Reason: ${msg.reason}"
            
            // React to termination
            if (msg.actor.name == 'Worker') {
                // Spawn replacement
                ctx.spawn('Worker') { ... }
            }
        }
    }
}
```

**Terminated Message:**
```groovy
class Terminated {
    final Actor actor          // The terminated actor
    final String reason        // Why it terminated
    final boolean cleanShutdown
}
```

### Graceful Shutdown

```groovy
// System-wide graceful shutdown
system.shutdown()

// Individual actor shutdown
actor.stop()                              // Graceful (finish current message)
actor.stop(Duration.ofSeconds(5))         // With timeout
actor.stopNow()                           // Immediate (interrupt)

// Check status
if (actor.isStopped()) {
    println "Actor has stopped processing"
}

if (actor.isTerminated()) {
    println "Actor is fully terminated"
}
```

---

## Remote Actors & Transport

### Remote Actor Architecture

GroovyActor supports distributed actor communication via pluggable transports:

```
┌─────────────┐                    ┌─────────────┐
│   Node 1    │                    │   Node 2    │
│             │                    │             │
│  Actor A ───┼──── Network ───────┼───> Actor B │
│             │   (Transport)      │             │
└─────────────┘                    └─────────────┘
```

### Pluggable Transport Architecture

**Interface:**
```groovy
interface RemotingTransport {
    String scheme()                          // "http", "rsocket", "grpc", etc.
    void start()                             // Start transport server
    void close()                             // Shutdown transport
    void tell(String uri, Object msg)        // Send fire-and-forget
    Object ask(String uri, Object msg, Duration timeout)  // Request-response
}
```

**Built-in Transports:**

| Transport | Scheme | Use Case | Features |
|-----------|--------|----------|----------|
| **HTTP** | `http://` | REST-like, simple | JSON, easy debugging |
| **RSocket** | `rsocket://` | High-performance | Binary, streaming, backpressure |

### HTTP Transport

**Configuration:**
```groovy
// Node 1 - Server
def httpTransport = new HttpTransport(
    host: "0.0.0.0",
    port: 8080,
    system: system
)

system.enableRemoting([httpTransport])
```

**Usage:**
```groovy
// Node 2 - Client
def remoteActor = system.remote("http://node1:8080/my-app/GreeterActor")

// Fire-and-forget
remoteActor.tell("Hello from remote!")

// Request-response
def result = remoteActor.ask("Ping", Duration.ofSeconds(5))
println result  // "Pong"
```

**HTTP Transport Features:**
- JSON serialization (default)
- Pluggable serializers
- TLS/SSL support
- Authentication via headers
- Health check endpoints
- Metrics endpoints

### RSocket Transport

**Configuration:**
```groovy
// Node 1 - Server
def rsocketTransport = new RSocketTransport(
    host: "0.0.0.0",
    port: 7000,
    system: system
)

system.enableRemoting([rsocketTransport])
```

**Usage:**
```groovy
// Node 2 - Client
def remoteActor = system.remote("rsocket://node1:7000/my-app/DataProcessor")

// High-performance binary messaging
remoteActor.tell(largeDataSet)
def result = remoteActor.ask(complexQuery, Duration.ofSeconds(10))
```

**RSocket Transport Features:**
- Binary serialization (MessagePack default)
- Streaming support (request-stream, channel)
- Backpressure handling
- Connection resumption
- TLS/SSL support
- Low latency

### Remote Actor URI Format

```
scheme://host:port/system-name/actor-name
```

**Examples:**
```
http://localhost:8080/my-app/GreeterActor
rsocket://10.0.1.5:7000/production/DataProcessor
```

### Security for Remote Actors

**TLS Configuration:**
```groovy
def httpTransport = new HttpTransport(
    host: "0.0.0.0",
    port: 8443,
    system: system,
    tls: [
        keyStore: "/path/to/keystore.jks",
        keyStorePassword: "password",
        trustStore: "/path/to/truststore.jks",
        trustStorePassword: "password"
    ]
)
```

**Authentication:**
```groovy
def httpTransport = new HttpTransport(
    host: "0.0.0.0",
    port: 8080,
    system: system,
    auth: [
        type: "jwt",
        secret: "your-secret-key"
    ]
)
```

**Authorization in Actors:**
```groovy
def secureActor = system.actor {
    name 'SecureActor'
    
    onMessage { msg, ctx ->
        // Check authentication
        ctx.requireAuthenticated()
        
        // Check authorization
        ctx.requireRole("admin")
        
        // Process message
        ctx.reply("Authorized!")
    }
}
```

---

## Clustering with Hazelcast

### Architecture Overview

GroovyActor provides **production-ready Hazelcast clustering** through `HazelcastManager`, a centralized singleton that manages distributed data structures for both ActorSystem and TaskGraph.

**Shared Infrastructure:**
```
┌─────────────────────────────────────────────────────┐
│              HazelcastManager (Singleton)            │
│         Manages Hazelcast instance & maps            │
├─────────────────────────────────────────────────────┤
│  ActorSystem Clustering    │  TaskGraph Clustering  │
│  - actor:registry          │  - taskgraph:graph-states│
│  - actor:events            │  - taskgraph:task-states │
│                            │  - taskgraph:task-events │
└─────────────────────────────────────────────────────┘
```

**Package Structure:**
- `org.softwood.cluster.HazelcastManager` - Shared infrastructure singleton
- `org.softwood.actor.cluster.*` - Actor-specific clustering classes
- `org.softwood.dag.cluster.*` - TaskGraph-specific clustering classes

### When to Use Clustering

**✅ Use Clustering When:**
- High availability required (survive node failures)
- Multiple nodes in deployment  
- Actors need to be discoverable across cluster
- State synchronization across nodes needed
- Lifecycle events must be broadcast

**❌ Don't Use Clustering When:**
- Single node deployment
- Actors are truly local (no cross-node needs)
- Tight latency requirements (local-only is faster)
- Simple applications without HA needs

### HazelcastManager Setup

#### 1. Dependencies

```gradle
implementation 'com.hazelcast:hazelcast:5.6.0'
```

#### 2. Initialize HazelcastManager

```groovy
import org.softwood.cluster.HazelcastManager

// Get singleton instance
def hazelcastManager = HazelcastManager.instance

// Configure clustering
def clusterConfig = [
    enabled: true,
    port: 5701,
    cluster: [
        name: 'my-actor-cluster',
        members: [
            '192.168.1.10:5701',
            '192.168.1.11:5701',
            '192.168.1.12:5701'
        ]
    ]
]

// Initialize (call once at application startup)
hazelcastManager.initialize(clusterConfig)

// Verify clustering is enabled
assert hazelcastManager.isEnabled()
```

**Network Discovery Modes:**

**Multicast (Development):**
```groovy
def config = [
    enabled: true,
    port: 5701,
    cluster: [
        name: 'dev-cluster',
        members: []  // Empty = multicast
    ]
]
```

**TCP/IP (Production):**
```groovy
def config = [
    enabled: true,
    port: 5701,
    cluster: [
        name: 'prod-cluster',
        members: [
            'node1.prod.example.com:5701',
            'node2.prod.example.com:5701',
            'node3.prod.example.com:5701'
        ]
    ]
]
```

#### 3. Create ActorSystem with Clustering

```groovy
// ActorSystem automatically detects enabled HazelcastManager
def system = new ActorSystem("my-app")

// Create clustered actors (automatically registered)
def actor = system.actor {
    name 'ClusteredActor'
    onMessage { msg, ctx ->
        println "Processing on node: ${hazelcastManager.getInstance().cluster.localMember}"
        ctx.reply("Processed!")
    }
}

// Actor is now visible across all cluster nodes!
```

### Automatic Actor Registration

When clustering is enabled, ActorSystem **automatically**:

1. **Registers actors** in distributed `actor:registry` map
2. **Publishes lifecycle events** to `actor:events` topic
3. **Tracks status** (ACTIVE, INACTIVE)
4. **Updates metadata** (actor class, node location)

**No code changes needed** - existing actors automatically gain clustering!

```groovy
// Node 1: Create actor
def actor = system.actor {
    name 'DataProcessor'
    onMessage { msg, ctx -> /* ... */ }
}

// Node 2: Access same actor (discovered via cluster)
def processor = system.getActor('DataProcessor')
processor.tell([type: 'process', data: someData])
// Message routed to Node 1 automatically!
```

### Actor Registry Entry

**Class:** `org.softwood.actor.cluster.ActorRegistryEntry`

Each registered actor has metadata stored cluster-wide:

```groovy
class ActorRegistryEntry implements Serializable {
    final String actorId           // Actor name
    final String actorClass        // Actor implementation class
    final String nodeName          // Cluster member hosting this actor
    ActorStatus status             // ACTIVE or INACTIVE
    final Date createdAt           // Registration timestamp
    Date lastUpdated               // Last status change
    
    // Thread-safe status updates
    void markActive()
    void markInactive()
}
```

**Actor Status:**
```groovy
enum ActorStatus {
    ACTIVE,      // Actor running and accepting messages
    INACTIVE     // Actor stopped or unavailable
}
```

**Query Registry:**
```groovy
// Get actor registry map
def registry = hazelcastManager.getActorRegistryMap()

// Check if actor exists cluster-wide
def entry = registry.get('DataProcessor')
if (entry) {
    println "Actor: ${entry.actorId}"
    println "Status: ${entry.status}"
    println "Node: ${entry.nodeName}"
    println "Class: ${entry.actorClass}"
    println "Created: ${entry.createdAt}"
}

// List all actors across cluster
registry.keySet().each { actorName ->
    def info = registry.get(actorName)
    println "${actorName} @ ${info.nodeName} [${info.status}]"
}
```

### Actor Lifecycle Events

**Class:** `org.softwood.actor.cluster.ActorLifecycleEvent`

ActorSystem publishes events to the cluster for:
- Actor creation
- Actor start/stop
- Actor failure/restart

```groovy
enum ActorLifecycleEventType {
    CREATED,      // Actor registered in cluster
    STARTED,      // Actor began processing
    STOPPED,      // Actor stopped gracefully
    FAILED,       // Actor encountered error
    RESTARTED     // Actor restarted after failure
}

class ActorLifecycleEvent implements Serializable {
    final String actorId
    final ActorLifecycleEventType eventType
    final String nodeName
    final Date timestamp
    String reason                // Optional failure/stop reason
}
```

**Subscribe to Events:**
```groovy
// Get event topic
def eventTopic = hazelcastManager.getActorEventTopic()

// Listen for lifecycle events
eventTopic.addMessageListener { message ->
    ActorLifecycleEvent event = message.messageObject
    
    println "[${event.timestamp}] Actor '${event.actorId}' ${event.eventType} on ${event.nodeName}"
    
    if (event.eventType == ActorLifecycleEventType.FAILED) {
        println "Failure reason: ${event.reason}"
        // Take corrective action
    }
}
```

**Example Event Flow:**
```
Node 1: Creates 'Worker'
  → CREATED event published to cluster
  → STARTED event published to cluster
  
Node 2: Receives events, knows 'Worker' exists on Node 1

Node 1: Worker encounters error
  → FAILED event published with reason
  → RESTARTED event published after recovery
  
Node 1: Worker stops
  → STOPPED event published to cluster
```

### Status Tracking

ActorSystem automatically maintains actor status:

```groovy
// Actor created → Status: ACTIVE
def actor = system.actor { name 'Worker'; onMessage { ... } }

// Actor stopped → Status: INACTIVE
actor.stop()

// Registry automatically updated
def entry = hazelcastManager.getActorRegistryMap().get('Worker')
assert entry.status == ActorStatus.INACTIVE
```

**Manual Status Updates:**
```groovy
// In ActorSystem implementation
private void updateActorStatusInCluster(String actorName, ActorStatus newStatus) {
    synchronized (this) {
        ActorRegistryEntry entry = clusterActorRegistry.get(actorName)
        if (entry) {
            if (newStatus == ActorStatus.ACTIVE) {
                entry.markActive()
            } else {
                entry.markInactive()
            }
            clusterActorRegistry.replace(actorName, entry)  // Atomic update
        }
    }
}
```

### Cluster Configuration Options

**YAML Configuration:**
```yaml
# hazelcast.yaml
hazelcast:
  cluster-name: actor-cluster
  
  network:
    port:
      auto-increment: true
      port: 5701
    join:
      multicast:
        enabled: false
      tcp-ip:
        enabled: true
        member-list:
          - 192.168.1.10
          - 192.168.1.11
          - 192.168.1.12
  
  map:
    actor:registry:
      backup-count: 1              # Replicate to 1 other node
      time-to-live-seconds: 0      # No TTL (actors live forever)
      max-idle-seconds: 0          # No idle eviction
      
    actor:events:
      backup-count: 0              # Events don't need backup
```

### Graceful Shutdown

```groovy
// Shutdown ActorSystem (cleans up cluster entries)
system.shutdown()

// Shutdown HazelcastManager (on application exit)
hazelcastManager.shutdown()
```

### Known Issues & Limitations

#### Partition Initialization Race Condition

**Problem:** In test environments with rapid cluster initialization followed by immediate map access, there's a timing window where:
- `IMap.put()` succeeds
- `IMap.toString()` shows entries  
- `IMap.keySet()` returns keys
- **BUT `IMap.get()` returns null!**

**Affected:** Unit tests that create ActorSystem immediately after Hazelcast startup.

**Not Affected:** Production deployments (sufficient time between startup and first use).

**Workaround for Tests:**
```groovy
@BeforeEach
void setup() {
    hazelcastManager.initialize(config)
    
    // Pre-initialize actor registry map BEFORE tests
    def registry = hazelcastManager.getActorRegistryMap()
    
    // Verify map is truly ready with put/get test
    registry.put("_test_", new ActorRegistryEntry("test", "Test", "node"))
    Awaitility.await()
        .atMost(2, TimeUnit.SECONDS)
        .until({ registry.get("_test_") != null })
    registry.remove("_test_")
    
    // NOW tests can safely use registry
}
```

**Status:** Reported to Hazelcast team. See `HAZELCAST_BUG_REPORT.md` for details.

### Complete Example

```groovy
import org.softwood.cluster.HazelcastManager
import org.softwood.actor.ActorSystem
import org.softwood.actor.cluster.*

// 1. Initialize Hazelcast
def hazelcast = HazelcastManager.instance
hazelcast.initialize([
    enabled: true,
    port: 5701,
    cluster: [
        name: 'production-cluster',
        members: ['node1:5701', 'node2:5701', 'node3:5701']
    ]
])

// 2. Create ActorSystem
def system = new ActorSystem("prod-app")

// 3. Subscribe to cluster events
def events = hazelcast.getActorEventTopic()
events.addMessageListener { msg ->
    ActorLifecycleEvent event = msg.messageObject
    println "Cluster event: ${event.actorId} ${event.eventType} on ${event.nodeName}"
}

// 4. Create clustered actors
def worker = system.actor {
    name 'Worker'
    onMessage { msg, ctx ->
        println "Processing ${msg} on ${hazelcast.getInstance().cluster.localMember}"
        ctx.reply("Done")
    }
}

// 5. Monitor cluster
def registry = hazelcast.getActorRegistryMap()
registry.keySet().each { actorName ->
    def entry = registry.get(actorName)
    println "Actor: ${actorName} @ ${entry.nodeName} [${entry.status}]"
}

// 6. Graceful shutdown
Runtime.addShutdownHook {
    system.shutdown()
    hazelcast.shutdown()
}
```

### Performance Considerations

**Network Latency:**
- Cluster operations add ~1-5ms network overhead
- Local actors (same JVM) bypass network completely
- Use local actors for latency-critical paths

**Registry Size:**
- Each actor entry: ~200-500 bytes
- 10,000 actors ≈ 2-5 MB cluster memory
- Hazelcast distributes evenly across nodes

**Event Topic:**
- Events are fire-and-forget (no delivery guarantees)
- High event rate: Consider batching or sampling
- Events don't block actor operations

**Backup Strategy:**
- Default: 1 backup copy per map entry
- Survives single node failure
- Increase for higher availability

### Comparison: Clustering vs Remote Actors

| Feature | Hazelcast Clustering | Remote Actors (HTTP/RSocket) |
|---------|---------------------|-----------------------------|
| **Discovery** | Automatic (cluster registry) | Manual (explicit URIs) |
| **Failover** | Yes (automatic) | No (explicit retry logic) |
| **State Sync** | Built-in (distributed maps) | Manual (external store) |
| **Overhead** | Low (binary protocol) | Medium (HTTP) / Low (RSocket) |
| **Complexity** | Low (mostly transparent) | Medium (explicit routing) |
| **Use Case** | High availability clusters | Service-to-service | 

**When to combine both:**
- **Internal cluster**: Hazelcast for HA within cluster
- **External services**: Remote actors for cross-service communication

---

## Serialization Options

### Why Serialization Matters

For remote actors and clustering, messages must be serialized:
- **HTTP Transport**: Typically uses JSON
- **RSocket Transport**: Typically uses binary (MessagePack)
- **Hazelcast**: Uses Java serialization or custom serializers

### Pluggable Serializer Architecture

**Interface:**
```groovy
interface MessageSerializer {
    byte[] serialize(Object obj)
    Object deserialize(byte[] bytes, Class<?> type)
    String contentType()
}
```

### Built-in Serializers

| Serializer | Format | Use Case | Performance |
|------------|--------|----------|-------------|
| **JsonSerializer** | JSON | Human-readable, debugging | Medium |
| **MessagePackSerializer** | MessagePack | Compact binary, efficient | High |
| **MicroStreamSerializer** | MicroStream | Object graphs, no schema | High |
| **JavaSerializer** | Java | Built-in, simple | Low |

### JSON Serialization

**Default for HTTP Transport:**
```groovy
def httpTransport = new HttpTransport(
    host: "0.0.0.0",
    port: 8080,
    system: system,
    serializer: new JsonSerializer()  // Default
)
```

**Features:**
- Human-readable
- Cross-platform
- Easy debugging
- Browser-compatible

**Limitations:**
- Larger payload size
- Slower than binary
- Type information required

### MessagePack Serialization

**Default for RSocket Transport:**
```groovy
def rsocketTransport = new RSocketTransport(
    host: "0.0.0.0",
    port: 7000,
    system: system,
    serializer: new MessagePackSerializer()  // Default
)
```

**Features:**
- Compact binary format
- Fast serialization
- Cross-platform
- Schema-less

**Use When:**
- High throughput required
- Message size matters
- Binary transport available

### MicroStream Serialization

**For complex object graphs:**
```groovy
def transport = new RSocketTransport(
    host: "0.0.0.0",
    port: 7000,
    system: system,
    serializer: new MicroStreamSerializer()
)
```

**Features:**
- Preserves object graphs
- No schema required
- Handles circular references
- Native Java types

**Use When:**
- Complex domain objects
- Object relationships matter
- Performance critical

### Custom Serializer

```groovy
class ProtobufSerializer implements MessageSerializer {
    @Override
    byte[] serialize(Object obj) {
        // Convert to Protobuf
        return obj.toByteArray()
    }
    
    @Override
    Object deserialize(byte[] bytes, Class<?> type) {
        // Parse from Protobuf
        return type.parseFrom(bytes)
    }
    
    @Override
    String contentType() {
        return "application/x-protobuf"
    }
}

// Use custom serializer
def transport = new HttpTransport(
    serializer: new ProtobufSerializer()
)
```

---

## Configuration & Defaults

### Configuration Architecture

GroovyActor uses `ConfigLoader` for hierarchical configuration:

```
1. Default configuration (built-in)
2. Classpath configuration (application.conf)
3. External configuration file
4. Environment variables
5. System properties
6. Programmatic overrides
```

### Default Configuration

**Location:** `src/main/resources/reference.conf`

```yaml
groovy-actor:
  # Actor System Defaults
  system:
    default-timeout: 5s
    shutdown-timeout: 30s
    
  # Executor Pool Defaults
  executor:
    core-pool-size: 10
    max-pool-size: 100
    keep-alive-time: 60s
    queue-size: 1000
    
  # Actor Defaults
  actor:
    max-mailbox-size: 1000
    max-errors-retained: 100
    default-ask-timeout: 5s
    
  # Scheduler Defaults
  scheduler:
    thread-pool-size: 4
    
  # Supervision Defaults
  supervision:
    max-retries: 3
    retry-delay: 1s
    default-strategy: restart
    
  # Remote Transport Defaults
  remoting:
    http:
      enabled: false
      host: "0.0.0.0"
      port: 8080
      tls-enabled: false
      
    rsocket:
      enabled: false
      host: "0.0.0.0"
      port: 7000
      tls-enabled: false
      
  # Serialization Defaults
  serialization:
    default: "json"
    json:
      pretty-print: false
    messagepack:
      buffer-size: 8192
      
  # Clustering Defaults  
  clustering:
    enabled: false
    cluster-name: "actor-cluster"
    
  # Monitoring Defaults
  monitoring:
    metrics-enabled: true
    health-check-enabled: true
    jmx-enabled: false
```

### Loading Configuration

**Classpath Configuration:**
```yaml
# src/main/resources/application.conf
groovy-actor:
  system:
    default-timeout: 10s
    
  actor:
    max-mailbox-size: 5000
    
  remoting:
    http:
      enabled: true
      port: 9090
```

**External Configuration:**
```groovy
// Load from external file
def config = ConfigLoader.load("/etc/myapp/actor.conf")
def system = new ActorSystem("app", config)
```

**Programmatic Override:**
```groovy
def config = ConfigLoader.load()
config.set("groovy-actor.actor.max-mailbox-size", 10000)
config.set("groovy-actor.remoting.http.enabled", true)

def system = new ActorSystem("app", config)
```

**Environment Variables:**
```bash
export GROOVY_ACTOR_SYSTEM_DEFAULT_TIMEOUT=15s
export GROOVY_ACTOR_REMOTING_HTTP_PORT=9090
```

### Accessing Configuration in Actors

```groovy
def actor = system.actor {
    name 'ConfigAwareActor'
    
    onMessage { msg, ctx ->
        def config = ctx.system.config
        
        def timeout = config.getDuration("groovy-actor.system.default-timeout")
        def maxMailbox = config.getInt("groovy-actor.actor.max-mailbox-size")
        
        println "Configured timeout: ${timeout}"
        println "Max mailbox: ${maxMailbox}"
    }
}
```

### Configuration Best Practices

**Development:**
```yaml
groovy-actor:
  actor:
    max-mailbox-size: 100      # Low for quick feedback
  monitoring:
    metrics-enabled: true       # Enable for debugging
    jmx-enabled: true
```

**Production:**
```yaml
groovy-actor:
  actor:
    max-mailbox-size: 10000    # High for throughput
  monitoring:
    metrics-enabled: true
    jmx-enabled: false          # Security concern
  remoting:
    http:
      tls-enabled: true         # Always use TLS
```

---

## Best Practices & Patterns

### Groovy Closure Safety

For deeply nested closures (6+ levels), use the safe helpers:

**❌ Problematic:**
```groovy
def counts = [:]  // Fails in nested closures
files.each { file ->
    counts[ext] = counts[ext] + 1  // Error!
}

// Creating actors in .each
items.each { item ->
    system.actor { ... }  // Scoping issues!
}
```

**✅ Safe:**
```groovy
// Use CounterMap
def counts = ctx.createCounterMap()
files.each { file ->
    counts.increment(ext)  // Safe!
}

// Use spawnForEach
ctx.spawnForEach(items, 'worker') { item, idx, msg, ctx ->
    // Safe actor creation!
}
```

**See:** `docs/GROOVY_CLOSURE_GOTCHAS.md` for complete guide.

### ActorPatterns - Pre-Built Patterns

`ActorPatterns` is a utility class providing tested, production-ready patterns for common actor scenarios. These patterns handle Groovy closure scoping issues automatically.

#### Available Patterns

**1. Aggregator Pattern**

Collects results from multiple workers before triggering completion:

```groovy
// Pattern signature
ActorPatterns.aggregatorPattern(int expectedCount, Closure completionHandler)

// Usage
def aggregator = system.actor {
    name 'ResultAggregator'
    onMessage ActorPatterns.aggregatorPattern(5) { results, ctx ->
        // Called when all 5 results received
        println "Complete! Results: ${results}"
        ctx.reply([status: 'done', data: results])
    }
}

// Workers send results
workers.each { worker ->
    worker.tell([type: 'process'])
    // Worker sends: aggregator.tell([type: 'result', data: someValue])
}
```

**How it works:**
- Maintains internal count of received results
- Collects results in order of arrival
- Calls completion handler when count reached
- Thread-safe result collection

**Use cases:**
- Scatter-gather pattern
- Coordinating multiple workers
- Batch processing with multiple stages
- Parallel computation aggregation

#### Helper Methods

**getExtension(String filename)**

Safely extracts file extensions:

```groovy
def ext = ActorPatterns.getExtension("document.pdf")  // "pdf"
def ext = ActorPatterns.getExtension("README")        // "no-extension"
def ext = ActorPatterns.getExtension(null)            // "no-extension"
```

**uniqueName(String baseName)**

Generates unique actor names:

```groovy
def name = ActorPatterns.uniqueName("worker")  // "worker-a3f42d1e"
def name = ActorPatterns.uniqueName("task")    // "task-b9e21c4f"
```

**Benefits:**
- Collision-free naming
- Useful for dynamic actor creation
- 8-character UUID suffix for readability

### Common Patterns

#### 1. Request-Response
```groovy
def worker = system.actor {
    name 'Worker'
    onMessage { msg, ctx ->
        def result = processWork(msg)
        ctx.reply(result)
    }
}

// Synchronous
def result = worker.askSync("task", Duration.ofSeconds(5))

// Asynchronous
worker.ask("task", Duration.ofSeconds(5)).onComplete { result ->
    println "Got: ${result}"
}
```

#### 2. Fire-and-Forget
```groovy
def logger = system.actor {
    name 'Logger'
    onMessage { msg, ctx ->
        println "[${new Date()}] ${msg}"
        // No reply needed
    }
}

logger.tell("Something happened")
```

#### 3. Aggregator Pattern
```groovy
def aggregator = system.actor {
    name 'Aggregator'
    onMessage ActorPatterns.aggregatorPattern(5) { results, ctx ->
        println "All 5 results collected: ${results}"
        ctx.reply(results)
    }
}

// Send results from workers
(1..5).each { i ->
    aggregator.tell([type: 'result', data: "Result ${i}"])
}
```

#### 4. Supervisor Pattern
```groovy
def supervisor = system.actor {
    name 'Supervisor'
    
    onMessage { msg, ctx ->
        if (msg == 'create-workers') {
            ctx.spawnForEach(1..10, 'worker') { id, idx, workerMsg, workerCtx ->
                // Worker logic
                if (workerMsg == 'work') {
                    // Might fail
                    performRiskyWork()
                }
            }
        }
        
        if (msg instanceof Terminated) {
            println "Worker failed, restarting..."
            ctx.spawn(msg.actor.name) { ... }
        }
    }
}
```

#### 5. Circuit Breaker
```groovy
def circuitBreaker = system.actor {
    name 'CircuitBreaker'
    state failures: 0, state: 'CLOSED'
    
    onMessage { msg, ctx ->
        if (ctx.state.state == 'OPEN') {
            ctx.reply([error: 'Circuit open, try later'])
            return
        }
        
        try {
            def result = callExternalService(msg)
            ctx.state.failures = 0
            ctx.reply(result)
        } catch (Exception e) {
            ctx.state.failures++
            if (ctx.state.failures >= 5) {
                ctx.state.state = 'OPEN'
                ctx.scheduleOnce(Duration.ofSeconds(30), 'reset')
            }
            ctx.reply([error: e.message])
        }
        
        if (msg == 'reset') {
            ctx.state.state = 'CLOSED'
        }
    }
}
```

### Performance Tips

**1. Mailbox Sizing:**
```groovy
// High-throughput actors
actor.setMaxMailboxSize(10000)

// Low-latency actors  
actor.setMaxMailboxSize(100)
```

**2. Pool Configuration:**
```groovy
// Dedicated pool for critical actors
def criticalPool = ExecutorPoolFactory.builder()
    .name("critical-pool")
    .corePoolSize(20)
    .build()

def actor = ActorFactory.builder("critical", handler)
    .poolOwned(criticalPool)
    .build()
```

**3. Async Operations:**
```groovy
def actor = system.actor {
    name 'AsyncWorker'
    onMessage { msg, ctx ->
        ctx.deferReply()  // Don't auto-reply
        
        CompletableFuture.supplyAsync({
            // Long-running work
            performWork(msg)
        }).thenAccept { result ->
            ctx.reply(result)
        }
    }
}
```

### Error Handling Best Practices

**1. Use onError:**
```groovy
def actor = system.actor {
    name 'ResilientActor'
    onMessage { msg, ctx ->
        riskyOperation(msg)
    }
}

actor.onError { error ->
    println "Error occurred: ${error.message}"
    // Log, alert, etc.
}
```

**2. Defensive Programming:**
```groovy
def actor = system.actor {
    name 'SafeActor'
    onMessage { msg, ctx ->
        try {
            processMessage(msg)
        } catch (ValidationException e) {
            ctx.reply([error: "Invalid: ${e.message}"])
        } catch (Exception e) {
            log.error("Unexpected error", e)
            ctx.reply([error: "Internal error"])
        }
    }
}
```

### Testing Actors

```groovy
class MyActorTest {
    ActorSystem system
    
    @BeforeEach
    void setup() {
        system = new ActorSystem('test')
    }
    
    @AfterEach
    void cleanup() {
        system.shutdown()
    }
    
    @Test
    void testActorBehavior() {
        def actor = system.actor {
            name 'TestActor'
            onMessage { msg, ctx ->
                ctx.reply(msg.toUpperCase())
            }
        }
        
        def result = actor.askSync("hello", Duration.ofSeconds(2))
        assertEquals("HELLO", result)
    }
}
```

---

## @SafeActor - Compile-Time Safety

### What is @SafeActor?

`@SafeActor` is an **optional** AST (Abstract Syntax Tree) transformation that detects common Groovy closure pitfalls **at compile-time**. It helps you write safer actor code by catching problems before they cause runtime issues.

**Key Point:** @SafeActor is a **development aid**, not a requirement. All actor features work perfectly without it.

### What Problems Does It Detect?

#### 1. Actor Creation Inside `.each{}` Closures

**Problem:** Variable capture in nested closures causes subtle bugs:

```groovy
// ❌ PROBLEMATIC - @SafeActor warns
def items = ['A', 'B', 'C']
items.each { item ->
    system.actor {
        name "worker-${item}"
        onMessage { msg, ctx ->
            println "Processing ${item}"  // May print wrong value!
        }
    }
}
```

**Why It Fails:**
- The closure captures `item` by reference, not value
- By the time the actor runs, `item` might have changed
- All actors might see the same (last) value

**@SafeActor Warning:**
```
WARNING: Creating actor inside .each{} closure at line 45
Variable 'item' may not be captured correctly.
Consider using ctx.spawnForEach() instead.
```

#### 2. Deep Nesting of `.each{}` (6+ levels)

**Problem:** Deep nesting makes code hard to maintain and prone to errors:

```groovy
// ⚠️ WARNING - @SafeActor warns at 6+ levels
groups.each { group ->           // Level 1
    group.items.each { item ->    // Level 2
        item.tasks.each { task ->  // Level 3
            task.steps.each { step -> // Level 4
                step.actions.each { action -> // Level 5
                    action.cmds.each { cmd ->  // Level 6 ⚠️
                        // Too deep!
                    }
                }
            }
        }
    }
}
```

**@SafeActor Warning:**
```
WARNING: .each{} nesting depth exceeds 5 levels at line 52
Consider refactoring into separate methods or using iterators.
```

#### 3. Manual Map Manipulation in Closures

**Problem:** `HashMap` operations in nested closures fail silently:

```groovy
// ❌ PROBLEMATIC
def counts = [:]
files.each { file ->
    def ext = getExtension(file)
    counts[ext] = counts[ext] + 1  // NullPointerException or wrong value
}
```

**@SafeActor Suggestion:**
```
INFO: Consider using CounterMap for safer counting:
  def counts = ctx.createCounterMap()
  files.each { file -> counts.increment(ext) }
```

### How to Use @SafeActor

#### 1. Apply to Actor Creation Closures

```groovy
import org.softwood.actor.transform.SafeActor

@SafeActor
def myActor = system.actor {
    name 'MyActor'
    
    onMessage { msg, ctx ->
        // @SafeActor checks this entire closure
        if (msg.type == 'process') {
            // Warns if you create actors inside .each{}
            // Warns if nesting is too deep
            // Suggests safer alternatives
        }
    }
}
```

#### 2. Apply to Entire Classes

```groovy
@SafeActor
class MyActorCoordinator {
    ActorSystem system
    
    def createWorkers() {
        def items = getItems()
        
        // @SafeActor checks all methods
        items.each { item ->
            system.actor { ... }  // WARNING: Use ctx.spawnForEach()
        }
    }
}
```

### Safe Alternatives (Recommended Patterns)

#### Pattern 1: Use `ctx.spawnForEach()`

```groovy
@SafeActor
def coordinator = system.actor {
    name 'Coordinator'
    
    onMessage { msg, ctx ->
        if (msg.type == 'spawn-workers') {
            // ✅ SAFE: Designed for this use case
            def workers = ctx.spawnForEach(msg.items, 'worker') { item, index, workerMsg, workerCtx ->
                println "Worker ${index}: Processing ${item}"
                // 'item' is correctly captured!
            }
            
            ctx.reply("Created ${workers.size()} workers")
        }
    }
}
```

**Why It Works:**
- `spawnForEach()` handles variable capture correctly
- Each actor gets its own copy of `item`
- No closure scoping issues

#### Pattern 2: Use Traditional For Loop

```groovy
@SafeActor
def coordinator = system.actor {
    name 'Coordinator'
    
    onMessage { msg, ctx ->
        def items = msg.items
        
        // ✅ SAFE: Traditional for loop
        for (int i = 0; i < items.size(); i++) {
            def item = items[i]  // Explicit local variable
            def index = i
            
            ctx.spawn("worker-${index}") { workerMsg, workerCtx ->
                println "Processing ${item}"  // Correctly captured
            }
        }
    }
}
```

**Why It Works:**
- Explicit variable declaration in each iteration
- No hidden closure capture
- Clear, predictable behavior

#### Pattern 3: Use `CounterMap` for Counting

```groovy
@SafeActor
def analyzer = system.actor {
    name 'Analyzer'
    
    onMessage { msg, ctx ->
        // ✅ SAFE: CounterMap handles closure issues
        def counts = ctx.createCounterMap()
        
        msg.files.each { file ->
            def ext = file.substring(file.lastIndexOf('.') + 1)
            counts.increment(ext)  // Works in any nesting level!
        }
        
        ctx.reply([
            total: counts.total(),
            breakdown: counts.toMap()
        ])
    }
}
```

**Why It Works:**
- `CounterMap` designed for nested closures
- Thread-safe concurrent operations
- No null pointer issues

#### Pattern 4: Use `ActorPatterns` Helpers

```groovy
@SafeActor
def aggregator = system.actor {
    name 'Aggregator'
    
    // ✅ SAFE: Pre-built pattern handles complexity
    onMessage ActorPatterns.aggregatorPattern(5) { results, ctx ->
        println "Collected ${results.size()} results"
        ctx.reply(results)
    }
}
```

### When to Use @SafeActor

**✅ Use @SafeActor When:**
- You're creating complex actor hierarchies
- You have nested closures and loops
- You're new to Groovy closure semantics
- You want extra safety during development
- You're refactoring existing code

**❌ Don't Need @SafeActor When:**
- Simple, flat actor structures
- You're already using safe patterns (spawnForEach, CounterMap)
- You understand Groovy closure capture deeply
- You have comprehensive test coverage

### Configuration

@SafeActor is configurable via compiler configuration:

```groovy
// build.gradle
compileGroovy {
    groovyOptions.configurationScript = file('config/safeactor.groovy')
}
```

```groovy
// config/safeactor.groovy
withConfig(configuration) {
    ast(SafeActor) {
        maxEachNesting = 5           // Warn at 6+ levels (default: 5)
        warnOnActorInEach = true     // Warn about actors in .each{} (default: true)
        suggestCounterMap = true     // Suggest CounterMap usage (default: true)
    }
}
```

### Example Output

When @SafeActor detects issues:

```
Compilation warnings for: MyActor.groovy

[Line 45] WARNING: Creating actor inside .each{} closure
          Variable 'item' may not be captured correctly.
          
          Current code:
              items.each { item ->
                  system.actor { name "worker-${item}" ... }
              }
          
          Suggested fix:
              ctx.spawnForEach(items, 'worker') { item, idx, msg, ctx ->
                  // item is correctly captured here
              }

[Line 67] WARNING: .each{} nesting depth is 6 (exceeds limit of 5)
          Consider refactoring into separate methods.

[Line 89] INFO: Manual HashMap counting detected
          Consider using CounterMap:
              def counts = ctx.createCounterMap()
              items.each { counts.increment(key) }
```

### Summary: @SafeActor Benefits

| Benefit | Description |
|---------|-------------|
| **Catch bugs early** | Find issues at compile-time, not runtime |
| **Learn best practices** | Warnings teach you safer patterns |
| **Reduce debugging time** | Avoid subtle closure capture bugs |
| **Improve code quality** | Encourages cleaner, more maintainable code |
| **Optional** | Use only when you need it, no runtime overhead |

### See Also

- **SafeActorExample.groovy** - Complete working examples
- **GROOVY_CLOSURE_GOTCHAS.md** - Detailed closure safety guide
- **ActorNewFeaturesTest.groovy** - Test suite demonstrating patterns

---

## Summary

### Key Takeaways

1. **Use ActorSystem** - Primary entry point for production code
2. **Leverage Helpers** - CounterMap, spawnForEach for safe closures
3. **Understand Lifecycle** - PreStart, PostStop, DeathWatch
4. **Choose Transport** - HTTP (simple), RSocket (performance)
5. **Configure Properly** - Use ConfigLoader, environment-specific configs
6. **Monitor & Debug** - Health checks, metrics, error handling
7. **Test Thoroughly** - Use Awaitility for async assertions

### Further Reading

- **GROOVY_CLOSURE_GOTCHAS.md** - Comprehensive closure safety guide
- **API Documentation** - Generated Javadoc
- **Example Scripts** - `src/main/groovy/org/softwood/sampleScripts/actors/`

### Getting Help

- Issues: GitHub Issues
- Discussions: GitHub Discussions
- Examples: Check test suite for usage patterns