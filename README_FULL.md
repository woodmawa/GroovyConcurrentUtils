# Actor Framework – DSL, ScopedValueActor, Supervision, Architecture

A lightweight Groovy actor framework offering:

- A flexible actor-definition DSL  
- Pattern matching  
- Actor-local scoped state  
- Deterministic mailbox execution  
- Supervision strategies (restart/resume/stop/escalate)  
- Structured error handling  
- Clean architecture for building systems based on message‑passing concurrency  

---

# Table of Contents
1. Overview  
2. Architecture  
   - High-Level Architecture  
   - Execution Flow  
   - Components  
3. Library Internals  
4. Sequence Diagrams  
   - Actor Creation  
   - Message Handling  
   - Pattern Matching Dispatch  
5. Supervision & Error Handling  
6. DSL Features  
7. Usage Examples  

---

# Overview

This library provides:

- **ActorSystem** for lifecycle and message routing  
- **ScopedValueActor** for isolated state and sequential message execution  
- A powerful **DSL** for actor construction  

---

# Architecture

## High‑Level Architecture

```
 ┌────────────────────────────┐
 │        ActorSystem         │
 │  - Creates actors          │
 │  - Holds registry          │
 │  - Manages lifecycle       │
 └──────────────┬─────────────┘
                │ createActor()
                ▼
 ┌────────────────────────────┐
 │  ActorBuilder (DSL layer)  │
 │  - name                    │
 │  - initialState            │
 │  - handlers / patterns     │
 └──────────────┬─────────────┘
                │ build()
                ▼
 ┌────────────────────────────┐
 │      ScopedValueActor      │
 │  - Mailbox queue           │
 │  - run loop                │
 │  - per‑actor scoped state  │
 │  - invokes handler closure │
 └────────────────────────────┘
```

---

## Execution Flow

1. Developer defines an actor in the DSL  
2. ActorBuilder merges handlers/patterns  
3. ActorSystem constructs a ScopedValueActor  
4. Messages processed sequentially in mailbox loop  

---

## Components

### ActorSystem
- Manages lifecycle, creation, removal, lookup  
- Provides request/reply support  

### ActorBuilder
- Collects name/state/handlers  
- Supports `onMessage`, `react`, `when`, `otherwise`  
- Produces final handler closure  

### ScopedValueActor
- Receives messages in order  
- Executes final handler with `ActorContext`  
- Maintains per‑actor scoped state  

---

# Library Internals

## Actor Creation Pipeline

```
DSL Script  
     ↓  
ActorSystem.actor { ... }  
     ↓  
ActorBuilder collects DSL  
     ↓  
Build merges handlers/patterns  
     ↓  
ScopedValueActor(name, state, handler)
```

---

## Message Processing Loop

Conceptual form:

```
while (running) {
    msg = mailbox.take()
    ctx.sender = resolveSender(msg)
    handler(msg.payload, ctx)
}
```

---

## Pattern Matching Engine

```
for pattern in patterns:
    if pattern.matcher matches msg:
         invoke handler
         return

if otherwise exists:
    call otherwise
```

---

# Sequence Diagrams

## Actor Creation Sequence

```
User Code                 ActorSystem              ActorBuilder              ScopedValueActor
    |                          |                        |                           |
    | system.actor {…}         |                        |                           |
    |------------------------->| new ActorBuilder()     |                           |
    |                          |----------------------->| collect DSL fields        |
    |                          | builder.build()        |                           |
    |                          |----------------------->| build handler             |
    |                          | createActor(...)       |                           |
    |                          |----------------------------------------------->     |
```

---

## Message Handling Sequence

```
Sender                    Actor                 Handler
  |                        |                      |
  | send(msg)              |                      |
  |----------------------->|                      |
  |                        | dispatch loop        |
  |                        |--------------------->| handler(msg, ctx)
  |                        |                      |
```

---

## Pattern Matching Dispatch

```
dispatcher receives msg  
        ↓  
iterate patterns  
        ↓  
Class match? → yes → invoke handler  
Predicate match? → yes → invoke handler  
Otherwise → fallback handler  
```

---

# Supervision & Error Handling

Implemented by **ActorSupervisor**.

Supervised actors send failure reports:

```
{
  actor: "actorName",
  error: Throwable
}
```

## Supervision Strategies

| Strategy | Meaning |
|---------|---------|
| **RESTART** | Clear state and continue |
| **RESUME** | Ignore failure and continue |
| **STOP** | Remove actor |
| **ESCALATE** | Send failure upward (future) |

---

## Restart Throttling

Each restart timestamp is recorded:

```
history.add(now)
history.removeAll { entry older than restartWindow }
if history.size > maxRestarts:
    stop actor
```

---

## Supervision Sequence

```
Actor                 Supervisor Actor         Supervisor Logic
  | throw exception        |                        |
  |----------------------->| failure report         |
  |                        |----------------------->| apply strategy
  |                        |                        |
```

---

# DSL Features

- `onMessage { msg, ctx -> ... }`  
- `react { msg, ctx -> ... }`  
- `when(Class) { ... }`  
- `when(predicate) { ... }`  
- `otherwise { ... }`  
- Per‑actor state via:

```
state counter: 0
ctx.state.counter++
```

---

# Usage Examples

## Pattern Matching Example

```groovy
def router = system.actor {
    name "router"

    when(String) { msg -> println "String: $msg" }
    when(Number) { msg -> println "Number: $msg" }

    otherwise { msg -> println "Unhandled: $msg" }
}
```

---

## Stateful Actor Example

```groovy
def counter = system.actor {
    name "counter"
    state count: 0

    onMessage { msg, ctx ->
        if (msg == "inc") ctx.state.count++
        if (msg == "get") ctx.reply(ctx.state.count)
    }
}
```

---

## Supervision Example

```groovy
def supervisor = new ActorSupervisor("root", system)
supervisor.supervise("worker1", SupervisionStrategy.RESTART, 3)
```

---

# End of README
