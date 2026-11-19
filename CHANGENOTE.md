# GroovyConcurrentUtils change notes

History of changes, newest first. One section per version: what changed, why, what a consumer has to do.
Entries before 1.2.0 are reconstructed from commit messages.

## 1.2.0 (2026-11-18) — core hardening: dataflow delivery, graph validation, task state, cancellation

Source: security review and dataflow review of 2026-11-18, brief `gcu/brief-core-hardening-2026-11-18` (WP1 and WP2, plus the dataflow findings the review added).
Tests: 2093 passing, 0 failing (2075 before, plus 12 in `CoreHardeningSpec` and 6 in `DataflowIntegritySpec`). Every new test that guards a fix was run red on 1.1.2 first.

### Fixed

Dataflow (the layer every TaskGraph result and callback travels through)

- **Lost callbacks (DF1).** A listener registered while a `DataflowVariable` was being bound could be missed for ever. Registration is now decided under the completion lock. Affects `whenBound`, `whenAvailable`, `whenError`, `then`, `recover`, `toFuture` and every `DataflowPromise` callback and combinator built on them, including `Promises.all`. Measured before the fix: 41 of 20,000 racing registrations never delivered.
- **`get()` that never returned (DF2).** `DataflowPromise.get()` and `get(timeout, unit)` read the variable's own future instead of a second future completed by listeners. Measured before the fix: 3 of 20,000 calls racing `accept()` timed out with the value already set.
- **Error state visible before its error (H5).** `setError` and `setCancelled` assign the error before the state. The substitute "Error state detected but error value is null" exception is gone, and so are the three `Thread.sleep(10)` retry workarounds for it in `TaskBase` and `ServiceTask`.
- **`cancel(true)` did not interrupt (DF3).** A promise from `executeAsync` now holds its pool `Future`; `cancel(true)` interrupts the running work, `cancel(false)` leaves it to finish.
- **Unbounded listener error list (DF4).** Capped at 100 entries as documented.
- **`Dataflows` wiring (DF5).** Assigning a failed variable to another now fails the target instead of binding `null`.
- **Late task completion (DF6).** A task that finishes after its variable was completed elsewhere is logged at debug instead of throwing into a discarded `Future`.

TaskGraph and TaskBase

- **Graph validation (H1).** New `TaskGraph.validate()`, called by `start()` before the graph is consumed. A dependency cycle, a graph with no root, or an edge to a task that does not exist is refused with an `IllegalStateException` naming the tasks. All three used to return a promise that never resolved.
- **Guarded task state (H3).** `TaskBase` state assignments go through `transitionState()`. A CANCELLED or SKIPPED task can no longer be overwritten with RUNNING, COMPLETED or FAILED by its own worker.
- **Single outcome (H6).** A run's outcome is claimed once, by completion, `abortFlow()` or graph timeout. `GRAPH_COMPLETED` / `GRAPH_FAILED` is emitted exactly once. Measured before the fix: two terminal events in the majority of 150 runs of a two-leaf graph. `start()` is claimed atomically as well.
- **Cancellation with correct state (H2).** `abortFlow()` and a graph timeout set a cancellation signal on the `TaskContext`. No new attempt starts, a retry wait ends without retrying, a cancelled task whose work finishes later stays CANCELLED and schedules no successor, and no terminal graph event follows an abort. A graph timeout now also resolves `graphCompletionPromise`, not only the promise `start()` returned.
- **Interrupts and JVM errors (H4).** An `InterruptedException` or `CancellationException` (direct or as a cause) ends the task as CANCELLED and is never retried. `VirtualMachineError` and `LinkageError` fail the task without retry.

### Added

- `TaskGraph.validate()`.
- `TaskContext.requestCancel()`, `isCancelRequested()`, `clearCancel()`.
- `interruptOnTimeout true` on a task and `interruptOnCancel true` on a graph: opt-in interruption of work that is already running. Both default to false.
- `DataflowVariable.attachTask(Future)` and `cancelTask(boolean)`.
- `TaskBase.interruptInFlight()`.

### Behaviour changes a consumer can see

1. `start()` throws for a graph that cannot complete. Previously it returned a promise that hung.
2. `promise.cancel(true)` on an `executeAsync` promise interrupts the work. Pass `false` for the old behaviour.
3. A task that was cancelled reports CANCELLED, not COMPLETED or FAILED, and a graph containing a cancelled task never reports `GRAPH_COMPLETED`.
4. After `abortFlow()` no `GRAPH_COMPLETED` / `GRAPH_FAILED` event is emitted. The aborting caller owns the terminal report.
5. An interrupted or cancelled task is not retried and is not written to the dead letter queue.
6. `task.state = X` is ignored when X is not a legal transition, except an explicit reset to CREATED or SCHEDULED. `execute()` on a COMPLETED or FAILED task restarts it.
7. `DataflowExpression.getError()` no longer returns a substitute `IllegalStateException`.

Unchanged by default: a timed-out or aborted task's running work is still left to finish unless the new opt-in flags are set.

### Not in this release

Still open from the reviews: the script "sandbox" is not isolation, `ControlTask` evaluates unrestricted Groovy, `HttpTask` does not apply `HttpTaskSecurityConfig`, `FileTask` defaults to a permissive security config, results are unredacted in logs/events/persistence, `TaskBinding.set(key, null)` throws, and dataflow items DF7 to DF15 (see `gcu/dataflow-review-2026-11-18`).

### Documentation

- New chapter `16-execution-integrity.adoc`: validation, task state, how a run ends, cancellation, timeouts, retries, dataflow delivery guarantees, known limits.
- `09-security.adoc`, `01-architecture.adoc`, `11-taskbase.adoc` and `index.adoc` corrected where they described behaviour the code does not have.

### Verification

See the release record in the project doc `gcu/release-1.2.0` for the test counts, the AW compatibility run and what was not verified.

## 1.1.2 (2026-11-16) — Groovy 6.0.0

- Groovy 5.0.3 to 6.0.0; Spock 2.5-groovy-6.0.
- `GraphEventListener.getName()` uses `getClass()`: `this.class` inside an interface default method throws on Groovy 6.
- GroovyCompile worker heap raised to 4g for the test source set.
- No API change. 2075 tests.

## 1.1.1

- `IBinding.getPage(offset, size)` default and `TaskBinding` override; `TaskBindingSpec`.
- Task-context chapter: paging section.

## 1.1.0

- Context explosion protection.
- `ITaskProvider` SPI extension pattern documented.
