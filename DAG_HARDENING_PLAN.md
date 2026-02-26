# DAG / TaskGraph Hardening Plan
Generated: 2026-02-26  
Assessment session: Deep review of TaskGraph, TaskGraphDsl, TaskBase, TaskContext, ServiceTask, TaskFactory, ForkDsl, RouterTask, TaskGraphFactory, Tasks utility.

---

## Status Legend
- `[ ]` TODO
- `[x]` DONE
- `[~]` IN PROGRESS

---

## CRITICAL (correctness / thread safety) — ALL DONE ✅

| # | Status | File(s) | Issue |
|---|--------|---------|-------|
| C1 | [x] | `TaskBase.groovy` | completionPromise pre-assigned before runAttempt(); callbacks wire result into holder |
| C2 | [x] | `TaskContext.groovy`, `DataflowPromiseFactory.groovy` | DataflowPromiseFactory(ExecutorPool) ctor added; all TaskContext ctors wire pool into factory |
| C3 | [x] | `TaskGraph.groovy` | markDownstreamAsSkipped() BFS wrapped in synchronized(this) |
| C4 | [x] | `TaskContext.groovy`, `Tasks.groovy` | TaskContext implements Closeable; Tasks.all/any/sequence/execute use try/finally ctx.close() |

---

## HIGH (design correctness) — ALL DONE ✅

| # | Status | File(s) | Issue |
|---|--------|---------|-------|
| H5 | [x] | `TaskBase.groovy`, `TaskState.groovy` | PENDING removed, CREATED added as initial state; hasStarted = state.ordinal() >= RUNNING.ordinal() |
| H6 | [x] | `TaskGraphDsl.groovy` | dependsOn(downstream, upstream...) - consistent with ITask.dependsOn() semantics. Docs clarify ordering. |
| H7 | [x] | `ITask.groovy`, `IGraphNode.groovy` | IGraphNode<T> extends ITask<T> — graph-internal plumbing (addSuccessor, buildPrevPromise etc) in IGraphNode. ITask is clean user-facing contract. |
| H8 | [x] | `TaskBase.groovy` | AtomicReference + transitionState() CAS loop; all markX() go through guard |
| H9 | [x] | `TaskState.groovy`, `TaskGraph.groovy` | CANCELLED state added; canTransitionTo() state machine; cancelRemainingTasks() calls markCancelled() |

---

## MEDIUM (DSL quality) — ALL DONE ✅

| # | Status | File(s) | Issue |
|---|--------|---------|-------|
| M10 | [x] | `ForkDsl.groovy` | route(), conditionalOn(), shard() marked @Deprecated with Javadoc pointing to canonical names |
| M11 | [x] | `TaskGraphDsl.groovy` | globals{} corrected to @DelegatesTo(TaskBinding) |
| M12 | [x] | `TaskGraphDsl.groovy` | task() is PRIMARY entry point; serviceTask() is alias with note |
| M13 | [x] | `TaskGraphDsl.groovy` | Implicit join names are deterministic: ${taskId}-join (no UUID) |
| M14 | [x] | `Tasks.groovy` | Fixed as part of C4 — try/finally ctx.close() on all static methods |

---

## LOW (polish)

| # | Status | File(s) | Issue |
|---|--------|---------|-------|
| L15 | [x] | `ITask.groovy` | ITask<T> kept with Javadoc noting future call-site parameterisation as follow-on work |
| L16 | [x] | `ITask.groovy`, `TaskBase.groovy` | getCtx() typed as TaskContext (not def) |
| L17 | [ ] | `TaskGraph.groovy` | 43KB monolith — extract ClusteringDelegate, PersistenceDelegate, TimeoutEnforcer (deliberately deferred: large effort, no correctness impact) |

---

## ✅ HARDENING COMPLETE (16/17)

All correctness, threading, DSL design, and polish items are done.  
L17 (TaskGraph monolith refactor) is deferred — low priority with no correctness impact.

**Full test suite passes** after clean build.  
Committed as: `feat: DAG hardening - all 16 items complete`

---

## Session Continuity
If returning to this work:
1. Read this file — only L17 remains (deferred)
2. Run `gradle clean test` to verify green baseline
3. L17 is a pure refactor of TaskGraph.groovy into delegate classes — no behaviour change
