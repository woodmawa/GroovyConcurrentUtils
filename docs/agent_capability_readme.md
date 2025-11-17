# Agent – Thread-Safe Wrapper with Virtual Threads
The Agent<T> type provides a thread-safe façade over a non-thread-safe object. All modifications to the wrapped object are serialized and executed on an executor (virtual threads by default), while readers obtain defensive snapshots via getVal().
 
## Why use Agent?
Use Agent when:
- You have some mutable state (Map, List, POJO) that is not thread-safe.
- You want to update it from many threads without locks or explicit synchronization.
- You are happy for mutations to be sequential (one at a time).
- You want readers to see a copy of the state, not the live object.

It’s essentially a tiny actor/agent abstraction sitting on top of a single object.
 
## Core guarantees
Sequential updates
- All closures submitted via send / sendAndGet execute one after another on a single logical “agent worker”.
- Thread-safe access
- Callers never touch the wrapped object directly; they only interact through closures that run on the agent’s executor.
- Defensive snapshots
- getVal() returns a deep copy of the current wrapped object:
- collections and maps are recursively copied;
- changes to the snapshot do not affect the agent’s internal state.
- Virtual threads by default
By default, the agent uses a virtual-thread-based ExecutorService obtained from ConcurrentPool, so blocking inside closures is cheap.
 
# API overview
## Creation
``` groovy
import org.softwood.agent.Agent

// Default copy strategy (deep copy via reflection)
def agent = Agent.agent([count: 0, items: []]).build()

// With custom copy strategy
def agent2 = Agent.agent(new MyState())
                  .immutableCopyBy { state -> state.clone() }  // example
                  .build()

// With external executor (Agent will NOT own/shutdown it)
def customExec = Executors.newFixedThreadPool(4)
def agent3 = new Agent(new MyState(), null, customExec)
```

The builder form is usually the nicest:
``` groovy
def agent = Agent.agent([count: 0, items: []])
                 .immutableCopyBy { state -> new HashMap<>(state) } // optional
                 .build()
```

## Usage 

Async updates (send / async / >>)
``` groovy
agent.send { count++ }           // fire-and-forget
agent.async { items << 1 }      // alias

agent >> { count += 10 }        // operator alias for async
```

These methods queue the closure to run against the wrapped object.
Inside the closure:
delegate is the wrapped object.
resolveStrategy is DELEGATE_FIRST.
So you can write:
``` groovy
agent.send {
    count++
    items << count
}
```

without explicitly referencing the underlying map/list.
Sync updates (sendAndGet / sync / <<)
``` groovy
def result = agent.sendAndGet {
    count += 2
    count
}

def result2 = agent.sync { count }

def result3 = (agent << { count })  // operator alias for sync
```

sendAndGet / sync block the caller until the closure completes and return its result. Because the agent uses virtual threads by default, this is cheap in terms of threading, but it is still logically blocking.
You can also specify a timeout in seconds:
``` groovy
def value = agent.sendAndGet({
    Thread.sleep(500)   // simulate work
    count
}, 1L) // 1-second timeout
```

If the work completes within the timeout, its result is returned.
If the timeout expires, a RuntimeException is thrown with a “timed out” message.
 
Reading state (getVal / val)
``` groovy
def snapshot = agent.val      // Groovy property access
println snapshot.count
println snapshot.items
```

val is a deep copy of the wrapped object:
nested lists and maps are recursively copied;
other nested objects are copied via reflection when possible.
You can mutate this snapshot freely; it will not modify the agent’s internal state.
Example:
``` groovy
def agent = Agent.agent([count: 0, items: [1, 2, 3]]).build()

agent.sendAndGet {
    count = 5
    items << 4
}

def snapshot1 = agent.val
assert snapshot1.count == 5
assert snapshot1.items == [1, 2, 3, 4]

// Mutating the snapshot does NOT affect the agent
snapshot1.count = 999
snapshot1.items << 999

def snapshot2 = agent.val
assert snapshot2.count == 5
assert snapshot2.items == [1, 2, 3, 4]
```

 
## Error handling
Async (send / async / >>)
If an exception is thrown inside a closure submitted via send:
- The exception is logged via SLF4J.
- The agent continues processing subsequent tasks.
- The error does not propagate back to the caller of send.
- Sync (sendAndGet / sync / <<)
For sendAndGet and sync:
If the closure throws a RuntimeException, it is re-thrown to the caller.
If it throws a checked exception or any other Throwable, it is wrapped in a RuntimeException with a descriptive message.
Example:
``` groovy
def agent = Agent.agent([value: 1]).build()

try {
    agent.sendAndGet {
        throw new IllegalArgumentException("boom")
    }
} catch (IllegalArgumentException e) {
    assert e.message == "boom"
}
```

 
## Lifecycle and executor ownership
Each Agent has an ExecutorService used for its internal worker.
If you construct the agent without providing an executor, it creates its own (via ConcurrentPool) and owns it.
If you pass an external executor, the agent does not shut it down.
Use shutdown() when you are done with an agent:
``` groovy
agent.shutdown()
```

If the agent owns its executor, that executor is shutdown; otherwise, the call is a no-op for the executor and you are responsible for shutting it down.
 
## Performance and large graphs
Deep copying arbitrary object graphs is powerful but can be expensive and dangerous if the graphs are huge or cyclic.
Some practical guidelines:
1. Keep agent state small and focused
Agents are best for:
- small to moderate-sized maps (e.g. config, counters, small aggregates),
- small POJOs with a few fields.
- Avoid using agents to wrap very large graphs (e.g. big in-memory databases, caches, or highly connected object graphs), because each getVal() will walk the entire graph.
2. Use custom copy strategies for heavy state
If your state type is large or complex, plug in a custom copy strategy via the builder:
``` groovy
def agent = Agent.agent(expensiveState)
                 .immutableCopyBy { state ->
                     // Example: return a cheap immutable view or a slim DTO
                     new SlimView(
                         state.importantField,
                         new ArrayList<>(state.someList)
                     )
                 }
                 .build()
```

This bypasses the generic reflection-based deep-copy, which is safer and more efficient for known types.
3. Be careful with cycles
The generic deep-copy logic does not do full graph-cycle detection. If you store cyclic structures (e.g. parent/child back-references) inside the agent’s state, you can end up with:
infinite recursion, or
very deep recursion and stack overflow.
For such graphs, either:
use a custom immutableCopyBy that knows how to copy your structure safely, or
consider using immutable data structures instead of mutable graphs.
4. Optional safeguards (max depth / max nodes)
If you need hard protection, you can extend the deep copy implementation to:
track recursion depth,
count how many objects/nodes have been copied,
either detect cycles via an IdentityHashMap (seen map), or
abort when limits are exceeded.
A simple pattern (conceptual):
``` groovy
private Object deepCopyValue(Object value,
                             Map<Object, Object> seen,
                             int depth,
                             int maxDepth,
                             int maxNodes) {
    if (value == null) return null
    if (depth > maxDepth || seen.size() > maxNodes) {
        throw new IllegalStateException("Deep copy limit exceeded")
    }

    if (seen.containsKey(value)) {
        return seen[value]  // handle cycles / shared references
    }

    // create a shallow copy instance (collection/map/object)
    // put it into seen, then recursively deepCopyValue children
}
```

You don’t have to add this immediately, but it’s a good pattern if you ever need strict safety for arbitrary inputs.
 
## When NOT to use Agent
Consider alternatives if:
- You need high-throughput parallel updates to independent pieces of state: shard your state across multiple agents, or use other concurrency primitives.
- Your state is already immutable: just share it; you don’t gain much from wrapping it in an agent.
- You need hard real-time guarantees: deep copying and queueing introduce latency and may be too unpredictable.
 
# Summary
Agent<T> is a compact, Groovy-friendly abstraction for:
sequential, thread-safe updates to arbitrary mutable state,
virtual-thread-based execution of state-changing closures,
deep, defensive snapshots via getVal().
Use send / async / >> for fire-and-forget, sendAndGet / sync / << for request-reply, and immutableCopyBy when you need fine-grained control over snapshot behaviour or performance.