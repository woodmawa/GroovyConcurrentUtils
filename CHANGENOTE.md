# GroovyConcurrentUtils change notes

History of changes, newest first. One section per version: what changed, why, what a consumer has to do.
Entries before 1.2.0 are reconstructed from commit messages.

## Security controls chapter and hardening plan (2026-11-20) — documentation only, version stays 1.2.1

- **New chapter, "Security Controls: What Is Enforced and What Is Planned"** (`docs/asciidoc/chapters/12-security-controls.adoc`), in both books. For scripts and rule conditions, shell commands, HTTP calls, file operations, remote actor transports, the cluster, Java deserialisation and configuration files it states what limits each one today, the agreed design, and the release that delivers it, with a status table and a list of how to use the library safely at this version.
- **Every release that changes a control updates that chapter**: its row in the status table and the section that describes it.
- **Agreed plan** (project doc `gcu/brief-core-hardening-2026-11-18`, rev 5): 1.2.2 defects; 1.3.0 safe defaults (`HttpTask` guard, `FileTask` default, remoting defaults, cluster signing); 1.4.0 restricted evaluation for `ScriptTask` and `ControlTask` plus a `ShellTask`; 2.0.0 module split with a `gcu-taskgraph` jar and `GroovyConcurrentUtils` kept as an aggregate.

## 1.2.1 (2026-11-20) — second hardening pass: actors, agents, serialisation, cluster settings, config, timers

Source: second security, completeness and robustness review, project doc `gcu/review-2-2026-11-20`. It read the parts the first review did not (actors, agents, pools, the rest of promises, config, security services, cluster, remoting) and took in the findings made while writing the book.
Tests: 2110 passing, 0 failing (2093 before, plus 17 in `Hardening121Spec`). All 17 were run red on 1.2.0 before the fixes.

### Fixed

Actors

- **An actor stopping itself deadlocked (A1).** A supervision `STOP` directive, reaching the restart limit, and `ESCALATE` with no parent all called `stop()` on the mailbox loop's own thread, which then waited for the loop to end. The actor never terminated and the caller of the failing `ask` waited out its timeout. `stop()` on the loop thread now records the stop; the loop finishes it when the handler returns, and the caller gets the handler's error at once.
- **`stop()` dropped queued messages (A3).** Documented as graceful, it processed only the message in hand and discarded the rest of the mailbox. It now processes everything queued before the stop.
- **Asks left in the mailbox waited for their timeout (A3).** When an actor is force-stopped or stops itself, asks that will never be processed now fail immediately with `IllegalStateException`.
- **The text `__STOP__` killed the mailbox loop (A4).** The stop marker was a string any sender could send, after which the actor accepted messages and never processed them. It is now a private object compared by identity.
- `stop(timeout)` now notifies death-watchers when it completes, as `stop()` does.

Agents

- **`getStateVersion()` counted reads (G1).** It advanced on `getValue()` and never on an update. It now advances once per update that completes and is unchanged by reads.

Remoting

- **Unfiltered Java deserialisation (S1).** `JavaSerializer.deserialize` read any class from the bytes it was given. It now reads through an allow-list: JDK value types and `GString` by default, plus package prefixes passed to `new JavaSerializer([...])`. Anything else is refused with `SerializationException`; depth, reference count and array length are capped.

Cluster

- **`HazelcastSecurityConfig` was never applied (C1).** `HazelcastManager.initialize` built its configuration from the cluster map alone. New `initialize(clusterConfig, security)` and `HazelcastManager.buildConfig(clusterConfig, security)` validate and apply the settings: bind address, port, port auto-increment, member allow-list, multicast only if allowed, TLS and member authentication. With a security config there is no fallback to multicast and a start-up failure is thrown instead of logged.

Configuration

- **External sources that were written but not wired (F1).** `ConfigLoader.load` read external files only from the `externalFiles` option. It now also reads files named by `-Dapp.config` and `config[-profile].*` in directories from `-Dapp.configDir` and the `externalDirs` option. `app.config` and `app.configDir` are no longer copied into the settings as keys.

TaskGraph

- **`TaskBinding.set(key, null)` threw (M2).** Setting null now removes the key, for `set` and for `binding[key] = null`.

Pools and promises

- **Cancelled timers stayed queued (P3).** A timeout timer cancelled because its task finished stayed in the shared scheduler, holding the task's future and result, until the timeout would have fired. The shared scheduler now removes cancelled timers at once. This covers `executeWithTimeout` and `Promise.timeout`.
- **`orTimeout` never cancelled its timer (DF8).** It now does, when the promise completes.
- **`Promises.from(CompletableFuture)` kept the wrapper (DF14).** A failed future's `CompletionException` is unwrapped; the promise fails with the real cause.

Build

- POM name, description, URL and SCM defaults said GroovyFX. They now describe this library.

### Behaviour changes a consumer can see

1. `actor.stop()` takes as long as the queued messages take to process. Use `stop(timeout)` or `stopNow()` to bound it.
2. An ask queued on an actor that is force-stopped fails with `ExecutionException` (cause `IllegalStateException`) and not `TimeoutException`.
3. `new JavaSerializer()` refuses application classes on deserialise. Pass the package prefixes of your message classes to the constructor.
4. `agent.stateVersion` no longer changes on reads.
5. A JVM started with `-Dapp.config=...` or `-Dapp.configDir=...` now loads those files, and the settings map no longer contains the keys `config` and `configDir` from them.
6. `binding.set(k, null)` removes `k` where it used to throw `NullPointerException`.
7. `HazelcastManager.initialize(config)` with one argument is unchanged.

Nothing on the TaskGraph execution path changed except M2 and the timer release. AW should still be run against 1.2.1 before it is adopted.

### Documentation

- The book describes the changed behaviour in the actors, agents, clustering, configuration, promises and pools chapters.
- **Second book.** `docs/asciidoc/taskgraph.adoc` builds the TaskGraph chapters on their own (`groovy-concurrent-utils-taskgraph.pdf`) from the same chapter files as Part V of the full book.

### Not in this release

Still open, with the decisions they wait on, in `gcu/brief-core-hardening-2026-11-18` (rev 4): restricted script evaluation (D2), the `HttpTask` guard (D3), `FileTask`'s permissive default, result redaction, `TaskContext` lazy caches and `Builder.config`, dataflow items DF7, DF9 to DF13 and DF15, and from this review: remote transports bind to all interfaces and accept unauthenticated callers by default, `setMaxMailboxSize` on a running actor strands the loop on the old queue, `actor << msg` is a blocking ask, `ErrorMode.RETRY` only logs. TLS and member authentication for the cluster need Hazelcast Enterprise.

One existing test, `ManualTaskTest.testManualTaskDSLWithNotificationsAndEscalation`, failed once in three full runs today (before any change) and passed in the other two. It is timing-dependent and was not touched.

## Documentation restructure (2026-11-20) — no code change, no version change

The documentation now follows the same layout as the MCP server projects: one book, `docs/asciidoc/index.adoc` plus `chapters/`, built to HTML (`gradlew asciidoctor`) and PDF (`gradlew asciidoctorPdf`, output `build/docs/asciidocPdf/groovy-concurrent-utils-reference.pdf`).

- **New book, "GroovyConcurrentUtils: Capabilities and Reference".** Parts I to IV are new chapters, one per capability, written against the 1.2.0 source: overview, pools, promises, dataflow, Gstream, reactive channels, actors, agents, configuration, security services, clustering, plus a glossary and a history map.
- **TaskGraph guide moved in as Part V.** The 17 chapters moved from `src/main/groovy/org/softwood/dag/docs/asciidoc/chapters/` to `docs/asciidoc/chapters/tg-*.adoc`. Content unchanged except: hard-coded "Chapter N" references became cross-references (the chapters renumber inside the larger book), a duplicate anchor in the TaskGraphSpec chapter and a reference to a missing image were removed.
- **Superseded material archived.** The capability readmes from `docs/`, the implementation notes that lived under `src/main/groovy`, and the old TaskGraph index and generated PDF are under `archive/`. They are no longer maintained.
- **Build.** `org.asciidoctor.jvm.pdf` 4.0.5 added; AsciidoctorJ pinned to 3.0.1 (the plugin default was 2.5.7 on JRuby 9.3.8.0); the tasks convert `index.adoc` only; the reference to a stylesheet that did not exist is gone; JVM flags stop the JRuby native-access and `sun.misc.Unsafe` warnings on JDK 25. The redundant `repositories` block in `build.gradle` is removed (settings.gradle declares them). A docs build went from 28 warnings to none.

Found while writing the chapters from the source. The first two and the last were fixed in 1.2.1 (above); the others are documented in the book as they behave:

- `HazelcastManager.initialize` does not apply `HazelcastSecurityConfig`: a cluster started through it has no TLS and no member authentication, and uses multicast when no member list is given.
- `Agent.getStateVersion()` advances on `getValue()` (a read), not on updates.
- `actor << msg` is a blocking `askSync` with a five-second timeout, not a send. Local `Actor.ask` blocks; `RemoteActorRef.ask` returns a `CompletableFuture`.
- `ErrorMode.RETRY` in the reactive package only logs.
- The POM defaults in `build.gradle` (`pomName`, `pomDescription`, `pomUrl`, SCM) still say GroovyFX.

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
