# Groovy Actor System (Virtual Threads + ScopedValue + CompileStatic)

A lightweight, production-ready actor framework built with:

- **Java 21–25 virtual threads**
- **ScopedValue** for safe, per-message context
- **Groovy @CompileStatic** for performance and type safety
- **Zero external dependencies**

This project provides:

- A high-performance **core actor model**
- **Ask / Tell / SendReceive / Join** messaging patterns
- **Routers** (Round-Robin, Random, Broadcast)
- **Supervision** (Restart, Stop, Resume, Escalate)
- **Remote Actors** (HTTP outbound)
- A small **Groovy DSL** for actor definitions
- A full **test harness** demonstrating all major features

---

# 📦 Features

### ✔ Virtual-Thread Actors  
Each actor runs in its own **virtual thread**, enabling millions of lightweight actors.

### ✔ ScopedValue Isolation  
Per-message contextual state is stored using JDK ScopedValue for safe, thread-local isolation.

### ✔ Fully Typed Under `@CompileStatic`  
All components use strong typing—no dynamic dispatch in core runtime.

### ✔ Routers  
Built-in routing strategies:

- `ROUND_ROBIN`
- `RANDOM`
- `BROADCAST`

Routers are themselves actors.

### ✔ Supervision  
`SupervisorActor` supports:

- `RESTART`
- `STOP`
- `RESUME`
- `ESCALATE`

Child actors include factories, allowing restart with clean state.

### ✔ Remote Actors  
Send messages to remote HTTP endpoints via `RemoteActor`.

### ✔ DSL  
Create actors concisely:

```groovy
def printer = Actors.actor {
    name "Printer"
    onMessage { msg, ctx -> println "[Printer] $msg" }
}
