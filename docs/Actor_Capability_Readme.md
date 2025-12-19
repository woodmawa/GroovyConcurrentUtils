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

### What is Hazelcast Clustering?

Hazelcast provides distributed data structures for actor clustering:

**Features:**
- Distributed actor registry (actors visible across all nodes)
- Automatic failover (actors can be restarted on other nodes)
- Load distribution (actors spread across cluster)
- Discovery (nodes find each other automatically)

### When to Use Clustering

**✅ Use Clustering When:**
- You need high availability (survive node failures)
- You want automatic load distribution
- You need transparent actor location
- You have multiple nodes in your deployment

**❌ Don't Use Clustering When:**
- Single node deployment
- Actors are truly local (no cross-node communication)
- Tight latency requirements (local-only faster)
- Simple applications

### Hazelcast Setup

**Dependencies:**
```gradle
implementation 'com.hazelcast:hazelcast:5.6.0'
```

**Configuration:**
```groovy
// 1. Create Hazelcast instance
def hazelcastConfig = new Config()
hazelcastConfig.clusterName = "actor-cluster"

// Network configuration
hazelcastConfig.networkConfig.join.multicastConfig.enabled = false
hazelcastConfig.networkConfig.join.tcpIpConfig.enabled = true
hazelcastConfig.networkConfig.join.tcpIpConfig.addMember("node1:5701")
hazelcastConfig.networkConfig.join.tcpIpConfig.addMember("node2:5701")

def hazelcast = Hazelcast.newHazelcastInstance(hazelcastConfig)

// 2. Create distributed actor registry
def registry = new HazelcastActorRegistry(hazelcast)

// 3. Create actor system with distributed registry
def system = new ActorSystem("my-app", registry)

// 4. Create actors (now visible cluster-wide)
def actor = system.actor {
    name 'ClusteredActor'
    onMessage { msg, ctx ->
        println "Processing on node: ${hazelcast.cluster.localMember.address}"
        ctx.reply("Processed!")
    }
}
```

### Cluster Features

**Actor Discovery:**
```groovy
// Actors are automatically visible across nodes
def actor = system.getActor("ClusteredActor")
// Works even if actor is on different node!
```

**Distributed State:**
```groovy
// Use Hazelcast distributed data structures
def sharedMap = hazelcast.getMap("shared-state")

def actor = system.actor {
    name 'StatefulActor'
    onMessage { msg, ctx ->
        // Local state (per-actor)
        ctx.state.localCounter++
        
        // Shared state (cluster-wide)
        sharedMap.put("globalCounter", sharedMap.get("globalCounter", 0) + 1)
    }
}
```

**Failover:**
```groovy
// Configure actor for failover
def actor = system.actor {
    name 'FailsafeActor'
    clusterAware true  // Can migrate to other nodes
    
    onMessage { msg, ctx ->
        // If this node fails, actor restarts on another node
    }
}
```

### Cluster Configuration Options

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
    actor-registry:
      backup-count: 1
      time-to-live-seconds: 0
      max-idle-seconds: 0
```

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
